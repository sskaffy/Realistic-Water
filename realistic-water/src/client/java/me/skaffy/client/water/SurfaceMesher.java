package me.skaffy.client.water;

import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import org.lwjgl.vulkan.VkCommandBuffer;

final class SurfaceMesher implements AutoCloseable {
	static final long DRAW_ARGS_OFFSET = 16;

	final int subdivision;
	final int fx;
	final int fy;
	final int fz;
	final int fineCount;
	final int cubeCount;
	final int vertexCapacity;
	final int quadCapacity;

	private final VkBuf accum;
	final VkBuf densA;
	private final VkBuf vertexId;
	final VkBuf verts;
	final VkBuf quads;
	final VkBuf meshCounters;

	private final ComputeProgram clear;
	private final ComputeProgram splat;
	private final ComputeProgram blur;
	private final ComputeProgram netsVertices;
	private final ComputeProgram netsQuads;
	private final ComputeProgram meshArgs;

	SurfaceMesher(int nx, int ny, int nz, int subdivision) {
		this.subdivision = subdivision;
		this.fx = nx * subdivision;
		this.fy = ny * subdivision;
		this.fz = nz * subdivision;
		this.fineCount = this.fx * this.fy * this.fz;
		this.cubeCount = (this.fx + 1) * (this.fy + 1) * (this.fz + 1);
		this.vertexCapacity = Math.clamp(this.fineCount / 6, 200_000, 3_000_000);
		this.quadCapacity = this.vertexCapacity + this.vertexCapacity / 4;

		this.accum = VkBuf.device(this.fineCount * 4L, 0);
		this.densA = VkBuf.device(this.fineCount * 4L, 0);
		this.vertexId = VkBuf.device(this.cubeCount * 4L, 0);
		this.verts = VkBuf.device(this.vertexCapacity * 16L, 0);
		this.quads = VkBuf.device(this.quadCapacity * 16L, 0);
		this.meshCounters = VkBuf.device(64, VkBuf.INDIRECT);

		if (shared == null) {
			int U = Desc.UBO;
			int S = Desc.SSBO;
			shared = new ComputeProgram[]{
				new ComputeProgram("fine_clear.comp", U, S),
				new ComputeProgram("splat.comp", U, S, S, S),
				new ComputeProgram("blur.comp", U, S, S, S),
				new ComputeProgram("nets_vertices.comp", U, S, S, S, S, S),
				new ComputeProgram("nets_quads.comp", U, S, S, S, S),
				new ComputeProgram("mesh_args.comp", U, S)
			};
		}
		this.clear = shared[0];
		this.splat = shared[1];
		this.blur = shared[2];
		this.netsVertices = shared[3];
		this.netsQuads = shared[4];
		this.meshArgs = shared[5];
	}

	private static ComputeProgram[] shared;

	static void closePrograms() {
		if (shared != null) {
			for (ComputeProgram p : shared) {
				p.close();
			}
			shared = null;
		}
	}

	int[] fineBox(int[] box) {
		int[] f = new int[6];
		for (int a = 0; a < 6; a++) {
			f[a] = box[a] * this.subdivision;
		}
		return f;
	}

	static int voxels(int[] fineBox) {
		return Math.max(0, fineBox[3] - fineBox[0]) * Math.max(0, fineBox[4] - fineBox[1]) * Math.max(0, fineBox[5] - fineBox[2]);
	}

	static int cubes(int[] fineBox) {
		return (fineBox[3] - fineBox[0] + 1) * (fineBox[4] - fineBox[1] + 1) * (fineBox[5] - fineBox[2] + 1);
	}

	void build(VkCommandBuffer cb, Desc ubo, VkBuf particles, VkBuf counters, int counterIndex, VkBuf dispatchArgs, VkBuf staticSolid,
		int[] fineBox) {
		int groups = ComputeProgram.groups(voxels(fineBox), 256);
		int cubeGroups = ComputeProgram.groups(cubes(fineBox), 256);
		this.meshCounters.fill(cb, 0);
		this.clear.bind(cb, ubo, Desc.ssbo(this.accum));
		this.clear.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);

		this.splat.bind(cb, ubo, Desc.ssbo(particles), Desc.ssbo(counters), Desc.ssbo(this.accum));
		this.splat.push(cb, counterIndex);
		this.splat.dispatchIndirect(cb, dispatchArgs, WaterRegion.ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);

		this.blur.bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.densA), Desc.ssbo(this.densA));
		this.blur.push(cb, 0, 1, 0);
		this.blur.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);
		this.blur.bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.densA), Desc.ssbo(this.accum));
		this.blur.push(cb, 1, 0, 0);
		this.blur.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);
		this.blur.bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.accum), Desc.ssbo(this.densA));
		this.blur.push(cb, 2, 0, 1);
		this.blur.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);

		this.netsVertices.bind(cb, ubo, Desc.ssbo(this.densA), Desc.ssbo(this.vertexId), Desc.ssbo(this.verts), Desc.ssbo(this.meshCounters),
			Desc.ssbo(staticSolid));
		this.netsVertices.push(cb, this.vertexCapacity);
		this.netsVertices.dispatch(cb, cubeGroups, 1, 1);
		Vk.barrier(cb);
		this.netsQuads.bind(cb, ubo, Desc.ssbo(this.densA), Desc.ssbo(this.vertexId), Desc.ssbo(this.quads), Desc.ssbo(this.meshCounters));
		this.netsQuads.push(cb, 0, this.quadCapacity);
		this.netsQuads.dispatch(cb, cubeGroups, 1, 1);
		Vk.barrier(cb);
		this.meshArgs.bind(cb, ubo, Desc.ssbo(this.meshCounters));
		this.meshArgs.push(cb, 0, this.quadCapacity);
		this.meshArgs.dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
	}

	@Override
	public void close() {
		for (AutoCloseable c : new AutoCloseable[]{this.accum, this.densA, this.vertexId, this.verts, this.quads, this.meshCounters}) {
			try {
				c.close();
			} catch (Exception ignored) {
			}
		}
	}
}
