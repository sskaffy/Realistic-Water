package me.skaffy.client.water;

import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Program;
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
	private final VkBuf densB;
	private final VkBuf zb;
	private final VkBuf vertexId;
	final VkBuf verts;
	final VkBuf quads;
	final VkBuf meshCounters;

	private final ComputeProgram clear;
	private final ComputeProgram zbSplat;
	private final ComputeProgram zbField;
	private final ComputeProgram blur;
	private final ComputeProgram netsVertices;
	private final ComputeProgram netsQuads;
	private final ComputeProgram meshArgs;
	private final ComputeProgram sheetFill;

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
		this.densB = VkBuf.device(this.fineCount * 4L, 0);
		this.zb = VkBuf.device(this.fineCount * 16L, 0);
		this.vertexId = VkBuf.device(this.cubeCount * 4L, 0);
		this.verts = VkBuf.device(this.vertexCapacity * 16L, 0);
		this.quads = VkBuf.device(this.quadCapacity * 16L, 0);
		this.meshCounters = VkBuf.device(64, VkBuf.INDIRECT);

		if (shared == null) {
			int U = Desc.UBO;
			int S = Desc.SSBO;
			shared = new ComputeProgram[]{
				new ComputeProgram("fine_clear.comp", U, S),
				new ComputeProgram("blur.comp", U, S, S, S),
				new ComputeProgram("nets_vertices.comp", U, S, S, S, S, S),
				new ComputeProgram("nets_quads.comp", U, S, S, S, S),
				new ComputeProgram("mesh_args.comp", U, S),
				new ComputeProgram("sheet_fill.comp", U, S, S, S),
				new ComputeProgram("zb_splat.comp", U, S, S, S),
				new ComputeProgram("zb_field.comp", U, S, S)
			};
		}
		this.clear = shared[0];
		this.blur = shared[1];
		this.netsVertices = shared[2];
		this.netsQuads = shared[3];
		this.meshArgs = shared[4];
		this.sheetFill = shared[5];
		this.zbSplat = shared[6];
		this.zbField = shared[7];
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
		int[] fineBox, boolean fillSheets) {
		int groups = ComputeProgram.groups(voxels(fineBox), 256);
		int cubeGroups = ComputeProgram.groups(cubes(fineBox), 256);
		this.meshCounters.fill(cb, 0);
		this.clear.bind(cb, ubo, Desc.ssbo(this.zb));
		this.clear.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);

		this.zbSplat.bind(cb, ubo, Desc.ssbo(particles), Desc.ssbo(counters), Desc.ssbo(this.zb));
		this.zbSplat.push(cb, counterIndex, 0, 0, 0, Program.f(WaterSettings.surfaceKernel));
		this.zbSplat.dispatchIndirect(cb, dispatchArgs, WaterRegion.ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);
		this.zbField.bind(cb, ubo, Desc.ssbo(this.zb), Desc.ssbo(this.densB));
		this.zbField.push(cb, 0, 0, 0, 0, Program.f(WaterSettings.surfaceRadius), Program.f(1.0F), Program.f(3.0F));
		this.zbField.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);

		this.blur.bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.densB), Desc.ssbo(this.accum));
		this.blur.push(cb, 0, 0, 0);
		this.blur.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);
		this.blur.bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.accum), Desc.ssbo(this.densB));
		this.blur.push(cb, 1, 0, 0);
		this.blur.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);
		this.blur.bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.densB), Desc.ssbo(this.densA));
		this.blur.push(cb, 2, 0, 1);
		this.blur.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);

		if (fillSheets) {
			for (int pass = 0; pass < 3; pass++) {
				this.sheetFill.bind(cb, ubo, Desc.ssbo(this.densA), Desc.ssbo(this.densB), Desc.ssbo(this.accum));
				this.sheetFill.push(cb, pass, 0, 0, 0, Program.f(WaterSettings.sheetSmoothing));
				this.sheetFill.dispatch(cb, groups, 1, 1);
				Vk.barrier(cb);
			}
		}

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
		for (AutoCloseable c : new AutoCloseable[]{this.accum, this.densA, this.densB, this.zb, this.vertexId, this.verts, this.quads, this.meshCounters}) {
			try {
				c.close();
			} catch (Exception ignored) {
			}
		}
	}
}
