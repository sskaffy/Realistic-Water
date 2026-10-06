package me.skaffy.client.water;

import java.util.ArrayList;
import java.util.List;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import org.lwjgl.vulkan.VkCommandBuffer;

final class PressureSolver implements AutoCloseable {
	private static final int MAX_LEVELS = 14;
	private static final int COARSEST_CELLS = 512;
	private static final int RZ0 = 0;
	private static final int PQ = 2;

	private final int levels;
	private final int[][] dims;
	private final int[] offsets;
	private final int fineCells;
	private final int maxPartials;
	private final int[][] box;
	private int partialCount;
	private int partialSlots;

	private final VkBuf mgFlags;
	private final VkBuf mgB;
	private final VkBuf mgX;
	private final VkBuf r;
	private final VkBuf z;
	private final VkBuf p;
	private final VkBuf q;
	private final VkBuf partials;
	private final VkBuf scalars;

	private final ComputeProgram flags0;
	private final ComputeProgram coarsen;
	private final ComputeProgram smooth;
	private final ComputeProgram restrict;
	private final ComputeProgram prolong;
	private final ComputeProgram cgInit;
	private final ComputeProgram cgApply;
	private final ComputeProgram cgDot;
	private final ComputeProgram cgReduce;
	private final ComputeProgram cgUpdate;
	private final ComputeProgram cgDirection;
	private final ComputeProgram coarse;

	private static final class Programs {
		final ComputeProgram flags0;
		final ComputeProgram coarsen;
		final ComputeProgram smooth;
		final ComputeProgram restrict;
		final ComputeProgram prolong;
		final ComputeProgram cgInit;
		final ComputeProgram cgApply;
		final ComputeProgram cgDot;
		final ComputeProgram cgReduce;
		final ComputeProgram cgUpdate;
		final ComputeProgram cgDirection;
		final ComputeProgram coarse;

		Programs() {
			int U = Desc.UBO;
			int S = Desc.SSBO;
			this.flags0 = new ComputeProgram("mg_flags0.comp", U, S, S);
			this.coarsen = new ComputeProgram("mg_coarsen.comp", U, S);
			this.smooth = new ComputeProgram("mg_smooth.comp", U, S, S, S);
			this.restrict = new ComputeProgram("mg_restrict.comp", U, S, S, S, S, S);
			this.prolong = new ComputeProgram("mg_prolong.comp", U, S, S, S);
			this.cgInit = new ComputeProgram("cg_init.comp", U, S, S, S, S);
			this.cgApply = new ComputeProgram("cg_apply.comp", U, S, S, S, S);
			this.cgDot = new ComputeProgram("cg_dot.comp", U, S, S, S);
			this.cgReduce = new ComputeProgram("cg_reduce.comp", U, S, S);
			this.cgUpdate = new ComputeProgram("cg_update.comp", U, S, S, S, S, S);
			this.cgDirection = new ComputeProgram("cg_direction.comp", U, S, S, S);
			this.coarse = new ComputeProgram("mg_coarse.comp", U, S, S, S);
		}

		void close() {
			for (ComputeProgram p : new ComputeProgram[]{
				this.flags0, this.coarsen, this.smooth, this.restrict, this.prolong, this.cgInit, this.cgApply, this.cgDot, this.cgReduce,
				this.cgUpdate, this.cgDirection, this.coarse
			}) {
				p.close();
			}
		}
	}

	private static Programs shared;

	private static Programs programs() {
		if (shared == null) {
			shared = new Programs();
		}
		return shared;
	}

	static void closePrograms() {
		if (shared != null) {
			shared.close();
			shared = null;
		}
	}

