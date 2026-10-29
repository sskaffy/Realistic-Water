package me.skaffy.client.water;

import java.util.ArrayList;
import java.util.List;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;

final class SurfaceMesher implements AutoCloseable {
	static final long DRAW_ARGS_OFFSET = 16;
	private static final int INSIDE_RADIUS = 2;
	private static final int SCAN_BLOCK = 1024;

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
	private @Nullable VkBuf zb;
	private final VkBuf vertexId;
	final VkBuf verts;
	final VkBuf quads;
	final VkBuf meshCounters;
	private final VkBuf occupancy;
	private final VkBuf depthA;
	private final VkBuf depthB;
	private final VkBuf start;
	private final VkBuf cursor;
	private final VkBuf scanSums;
	private @Nullable VkBuf sorted;
	private final VkBuf blockMask;
	private final VkBuf nearMask;
	private boolean maskValid;

	private final ComputeProgram clear;
	private final ComputeProgram zbSplat;
	private final ComputeProgram zbField;
	private final ComputeProgram blur;
	private final ComputeProgram netsVertices;
	private final ComputeProgram netsQuads;
	private final ComputeProgram meshArgs;
	private final ComputeProgram sheetFill;
	private final ComputeProgram occupancyCount;
	private final ComputeProgram depth;
	private final ComputeProgram scanLocal;
	private final ComputeProgram scanAdd;
	private final ComputeProgram bin;
	private final ComputeProgram gather;
	private final ComputeProgram binTotal;
	private final ComputeProgram wallFill;
	private final ComputeProgram blockDilate;

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
		this.vertexId = VkBuf.device(this.cubeCount * 4L, 0);
		this.verts = VkBuf.device(this.vertexCapacity * 16L, 0);
		this.quads = VkBuf.device(this.quadCapacity * 16L, 0);
		this.meshCounters = VkBuf.device(64, VkBuf.INDIRECT);
		long cells = (long) nx * ny * nz;
		this.occupancy = VkBuf.device(cells * 4L, 0);
		this.depthA = VkBuf.device(cells * 4L, 0);
		this.depthB = VkBuf.device(cells * 4L, 0);
		this.start = VkBuf.device(cells * 4L, 0);
		this.cursor = VkBuf.device(cells * 4L, 0);
		this.scanSums = VkBuf.device((cells / (SCAN_BLOCK - 1) + 16) * 4L, 0);
		long blocks = (long) Math.ceilDiv(this.fx, 8) * Math.ceilDiv(this.fy, 8) * Math.ceilDiv(this.fz, 4);
		this.blockMask = VkBuf.device(blocks * 4L, 0);
		this.nearMask = VkBuf.device(blocks * 4L, 0);