	PressureSolver(int nx, int ny, int nz) {
		List<int[]> dimList = new ArrayList<>();
		int[] d = {nx, ny, nz};
		dimList.add(d);
		while (dimList.size() < MAX_LEVELS && (long) d[0] * d[1] * d[2] > COARSEST_CELLS) {
			d = new int[]{(d[0] + 1) / 2, (d[1] + 1) / 2, (d[2] + 1) / 2};
			dimList.add(d);
		}
		this.levels = dimList.size();
		this.dims = dimList.toArray(new int[0][]);
		this.offsets = new int[this.levels];
		int total = 0;
		for (int l = 0; l < this.levels; l++) {
			this.offsets[l] = total;
			total += this.cells(l);
		}
		this.fineCells = this.cells(0);
		this.maxPartials = ComputeProgram.launched(ComputeProgram.groups(this.fineCells, 256));
		this.box = new int[this.levels][6];

		this.mgFlags = VkBuf.device(total * 4L, 0);
		this.mgB = VkBuf.device(total * 4L, 0);
		this.mgX = VkBuf.device(total * 4L, 0);
		this.r = VkBuf.device(this.fineCells * 4L, 0);
		this.z = VkBuf.device(this.fineCells * 4L, 0);
		this.p = VkBuf.device(this.fineCells * 4L, 0);
		this.q = VkBuf.device(this.fineCells * 4L, 0);
		this.partials = VkBuf.device(this.maxPartials * 4L, 0);
		this.scalars = VkBuf.device(64, 0);

		Programs p = programs();
		this.flags0 = p.flags0;
		this.coarsen = p.coarsen;
		this.smooth = p.smooth;
		this.restrict = p.restrict;
		this.prolong = p.prolong;
		this.cgInit = p.cgInit;
		this.cgApply = p.cgApply;
		this.cgDot = p.cgDot;
		this.cgReduce = p.cgReduce;
		this.cgUpdate = p.cgUpdate;
		this.cgDirection = p.cgDirection;
		this.coarse = p.coarse;
	}

	int levels() {
		return this.levels;
	}

	private int cells(int l) {
		return this.dims[l][0] * this.dims[l][1] * this.dims[l][2];
	}

	private void setBox(int[] box0) {
		for (int l = 0; l < this.levels; l++) {
			int round = (1 << l) - 1;
			for (int a = 0; a < 3; a++) {
				this.box[l][a] = box0[a] >> l;
				this.box[l][3 + a] = Math.min((box0[3 + a] + round) >> l, this.dims[l][a]);
			}
		}
		this.partialCount = ComputeProgram.groups(this.boxCells(0), 256);
		this.partialSlots = ComputeProgram.launched(this.partialCount);
	}

	private int boxCells(int l) {
		int[] b = this.box[l];
		return Math.max(0, b[3] - b[0]) * Math.max(0, b[4] - b[1]) * Math.max(0, b[5] - b[2]);
	}

	private int boxGroups(int l) {
		return ComputeProgram.groups(this.boxCells(l), 256);
	}

	private int[] push(int fine, int coarse, int parity, int init) {
		int[] f = this.dims[fine];
		int[] c = this.dims[coarse];
		return new int[]{f[0], f[1], f[2], this.offsets[fine], c[0], c[1], c[2], this.offsets[coarse], parity, init, fine, coarse};
	}

	private VkBuf levelB(int l) {
		return l == 0 ? this.r : this.mgB;
	}

	private VkBuf levelX(int l) {
		return l == 0 ? this.z : this.mgX;
	}

	private void smooth(VkCommandBuffer cb, Desc ubo, int l, int parity, boolean init) {
		this.smooth.bind(cb, ubo, Desc.ssbo(this.mgFlags), Desc.ssbo(this.levelB(l)), Desc.ssbo(this.levelX(l)));
		this.smooth.push(cb, this.push(l, l, parity, init ? 1 : 0));
		this.smooth.dispatch(cb, this.boxGroups(l), 1, 1);
		Vk.barrier(cb);
	}

	private void smooth(VkCommandBuffer cb, Desc ubo, int l, int parity) {
		this.smooth(cb, ubo, l, parity, false);
	}

	private void vcycle(VkCommandBuffer cb, Desc ubo) {
		for (int l = 0; l < this.levels - 1; l++) {
			this.smooth(cb, ubo, l, 0, l == 0);
			this.smooth(cb, ubo, l, 1);
			this.restrict.bind(cb, ubo, Desc.ssbo(this.mgFlags), Desc.ssbo(this.levelB(l)), Desc.ssbo(this.levelX(l)),
				Desc.ssbo(this.mgB), Desc.ssbo(this.mgX));
			this.restrict.push(cb, this.push(l, l + 1, 0, 0));
			this.restrict.dispatch(cb, this.boxGroups(l + 1), 1, 1);
			Vk.barrier(cb);
		}
		int coarsest = this.levels - 1;
		int[] push = this.push(coarsest, coarsest, 0, this.levels == 1 ? 1 : 0);
		int[] withSweeps = java.util.Arrays.copyOf(push, 13);
		withSweeps[12] = Math.max(1, WaterSettings.mgCoarseSweeps);
		this.coarse.bind(cb, ubo, Desc.ssbo(this.mgFlags), Desc.ssbo(this.levelB(coarsest)), Desc.ssbo(this.levelX(coarsest)));
		this.coarse.push(cb, withSweeps);
		this.coarse.dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
		for (int l = this.levels - 2; l >= 0; l--) {
			this.prolong.bind(cb, ubo, Desc.ssbo(this.mgFlags), Desc.ssbo(this.levelX(l)), Desc.ssbo(this.mgX));
			this.prolong.push(cb, this.push(l, l + 1, 0, 0));
			this.prolong.dispatch(cb, this.boxGroups(l), 1, 1);
			Vk.barrier(cb);
			this.smooth(cb, ubo, l, 1);
			this.smooth(cb, ubo, l, 0);
		}
	}

	private void reduce(VkCommandBuffer cb, Desc ubo, int slot) {
		this.cgReduce.bind(cb, ubo, Desc.ssbo(this.partials), Desc.ssbo(this.scalars));
		this.cgReduce.push(cb, this.partialSlots, slot);
		this.cgReduce.dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
	}

	private void dot(VkCommandBuffer cb, Desc ubo, VkBuf a, VkBuf b, int slot) {
		this.cgDot.bind(cb, ubo, Desc.ssbo(a), Desc.ssbo(b), Desc.ssbo(this.partials));
		this.cgDot.dispatch(cb, this.partialCount, 1, 1);
		Vk.barrier(cb);
		this.reduce(cb, ubo, slot);
	}

	void solve(VkCommandBuffer cb, Desc ubo, VkBuf cellFlags, VkBuf b, VkBuf x, int iterations, int[] activeBox) {
		this.setBox(activeBox);
		int groups = this.partialCount;
		this.flags0.bind(cb, ubo, Desc.ssbo(cellFlags), Desc.ssbo(this.mgFlags));
		this.flags0.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);
		for (int l = 0; l < this.levels - 1; l++) {
			this.coarsen.bind(cb, ubo, Desc.ssbo(this.mgFlags));
			this.coarsen.push(cb, this.push(l, l + 1, 0, 0));
			this.coarsen.dispatch(cb, this.boxGroups(l + 1), 1, 1);
			Vk.barrier(cb);
		}

		this.cgInit.bind(cb, ubo, Desc.ssbo(this.mgFlags), Desc.ssbo(b), Desc.ssbo(x), Desc.ssbo(this.r));
		this.cgInit.push(cb, this.push(0, 0, 0, 0));
		this.cgInit.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);

		this.vcycle(cb, ubo);
		int rz = RZ0;
		this.dot(cb, ubo, this.r, this.z, rz);
		this.cgDirection.bind(cb, ubo, Desc.ssbo(this.scalars), Desc.ssbo(this.z), Desc.ssbo(this.p));
		this.cgDirection.push(cb, 1, 0, 0);
		this.cgDirection.dispatch(cb, groups, 1, 1);
		Vk.barrier(cb);

		for (int k = 0; k < iterations; k++) {
			this.cgApply.bind(cb, ubo, Desc.ssbo(this.mgFlags), Desc.ssbo(this.p), Desc.ssbo(this.q), Desc.ssbo(this.partials));
			this.cgApply.push(cb, this.push(0, 0, 0, 0));
			this.cgApply.dispatch(cb, groups, 1, 1);
			Vk.barrier(cb);
			this.reduce(cb, ubo, PQ);

			this.cgUpdate.bind(cb, ubo, Desc.ssbo(this.scalars), Desc.ssbo(this.p), Desc.ssbo(this.q), Desc.ssbo(x), Desc.ssbo(this.r));
			this.cgUpdate.push(cb, rz, PQ);
			this.cgUpdate.dispatch(cb, groups, 1, 1);
			Vk.barrier(cb);
			if (k == iterations - 1) {
				break;
			}

			this.vcycle(cb, ubo);
			int next = 1 - rz;
			this.dot(cb, ubo, this.r, this.z, next);
			this.cgDirection.bind(cb, ubo, Desc.ssbo(this.scalars), Desc.ssbo(this.z), Desc.ssbo(this.p));
			this.cgDirection.push(cb, 0, next, rz);
			this.cgDirection.dispatch(cb, groups, 1, 1);
			Vk.barrier(cb);
			rz = next;
		}
	}

	@Override
	public void close() {
		for (AutoCloseable c : new AutoCloseable[]{
			this.mgFlags, this.mgB, this.mgX, this.r, this.z, this.p, this.q, this.partials, this.scalars
		}) {
			try {
				c.close();
			} catch (Exception ignored) {
			}
		}
	}
}