		if (shared == null) {
			int U = Desc.UBO;
			int S = Desc.SSBO;
			shared = new ComputeProgram[]{
				new ComputeProgram("fine_clear.comp", U, S, S),
				new ComputeProgram("blur.comp", U, S, S, S, S),
				new ComputeProgram("nets_vertices.comp", U, S, S, S, S, S),
				new ComputeProgram("nets_quads.comp", U, S, S, S, S),
				new ComputeProgram("mesh_args.comp", U, S),
				new ComputeProgram("sheet_fill.comp", U, S, S, S),
				new ComputeProgram("zb_splat.comp", U, S, S, S, S),
				new ComputeProgram("zb_field.comp", U, S, S, S),
				new ComputeProgram("mesh_occupancy.comp", U, S, S, S),
				new ComputeProgram("mesh_depth.comp", U, S, S),
				new ComputeProgram("scan_local.comp", S, S),
				new ComputeProgram("scan_add.comp", S, S),
				new ComputeProgram("mesh_bin.comp", U, S, S, S, S),
				new ComputeProgram("zb_gather.comp", U, S, S, S, S, S, S),
				new ComputeProgram("mesh_bin_total.comp", U, S, S, S),
				new ComputeProgram("wall_fill.comp", U, S, S, S, S),
				new ComputeProgram("mesh_block_dilate.comp", S, S)
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
		this.occupancyCount = shared[8];
		this.depth = shared[9];
		this.scanLocal = shared[10];
		this.scanAdd = shared[11];
		this.bin = shared[12];
		this.gather = shared[13];
		this.binTotal = shared[14];
		this.wallFill = shared[15];
		this.blockDilate = shared[16];
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
		VkBuf cellFlags, int[] fineBox, boolean fillSheets, @Nullable VkBuf sortInto, int sortCounter) {
		boolean wallFill = WaterSettings.wallFill && WaterSettings.wallFillReach > 0.0F;
		boolean blurIntoB = wallFill && !fillSheets;
		this.buildField(cb, ubo, particles, counters, counterIndex, dispatchArgs, fineBox, fillSheets, sortInto, sortCounter, blurIntoB);
		if (wallFill) {
			int reach = Math.max(1, Math.round(WaterSettings.wallFillReach * this.subdivision));
			if (!blurIntoB) {
				this.densA.copyTo(cb, this.densB, 0, 0, this.densA.size);
				Vk.barrier(cb);
			}
			this.wallFill.bind(cb, ubo, Desc.ssbo(this.densB), Desc.ssbo(this.densA), Desc.ssbo(staticSolid), Desc.ssbo(cellFlags));
			this.wallFill.push(cb, reach);
			this.wallFill.dispatch(cb, ComputeProgram.groups(voxels(fineBox), 256), 1, 1);
			Vk.barrier(cb);
		}
		int cubeGroups = ComputeProgram.groups(cubes(fineBox), 256);
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

	void buildField(VkCommandBuffer cb, Desc ubo, VkBuf particles, VkBuf counters, int counterIndex, VkBuf dispatchArgs, int[] fineBox,
		boolean fillSheets, @Nullable VkBuf sortInto, int sortCounter) {
		this.buildField(cb, ubo, particles, counters, counterIndex, dispatchArgs, fineBox, fillSheets, sortInto, sortCounter, false);
	}

	private void buildField(VkCommandBuffer cb, Desc ubo, VkBuf particles, VkBuf counters, int counterIndex, VkBuf dispatchArgs, int[] fineBox,
		boolean fillSheets, @Nullable VkBuf sortInto, int sortCounter, boolean intoB) {
		int groups = ComputeProgram.groups(voxels(fineBox), 256);
		int s = this.subdivision;
		int cells = (fineBox[3] - fineBox[0]) / s * ((fineBox[4] - fineBox[1]) / s) * ((fineBox[5] - fineBox[2]) / s);
		boolean gather = WaterSettings.surfaceGather;
		this.maskValid = false;
		this.meshCounters.fill(cb, 0);
		if (gather || WaterSettings.surfaceInteriorSkip) {
			this.occupancy.fill(cb, 0, (long) cells * 4L, 0);
			Vk.barrier(cb);
			this.occupancyCount.bind(cb, ubo, Desc.ssbo(particles), Desc.ssbo(counters), Desc.ssbo(this.occupancy));
			this.occupancyCount.push(cb, counterIndex);
			this.occupancyCount.dispatchIndirect(cb, dispatchArgs, WaterRegion.ARGS_FLUID_DISPATCH);
			Vk.barrier(cb);
		}
		int[] skip = this.markInterior(cb, ubo, cells);
		if (gather) {
			this.gatherField(cb, ubo, particles, counters, counterIndex, dispatchArgs, fineBox, cells, skip[1], sortInto, sortCounter);
		} else {
			this.splatField(cb, ubo, particles, counters, counterIndex, dispatchArgs, groups, skip);
		}

		int mask = this.maskValid ? 1 : 0;
		this.blur.bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.densB), Desc.ssbo(this.accum), Desc.ssbo(this.nearMask));
		this.blur.push(cb, 0, 0, 0, mask);
		this.blur.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);
		VkBuf middle = intoB ? this.densA : this.densB;
		this.blur.bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.accum), Desc.ssbo(middle), Desc.ssbo(this.nearMask));
		this.blur.push(cb, 1, 0, 0, mask);
		this.blur.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);
		this.blur.bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(middle), Desc.ssbo(intoB ? this.densB : this.densA), Desc.ssbo(this.nearMask));
		this.blur.push(cb, 2, 0, 1, mask);
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
	}

	private void splatField(VkCommandBuffer cb, Desc ubo, VkBuf particles, VkBuf counters, int counterIndex, VkBuf dispatchArgs, int groups, int[] skip) {
		if (this.zb == null) {
			this.zb = VkBuf.device(this.fineCount * 16L, 0);
		}
		this.clear.bind(cb, ubo, Desc.ssbo(this.zb), Desc.ssbo(this.depthA));
		this.clear.push(cb, 0, skip[1]);
		this.clear.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);
		this.zbSplat.bind(cb, ubo, Desc.ssbo(particles), Desc.ssbo(counters), Desc.ssbo(this.zb), Desc.ssbo(this.depthA));
		this.zbSplat.push(cb, counterIndex, skip[0], 0, 0, Program.f(WaterSettings.surfaceKernel));
		this.zbSplat.dispatchIndirect(cb, dispatchArgs, WaterRegion.ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);
		this.zbField.bind(cb, ubo, Desc.ssbo(this.zb), Desc.ssbo(this.densB), Desc.ssbo(this.depthA));
		this.zbField.push(cb, 0, skip[1], 0, 0, Program.f(WaterSettings.surfaceRadius), Program.f(1.0F), Program.f(3.0F));
		this.zbField.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);
	}

	private void gatherField(VkCommandBuffer cb, Desc ubo, VkBuf particles, VkBuf counters, int counterIndex, VkBuf dispatchArgs, int[] fineBox, int cells,
		int insideDepth, @Nullable VkBuf sortInto, int sortCounter) {
		VkBuf target = sortInto;
		if (target == null) {
			if (this.sorted == null || this.sorted.size < particles.size) {
				if (this.sorted != null) {
					Vk.destroyLater(this.sorted);
				}
				this.sorted = VkBuf.device(particles.size, 0);
			}
			target = this.sorted;
		}
		this.occupancy.copyTo(cb, this.start, 0, 0, cells * 4L);
		Vk.barrier(cb);
		List<int[]> levels = new ArrayList<>();
		VkBuf data = this.start;
		int first = 0;
		int count = cells;
		int sums = 0;
		while (true) {
			int blocks = ComputeProgram.groups(count, SCAN_BLOCK);
			levels.add(new int[]{data == this.start ? 0 : 1, first, count, sums});
			this.scanLocal.bind(cb, Desc.ssbo(data), Desc.ssbo(this.scanSums));
			this.scanLocal.push(cb, first, count, sums);
			this.scanLocal.dispatch(cb, blocks, 1, 1);
			Vk.barrier(cb);
			if (blocks == 1) {
				break;
			}
			data = this.scanSums;
			first = sums;
			count = blocks;
			sums = first + blocks;
		}
		for (int l = levels.size() - 2; l >= 0; l--) {
			int[] level = levels.get(l);
			this.scanAdd.bind(cb, Desc.ssbo(level[0] == 0 ? this.start : this.scanSums), Desc.ssbo(this.scanSums));
			this.scanAdd.push(cb, level[1], level[2], level[3]);
			this.scanAdd.dispatch(cb, ComputeProgram.groups(level[2], 256), 1, 1);
			Vk.barrier(cb);
		}
		this.start.copyTo(cb, this.cursor, 0, 0, cells * 4L);
		if (sortInto != null) {
			this.binTotal.bind(cb, ubo, Desc.ssbo(this.start), Desc.ssbo(this.occupancy), Desc.ssbo(counters));
			this.binTotal.push(cb, sortCounter, cells);
			this.binTotal.dispatch(cb, 1, 1, 1);
		}
		Vk.barrier(cb);
		this.bin.bind(cb, ubo, Desc.ssbo(particles), Desc.ssbo(counters), Desc.ssbo(this.cursor), Desc.ssbo(target));
		this.bin.push(cb, counterIndex, sortInto != null ? sortCounter : -1);
		this.bin.dispatchIndirect(cb, dispatchArgs, WaterRegion.ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);
		int bx = Math.ceilDiv(fineBox[3] - fineBox[0], 8);
		int by = Math.ceilDiv(fineBox[4] - fineBox[1], 8);
		int bz = Math.ceilDiv(fineBox[5] - fineBox[2], 4);
		this.gather.bind(cb, ubo, Desc.ssbo(target), Desc.ssbo(this.start), Desc.ssbo(this.occupancy), Desc.ssbo(this.depthA), Desc.ssbo(this.densB),
			Desc.ssbo(this.blockMask));
		this.gather.push(cb, 0, 0, insideDepth, 0, Program.f(WaterSettings.surfaceKernel), Program.f(WaterSettings.surfaceRadius), Program.f(1.0F),
			Program.f(3.0F), bx, by, bz, 0);
		this.gather.dispatch(cb, bx * by * bz, 1, 1);
		Vk.barrier(cb);
		this.blockDilate.bind(cb, Desc.ssbo(this.blockMask), Desc.ssbo(this.nearMask));
		this.blockDilate.push(cb, bx, by, bz);
		this.blockDilate.dispatch(cb, ComputeProgram.groups((long) bx * by * bz, 256), 1, 1);
		Vk.barrier(cb);
		this.maskValid = true;
	}

	private int[] markInterior(VkCommandBuffer cb, Desc ubo, int cells) {
		if (!WaterSettings.surfaceInteriorSkip) {
			return new int[]{0, 0};
		}
		int deepest = INSIDE_RADIUS + (int) Math.ceil(WaterSettings.surfaceKernel / this.subdivision);
		VkBuf[] from = {this.occupancy, this.depthA, this.depthB};
		VkBuf[] to = {this.depthA, this.depthB, this.depthA};
		for (int axis = 0; axis < 3; axis++) {
			this.depth.bind(cb, ubo, Desc.ssbo(from[axis]), Desc.ssbo(to[axis]));
			this.depth.push(cb, axis, axis == 0 ? 1 : 0, deepest, Math.max(1, WaterSettings.surfaceInteriorCount));
			this.depth.dispatch(cb, ComputeProgram.groups(cells, 256), 1, 1);
			Vk.barrier(cb);
		}
		return new int[]{deepest + 1, INSIDE_RADIUS + 1};
	}

	@Override
	public void close() {
		for (AutoCloseable c : new AutoCloseable[]{this.accum, this.densA, this.densB, this.zb, this.vertexId, this.verts, this.quads, this.meshCounters,
			this.occupancy, this.depthA, this.depthB, this.start, this.cursor, this.scanSums, this.sorted, this.blockMask, this.nearMask}) {
			if (c == null) {
				continue;
			}
			try {
				c.close();
			} catch (Exception ignored) {
			}
		}
	}
}
