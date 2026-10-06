package me.skaffy.client.water;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class WaterRegion implements AutoCloseable {
	public static final int PARTICLE_BYTES = 32;
	public static final int EMITTER_BYTES = 64;
	public static final int MAX_EMITTERS_PER_FRAME = 8192;
	static final int C_FLUID = 0;
	static final int C_WW = 2;
	static final int C_MAX_SPEED = 6;
	static final int C_AABB = 8;
	static final long STATS_SLOT = 64;
	static final long ARGS_FLUID_DISPATCH = 0;
	static final long ARGS_WW_DISPATCH = 16;
	static final long ARGS_FLUID_DRAW = 48;
	static final long ARGS_WW_DRAW = 64;
	static final int AABB_BIAS = 1 << 20;
	static final int MAX_REMOVE = 16;
	private static final int INITIAL_PARTICLES = 262_144;
	private static final int INITIAL_WHITEWATER = 262_144;

	public record Emitter(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, float vx, float vy, float vz) {
		int[] lattice(int res) {
			return new int[]{
				Math.max(1, (int) Math.round((this.maxX - this.minX) * res * 2.0)),
				Math.max(1, (int) Math.round((this.maxY - this.minY) * res * 2.0)),
				Math.max(1, (int) Math.round((this.maxZ - this.minZ) * res * 2.0))
			};
		}

		int count(int res) {
			int[] n = this.lattice(res);
			return n[0] * n[1] * n[2];
		}

		int[] cells(int res) {
			return new int[]{
				(int) Math.floor(this.minX * res), (int) Math.floor(this.minY * res), (int) Math.floor(this.minZ * res),
				(int) Math.ceil(this.maxX * res), (int) Math.ceil(this.maxY * res), (int) Math.ceil(this.maxZ * res)
			};
		}
	}

	public record Removal(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
	}

	public record Fill(int x, int y, int z, int dx, int dy, int dz, byte[] fill) {
	}

	private static ComputeProgram[] programs;

	private static ComputeProgram[] programs() {
		if (programs == null) {
			int U = Desc.UBO;
			int S = Desc.SSBO;
			programs = new ComputeProgram[]{
				new ComputeProgram("args.comp", U, S, S),
				new ComputeProgram("emit.comp", U, S, S, S, S),
				new ComputeProgram("clear_box.comp", U, S, S, S, S),
				new ComputeProgram("p2g.comp", U, S, S, S, S, S),
				new ComputeProgram("classify.comp", U, S, S, S),
				new ComputeProgram("faces.comp", U, S, S, S, S, S),
				new ComputeProgram("divergence.comp", U, S, S, S, S, S),
				new ComputeProgram("project.comp", U, S, S, S, S),
				new ComputeProgram("extrapolate.comp", U, S, S, S),
				new ComputeProgram("potential.comp", U, S, S, S, S),
				new ComputeProgram("g2p.comp", U, S, S, S, S, S, S, S, S, S, S),
				new ComputeProgram("ww_update.comp", U, S, S, S, S, S, S, S),
				new ComputeProgram("inflow.comp", U, S, S, S, S),
				new ComputeProgram("probe.comp", U, S, S, S),
				new ComputeProgram("shift.comp", U, S, S),
				new ComputeProgram("remove.comp", U, S, S),
				new ComputeProgram("merge.comp", U, S, S, S, S),
				new ComputeProgram("blockfill.comp", U, S, S, S)
			};
		}
		return programs;
	}

	static void closePrograms() {
		if (programs != null) {
			for (ComputeProgram p : programs) {
				p.close();
			}
			programs = null;
		}
		PressureSolver.closePrograms();
		SurfaceMesher.closePrograms();
	}

	final int id;
	final int res;

	int ox;
	int oy;
	int oz;
	int sx;
	int sy;
	int sz;
	int nx;
	int ny;
	int nz;
	boolean hasWindow;
	private int @Nullable [] pendingShift;
	private int oversizeFrames;

	int numCells;
	int numU;
	int numV;
	int numW;
	int numFaces;
	VkBuf accum;
	VkBuf vel;
	VkBuf velOld;
	VkBuf valid0;
	VkBuf valid1;
	VkBuf cellCount;
	VkBuf cellDens;
	VkBuf cellFlags;
	VkBuf staticSolid;
	VkBuf pressure;
	VkBuf divergence;
	VkBuf potential;
	VkBuf[] wwCount = new VkBuf[2];
	PressureSolver solver;
	SurfaceMesher mesher;
	private boolean freshGrid;
	private int[] solidMirror = new int[0];
	private boolean solidsFullUpload;
	private final List<int[]> solidPatches = new ArrayList<>();

	int fluidCapacity = INITIAL_PARTICLES;
	int wwCapacity = INITIAL_WHITEWATER;
	VkBuf[] particles = new VkBuf[2];
	VkBuf[] whitewater = new VkBuf[2];
	int cur;
	int wwCur;
	final VkBuf counters;
	final VkBuf args;
	private final VkBuf emitterRing;
	private final VkBuf statsReadback;
	private final int[][] slotOrigin = new int[Vk.FRAME_RING][];
	private final int[][] slotWindow = new int[Vk.FRAME_RING][];
	static final int KILL_MARGIN = 64;
	private final boolean[] slotHasAabb = new boolean[Vk.FRAME_RING];
	private VkBuf fillBuffer;
	private final VkBuf[] fillReadback = new VkBuf[Vk.FRAME_RING];
	private final int[][] fillMeta = new int[Vk.FRAME_RING][];
	private boolean initialized;
	private boolean resetRequested = true;
	private int seed = 1;

	final List<Emitter> pending = new ArrayList<>();
	final List<Removal> removals = new ArrayList<>();
	final LongOpenHashSet owned = new LongOpenHashSet();
	int @Nullable [] box;
	int @Nullable [] boxWorld;
	private int @Nullable [] particleBoxWorld;
	private int @Nullable [] recentEmitWorld;
	private int recentEmitExpiry;
	int idleFrames;
	boolean fillRequested;
	int lastFluidCount;
	int lastWhitewaterCount;
	float lastMaxSpeed;
	private int emittedSinceReadback;

	WaterRegion(int id, int res) {
		this.id = id;
		this.res = res;
		for (int i = 0; i < 2; i++) {
			this.particles[i] = VkBuf.device((long) this.fluidCapacity * PARTICLE_BYTES, 0);
			this.whitewater[i] = VkBuf.device((long) this.wwCapacity * PARTICLE_BYTES, 0);
		}
		this.counters = VkBuf.device(64, 0);
		this.args = VkBuf.device(128, VkBuf.INDIRECT);
		this.emitterRing = VkBuf.upload((long) Vk.FRAME_RING * MAX_EMITTERS_PER_FRAME * EMITTER_BYTES, VkBuf.STORAGE);
		this.statsReadback = VkBuf.readback(Vk.FRAME_RING * STATS_SLOT);
		for (int i = 0; i < Vk.FRAME_RING * STATS_SLOT; i += 4) {
			this.statsReadback.mapped().putInt(i, 0);
		}
	}

	int originCellX() {
		return this.ox * this.res;
	}

	int originCellY() {
		return this.oy * this.res;
	}

	int originCellZ() {
		return this.oz * this.res;
	}

	private int @Nullable [] claim;

	private void claimBox(int x0, int y0, int z0, int x1, int y1, int z1) {
		this.claim = union(this.claim, new int[]{x0, y0, z0, x1, y1, z1});
	}

	void claimBlock(BlockPos p) {
		this.claimBox(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1);
	}

	boolean claims(BlockPos pos, int margin) {
		int[] c = this.claim;
		return c != null
			&& pos.getX() >= c[0] - margin && pos.getX() < c[3] + margin
			&& pos.getY() >= c[1] - margin && pos.getY() < c[4] + margin
			&& pos.getZ() >= c[2] - margin && pos.getZ() < c[5] + margin;
	}

	boolean overlapsChunk(int chunkX, int chunkZ) {
		int x0 = chunkX << 4;
		int z0 = chunkZ << 4;
		return this.hasWindow && x0 < this.ox + this.sx && x0 + 16 > this.ox && z0 < this.oz + this.sz && z0 + 16 > this.oz;
	}

	boolean windowContains(BlockPos pos, int margin) {
		return this.hasWindow
			&& pos.getX() >= this.ox - margin && pos.getX() < this.ox + this.sx + margin
			&& pos.getY() >= this.oy - margin && pos.getY() < this.oy + this.sy + margin
			&& pos.getZ() >= this.oz - margin && pos.getZ() < this.oz + this.sz + margin;
	}

	void addEmitter(Emitter e) {
		this.pending.add(e);
		this.claimBox((int) Math.floor(e.minX()), (int) Math.floor(e.minY()), (int) Math.floor(e.minZ()),
			(int) Math.ceil(e.maxX()), (int) Math.ceil(e.maxY()), (int) Math.ceil(e.maxZ()));
	}


	@Nullable Fill readBack() {
		int slot = Vk.ringSlot();
		int off = (int) (slot * STATS_SLOT);
		this.statsReadback.invalidate(off, STATS_SLOT);
		ByteBuffer b = this.statsReadback.mapped();
		this.lastFluidCount = Math.max(b.getInt(off), b.getInt(off + 4));
		this.lastWhitewaterCount = Math.max(b.getInt(off + 8), b.getInt(off + 12));
		this.lastMaxSpeed = b.getFloat(off + 24);
		if (this.slotHasAabb[slot] && this.slotOrigin[slot] != null) {
			int[] o = this.slotOrigin[slot];
			int minX = b.getInt(off + 32);
			int minY = b.getInt(off + 36);
			int minZ = b.getInt(off + 40);
			int maxX = b.getInt(off + 44);
			int maxY = b.getInt(off + 48);
			int maxZ = b.getInt(off + 52);
			if (Integer.compareUnsigned(minX, maxX) > 0) {
				this.particleBoxWorld = null;
			} else {
				int[] w = this.slotWindow[slot];
				int[] raw = {
					minX - AABB_BIAS, minY - AABB_BIAS, minZ - AABB_BIAS,
					maxX - AABB_BIAS + 1, maxY - AABB_BIAS + 1, maxZ - AABB_BIAS + 1
				};
				int[] box = new int[6];
				for (int a = 0; a < 3; a++) {
					box[a] = Math.clamp(raw[a], -KILL_MARGIN, w[a] + KILL_MARGIN) + o[a];
					box[3 + a] = Math.clamp(raw[3 + a], -KILL_MARGIN, w[a] + KILL_MARGIN) + o[a];
				}
				this.particleBoxWorld = box[3] > box[0] && box[4] > box[1] && box[5] > box[2] ? box : null;
			}
			this.emittedSinceReadback = 0;
		}
		this.slotHasAabb[slot] = false;

		Fill fill = null;
		int[] meta = this.fillMeta[slot];
		if (meta != null) {
			int n = meta[3] * meta[4] * meta[5];
			VkBuf rb = this.fillReadback[slot];
			rb.invalidate(0, n * 4L);
			byte[] data = new byte[n];
			ByteBuffer fb = rb.mapped();
			for (int i = 0; i < n; i++) {
				data[i] = (byte) fb.getInt(i * 4);
			}
			fill = new Fill(meta[0], meta[1], meta[2], meta[3], meta[4], meta[5], data);
			this.fillMeta[slot] = null;
		}
		return fill;
	}

	private static int[] union(int @Nullable [] a, int[] b) {
		if (a == null) {
			return b.clone();
		}
		return new int[]{
			Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
			Math.max(a[3], b[3]), Math.max(a[4], b[4]), Math.max(a[5], b[5])
		};
	}

	int @Nullable [] computeBoxWorld(List<BlockPos> sources, int frame) {
		int[] b = this.particleBoxWorld != null ? this.particleBoxWorld.clone() : null;
		for (Emitter e : this.pending) {
			b = union(b, e.cells(this.res));
		}
		if (this.recentEmitWorld != null) {
			if (frame < this.recentEmitExpiry) {
				b = union(b, this.recentEmitWorld);
			} else {
				this.recentEmitWorld = null;
			}
		}
		for (BlockPos p : sources) {
			b = union(b, new int[]{
				p.getX() * this.res, p.getY() * this.res, p.getZ() * this.res,
				(p.getX() + 1) * this.res, (p.getY() + 1) * this.res, (p.getZ() + 1) * this.res
			});
		}
		if (b == null) {
			return null;
		}
		int margin = WaterSettings.boxMargin + (int) Math.ceil(this.lastMaxSpeed * 0.12F);
		for (int a = 0; a < 3; a++) {
			b[a] -= margin;
			b[3 + a] += margin;
		}
		return b;
	}

	static int[] unionBox(int[] a, int[] b) {
		return union(a, b);
	}


	void planWindow(ClientLevel level, SolidCache solids, int[] boxWorld) {
		this.boxWorld = boxWorld;
		boolean fits = this.hasWindow
			&& boxWorld[0] >= this.originCellX() && boxWorld[1] >= this.originCellY() && boxWorld[2] >= this.originCellZ()
			&& boxWorld[3] <= this.originCellX() + this.nx && boxWorld[4] <= this.originCellY() + this.ny && boxWorld[5] <= this.originCellZ() + this.nz;
		long boxVolume = (long) (boxWorld[3] - boxWorld[0]) * (boxWorld[4] - boxWorld[1]) * (boxWorld[5] - boxWorld[2]);
		if (fits && (long) this.nx * this.ny * this.nz > 6L * boxVolume) {
			this.oversizeFrames++;
		} else {
			this.oversizeFrames = 0;
		}
		if (!fits || this.oversizeFrames > 120) {
			int[] lo = new int[3];
			int[] size = new int[3];
			for (int a = 0; a < 3; a++) {
				int bMin = Math.floorDiv(boxWorld[a], this.res);
				int bMax = Math.ceilDiv(boxWorld[3 + a], this.res);
				int slack = Math.max(2, (bMax - bMin) / 4);
				lo[a] = bMin - slack;
				size[a] = bMax - bMin + 2 * slack;
				if (size[a] * this.res % 2 != 0) {
					size[a]++;
				}
			}
			if (this.hasWindow) {
				this.pendingShift = new int[]{(this.ox - lo[0]) * this.res, (this.oy - lo[1]) * this.res, (this.oz - lo[2]) * this.res};
			}
			this.ox = lo[0];
			this.oy = lo[1];
			this.oz = lo[2];
			this.sx = size[0];
			this.sy = size[1];
			this.sz = size[2];
			this.nx = this.sx * this.res;
			this.ny = this.sy * this.res;
			this.nz = this.sz * this.res;
			this.hasWindow = true;
			this.oversizeFrames = 0;
			this.allocateGrid();
			this.rebuildSolids(level, solids);
		}
		this.claim = new int[]{this.ox, this.oy, this.oz, this.ox + this.sx, this.oy + this.sy, this.oz + this.sz};
		for (Emitter e : this.pending) {
			this.claimBox((int) Math.floor(e.minX()), (int) Math.floor(e.minY()), (int) Math.floor(e.minZ()),
				(int) Math.ceil(e.maxX()), (int) Math.ceil(e.maxY()), (int) Math.ceil(e.maxZ()));
		}
		int[] b = new int[6];
		int[] o = {this.originCellX(), this.originCellY(), this.originCellZ()};
		int[] n = {this.nx, this.ny, this.nz};
		for (int a = 0; a < 3; a++) {
			b[a] = Math.max(0, boxWorld[a] - o[a]);
			b[3 + a] = Math.min(n[a], boxWorld[3 + a] - o[a]);
		}
		this.box = b;
	}

	private void allocateGrid() {
		this.releaseGrid();
		this.numCells = this.nx * this.ny * this.nz;
		this.numU = (this.nx + 1) * this.ny * this.nz;
		this.numV = this.nx * (this.ny + 1) * this.nz;
		this.numW = this.nx * this.ny * (this.nz + 1);
		this.numFaces = this.numU + this.numV + this.numW;
		this.accum = VkBuf.device(this.numFaces * 8L, 0);
		this.vel = VkBuf.device(this.numFaces * 4L, 0);
		this.velOld = VkBuf.device(this.numFaces * 4L, 0);
		this.valid0 = VkBuf.device(this.numFaces * 4L, 0);
		this.valid1 = VkBuf.device(this.numFaces * 4L, 0);
		this.cellCount = VkBuf.device(this.numCells * 4L, 0);
		this.cellDens = VkBuf.device(this.numCells * 4L, 0);
		this.cellFlags = VkBuf.device(this.numCells * 4L, 0);
		this.staticSolid = VkBuf.device(this.numCells * 4L, 0);
		this.pressure = VkBuf.device(this.numCells * 4L, 0);
		this.divergence = VkBuf.device(this.numCells * 4L, 0);
		this.potential = VkBuf.device(this.numCells * 8L, 0);
		this.wwCount[0] = VkBuf.device(this.numCells * 4L, 0);
		this.wwCount[1] = VkBuf.device(this.numCells * 4L, 0);
		this.solver = new PressureSolver(this.nx, this.ny, this.nz);
		this.mesher = new SurfaceMesher(this.nx, this.ny, this.nz, Math.max(1, WaterSettings.surfaceSubdivision));
		this.freshGrid = true;
	}

	private void releaseGrid() {
		for (AutoCloseable c : new AutoCloseable[]{
			this.accum, this.vel, this.velOld, this.valid0, this.valid1, this.cellCount, this.cellDens, this.cellFlags, this.staticSolid,
			this.pressure, this.divergence, this.potential, this.wwCount[0], this.wwCount[1], this.solver, this.mesher
		}) {
			if (c != null) {
				Vk.destroyLater(c);
			}
		}
	}

	private void rebuildSolids(ClientLevel level, SolidCache solids) {
		this.solidMirror = new int[this.numCells];
		for (int bz = 0; bz < this.sz; bz++) {
			for (int by = 0; by < this.sy; by++) {
				for (int bx = 0; bx < this.sx; bx++) {
					solids.writeBlock(level, this.ox + bx, this.oy + by, this.oz + bz, this.solidMirror,
						bx * this.res, by * this.res, bz * this.res, this.nx, this.ny);
				}
			}
		}
		this.solidsFullUpload = true;
		this.solidPatches.clear();
	}

	void patchSolid(ClientLevel level, SolidCache solids, BlockPos pos) {
		if (!this.windowContains(pos, 0) || this.solidsFullUpload) {
			return;
		}
		int bx = pos.getX() - this.ox;
		int by = pos.getY() - this.oy;
		int bz = pos.getZ() - this.oz;
		solids.writeBlock(level, pos.getX(), pos.getY(), pos.getZ(), this.solidMirror, bx * this.res, by * this.res, bz * this.res, this.nx, this.ny);
		if (this.solidPatches.size() > 256) {
			this.solidsFullUpload = true;
			this.solidPatches.clear();
		} else {
			this.solidPatches.add(new int[]{bx, by, bz});
		}
	}

	void markSolidsDirty(ClientLevel level, SolidCache solids) {
		if (this.hasWindow) {
			this.rebuildSolids(level, solids);
		}
	}

	private void uploadSolids(VkCommandBuffer cb) {
		if (this.solidsFullUpload) {
			VkBuf staging = VkBuf.upload(this.numCells * 4L, 0);
			staging.mapped().asIntBuffer().put(0, this.solidMirror);
			staging.flush(0, this.numCells * 4L);
			staging.copyTo(cb, this.staticSolid, 0, 0, this.numCells * 4L);
			Vk.destroyLater(staging);
		} else if (!this.solidPatches.isEmpty()) {
			int r = this.res;
			int rows = this.solidPatches.size() * r * r;
			VkBuf staging = VkBuf.upload((long) rows * r * 4L, 0);
			ByteBuffer mapped = staging.mapped();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkBufferCopy.Buffer regions = VkBufferCopy.malloc(rows, stack);
				int row = 0;
				for (int[] p : this.solidPatches) {
					for (int k = 0; k < r; k++) {
						for (int j = 0; j < r; j++) {
							int cell = p[0] * r + this.nx * ((p[1] * r + j) + this.ny * (p[2] * r + k));
							long src = (long) row * r * 4L;
							for (int c = 0; c < r; c++) {
								mapped.putInt((int) src + c * 4, this.solidMirror[cell + c]);
							}
							regions.get(row).srcOffset(src).dstOffset(cell * 4L).size(r * 4L);
							row++;
						}
					}
				}
				staging.flush(0, (long) rows * r * 4L);
				VK10.vkCmdCopyBuffer(cb, staging.handle, this.staticSolid.handle, regions);
			}
			Vk.destroyLater(staging);
		}
		this.solidsFullUpload = false;
		this.solidPatches.clear();
	}


	private static VkBuf[] grow(VkCommandBuffer cb, VkBuf[] old, int current, long oldBytes, long newBytes) {
		VkBuf[] fresh = {VkBuf.device(newBytes, 0), VkBuf.device(newBytes, 0)};
		old[current].copyTo(cb, fresh[current], 0, 0, oldBytes);
		Vk.destroyLater(old[0]);
		Vk.destroyLater(old[1]);
		return fresh;
	}

	private void ensureCapacity(VkCommandBuffer cb, int incoming, int numSources, int incomingWhitewater) {
		long estimate = (long) this.lastFluidCount + this.emittedSinceReadback + incoming + (long) numSources * this.res * this.res * this.res * 64;
		if (estimate > this.fluidCapacity * 0.8) {
			int next = (int) Math.min(Integer.MAX_VALUE / PARTICLE_BYTES, Math.max(this.fluidCapacity * 2L, estimate * 3 / 2));
			this.particles = grow(cb, this.particles, this.cur, (long) this.fluidCapacity * PARTICLE_BYTES, (long) next * PARTICLE_BYTES);
			this.fluidCapacity = next;
		}
		if (this.lastWhitewaterCount + incomingWhitewater > this.wwCapacity * 0.8) {
			int next = (int) Math.min(Integer.MAX_VALUE / PARTICLE_BYTES, this.wwCapacity * 2L);
			this.whitewater = grow(cb, this.whitewater, this.wwCur, (long) this.wwCapacity * PARTICLE_BYTES, (long) next * PARTICLE_BYTES);
			this.wwCapacity = next;
		}
	}


	private int writeEmitters(int slot, int[] totalOut) {
		ByteBuffer buf = this.emitterRing.mapped();
		long base = (long) slot * MAX_EMITTERS_PER_FRAME * EMITTER_BYTES;
		int total = 0;
		int n = 0;
		float r = this.res;
		for (Emitter e : this.pending) {
			int count = e.count(this.res);
			if (n >= MAX_EMITTERS_PER_FRAME || (long) total + count > this.fluidCapacity - this.lastFluidCount - this.emittedSinceReadback) {
				break;
			}
			int o = (int) (base + (long) n * EMITTER_BYTES);
			int[] lattice = e.lattice(this.res);
			buf.putFloat(o, (float) ((e.minX() - this.ox) * r)).putFloat(o + 4, (float) ((e.minY() - this.oy) * r))
				.putFloat(o + 8, (float) ((e.minZ() - this.oz) * r)).putFloat(o + 12, 0);
			buf.putFloat(o + 16, (float) ((e.maxX() - this.ox) * r)).putFloat(o + 20, (float) ((e.maxY() - this.oy) * r))
				.putFloat(o + 24, (float) ((e.maxZ() - this.oz) * r)).putFloat(o + 28, 0);
			buf.putFloat(o + 32, e.vx() * r).putFloat(o + 36, e.vy() * r).putFloat(o + 40, e.vz() * r).putFloat(o + 44, 0);
			buf.putInt(o + 48, total).putInt(o + 52, lattice[0]).putInt(o + 56, lattice[1]).putInt(o + 60, lattice[2]);
			total += count;
			n++;
		}
		this.emitterRing.flush(base, (long) Math.max(n, 1) * EMITTER_BYTES);
		totalOut[0] = total;
		return n;
	}

	List<float[]> takeRemovals() {
		List<float[]> out = new ArrayList<>();
		float r = this.res;
		while (!this.removals.isEmpty() && out.size() < MAX_REMOVE) {
			Removal rm = this.removals.removeFirst();
			out.add(new float[]{
				(float) ((rm.minX() - this.ox) * r), (float) ((rm.minY() - this.oy) * r), (float) ((rm.minZ() - this.oz) * r),
				(float) ((rm.maxX() - this.ox) * r), (float) ((rm.maxY() - this.oy) * r), (float) ((rm.maxZ() - this.oz) * r)
			});
		}
		return out;
	}

	private void absorb(VkCommandBuffer cb, Desc ubo, WaterRegion other) {
		if (!other.initialized) {
			this.pending.addAll(other.pending);
			return;
		}
		ComputeProgram merge = programs()[16];
		float dx = other.originCellX() - this.originCellX();
		float dy = other.originCellY() - this.originCellY();
		float dz = other.originCellZ() - this.originCellZ();
		merge.bind(cb, ubo, Desc.ssbo(other.particles[other.cur]), Desc.ssbo(other.counters), Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters));
		merge.push(cb, C_FLUID + other.cur, C_FLUID + this.cur, this.fluidCapacity, 0, Program.f(dx), Program.f(dy), Program.f(dz), 0);
		merge.dispatchIndirect(cb, other.args, ARGS_FLUID_DISPATCH);
		merge.bind(cb, ubo, Desc.ssbo(other.whitewater[other.wwCur]), Desc.ssbo(other.counters), Desc.ssbo(this.whitewater[this.wwCur]),
			Desc.ssbo(this.counters));
		merge.push(cb, C_WW + other.wwCur, C_WW + this.wwCur, this.wwCapacity, 0, Program.f(dx), Program.f(dy), Program.f(dz), 0);
		merge.dispatchIndirect(cb, other.args, ARGS_WW_DISPATCH);
		Vk.barrier(cb);
		this.pending.addAll(other.pending);
		this.removals.addAll(other.removals);
		this.owned.addAll(other.owned);
		this.emittedSinceReadback += other.lastFluidCount + other.emittedSinceReadback;
		if (other.boxWorld != null) {
			this.recentEmitWorld = union(this.recentEmitWorld, other.boxWorld);
		}
	}

	void queueAbsorb(WaterRegion other) {
		this.absorbQueue.add(other);
	}

	private final List<WaterRegion> absorbQueue = new ArrayList<>();

	void record(VkCommandBuffer cb, Desc ubo, int steps, int numSources, int frame, VkBuf frameView, int numRemovals) {
		int slot = Vk.ringSlot();
		int[] box = this.box;
		ComputeProgram[] p = programs();

		if (this.resetRequested) {
			this.resetRequested = false;
			this.counters.fill(cb, 0);
			this.args.fill(cb, 0);
			this.cur = 0;
			this.wwCur = 0;
		}
		if (this.freshGrid) {
			this.freshGrid = false;
			this.pendingShiftHappened = true;
			this.pressure.fill(cb, 0);
			this.wwCount[0].fill(cb, 0);
			this.wwCount[1].fill(cb, 0);
			this.cellCount.fill(cb, 0);
			this.cellDens.fill(cb, 0);
			this.cellFlags.fill(cb, 0);
		}
		Vk.barrier(cb);
		if (this.pendingShift != null) {
			if (this.initialized) {
				int[] d = this.pendingShift;
				p[14].bind(cb, ubo, Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters));
				p[14].push(cb, C_FLUID + this.cur, this.fluidCapacity, 0, 0, Program.f(d[0]), Program.f(d[1]), Program.f(d[2]), 0);
				p[14].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
				p[14].bind(cb, ubo, Desc.ssbo(this.whitewater[this.wwCur]), Desc.ssbo(this.counters));
				p[14].push(cb, C_WW + this.wwCur, this.wwCapacity, 0, 0, Program.f(d[0]), Program.f(d[1]), Program.f(d[2]), 0);
				p[14].dispatchIndirect(cb, this.args, ARGS_WW_DISPATCH);
				Vk.barrier(cb);
			}
			this.pendingShift = null;
			this.pendingShiftHappened = true;
		}
		this.uploadSolids(cb);
		int incoming = 0;
		for (Emitter e : this.pending) {
			incoming += e.count(this.res);
		}
		int incomingWhitewater = 0;
		for (WaterRegion other : this.absorbQueue) {
			incoming += other.lastFluidCount + other.emittedSinceReadback;
			incomingWhitewater += other.lastWhitewaterCount;
		}
		this.ensureCapacity(cb, incoming, numSources, incomingWhitewater);
		Vk.barrier(cb);
		for (WaterRegion other : this.absorbQueue) {
			this.absorb(cb, ubo, other);
		}
		this.absorbQueue.clear();
		if (!WaterSettings.whitewater) {
			this.counters.fill(cb, C_WW * 4L, 8, 0);
		}
		if (steps > 0) {
			this.counters.fill(cb, C_AABB * 4L, 12, 0xFFFFFFFF);
			this.counters.fill(cb, (C_AABB + 3) * 4L, 12, 0);
		}
		Vk.barrier(cb);

		if (!this.pending.isEmpty()) {
			int[] total = new int[1];
			int consumed = this.writeEmitters(slot, total);
			if (total[0] > 0) {
				long base = (long) slot * MAX_EMITTERS_PER_FRAME * EMITTER_BYTES;
				p[1].bind(cb, ubo, Desc.ssbo(this.emitterRing, base, (long) MAX_EMITTERS_PER_FRAME * EMITTER_BYTES),
					Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters), Desc.ssbo(this.staticSolid));
				p[1].push(cb, C_FLUID + this.cur, consumed, total[0], this.seed++);
				p[1].dispatch(cb, ComputeProgram.groups(total[0], 256), 1, 1);
				Vk.barrier(cb);
			}
			List<Emitter> done = this.pending.subList(0, consumed);
			for (Emitter e : done) {
				this.recentEmitWorld = union(this.recentEmitWorld, e.cells(this.res));
			}
			this.recentEmitExpiry = frame + Vk.FRAME_RING + 4;
			this.emittedSinceReadback += total[0];
			done.clear();
		}
		if (numRemovals > 0 && this.initialized) {
			p[15].bind(cb, ubo, Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters));
			p[15].push(cb, C_FLUID + this.cur);
			p[15].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
			Vk.barrier(cb);
		}

		for (int s = 0; s < steps; s++) {
			this.step(cb, ubo, numSources, box, s == 0);
		}

		p[0].bind(cb, ubo, Desc.ssbo(this.counters), Desc.ssbo(this.args));
		p[0].push(cb, C_FLUID + this.cur, C_WW + this.wwCur);
		p[0].dispatch(cb, 1, 1, 1);
		p[13].bind(cb, ubo, Desc.ssbo(this.cellDens), Desc.ssbo(this.cellFlags), Desc.ssbo(frameView));
		p[13].dispatch(cb, 1, 1, 1);
		if (this.fillRequested) {
			this.recordFill(cb, ubo, slot);
		}
		Vk.barrier(cb);
		this.counters.copyTo(cb, this.statsReadback, 0, slot * STATS_SLOT, 64);
		this.slotHasAabb[slot] = steps > 0;
		this.slotOrigin[slot] = new int[]{this.originCellX(), this.originCellY(), this.originCellZ()};
		this.slotWindow[slot] = new int[]{this.nx, this.ny, this.nz};
		this.initialized = true;
	}

	private void recordFill(VkCommandBuffer cb, Desc ubo, int slot) {
		this.fillRequested = false;
		int[] b = this.box;
		int r = this.res;
		int x0 = b[0] / r;
		int y0 = b[1] / r;
		int z0 = b[2] / r;
		int dx = Math.ceilDiv(b[3], r) - x0;
		int dy = Math.ceilDiv(b[4], r) - y0;
		int dz = Math.ceilDiv(b[5], r) - z0;
		long n = (long) dx * dy * dz;
		if (n <= 0) {
			return;
		}
		if (this.fillBuffer == null || this.fillBuffer.size < n * 4) {
			if (this.fillBuffer != null) {
				Vk.destroyLater(this.fillBuffer);
			}
			this.fillBuffer = VkBuf.device(n * 4 * 2, 0);
		}
		if (this.fillReadback[slot] == null || this.fillReadback[slot].size < n * 4) {
			if (this.fillReadback[slot] != null) {
				Vk.destroyLater(this.fillReadback[slot]);
			}
			this.fillReadback[slot] = VkBuf.readback(n * 4 * 2);
		}
		Vk.barrier(cb);
		ComputeProgram fill = programs()[17];
		fill.bind(cb, ubo, Desc.ssbo(this.cellCount), Desc.ssbo(this.cellFlags), Desc.ssbo(this.fillBuffer));
		fill.push(cb, x0, y0, z0, 0, dx, dy, dz, 0);
		fill.dispatch(cb, ComputeProgram.groups(n, 64), 1, 1);
		Vk.barrier(cb);
		this.fillBuffer.copyTo(cb, this.fillReadback[slot], 0, 0, n * 4);
		this.fillMeta[slot] = new int[]{this.ox + x0, this.oy + y0, this.oz + z0, dx, dy, dz};
	}

	private void step(VkCommandBuffer cb, Desc ubo, int numSources, int[] box, boolean first) {
		ComputeProgram[] p = programs();
		int src = this.cur;
		int dst = 1 - this.cur;
		int wwSrc = this.wwCur;
		int wwDst = 1 - this.wwCur;
		boolean ww = WaterSettings.whitewater;
		int cellGroups = ComputeProgram.groups(boxCells(box), 256);
		int faceGroups = ComputeProgram.groups(boxFaces(box), 256);

		if (numSources > 0 && !(first && this.pendingShiftHappened)) {
			p[12].bind(cb, ubo, Desc.ssbo(this.particles[src]), Desc.ssbo(this.counters), Desc.ssbo(this.cellCount), Desc.ssbo(this.staticSolid));
			p[12].push(cb, C_FLUID + src, this.seed++);
			p[12].dispatch(cb, ComputeProgram.groups((long) numSources * this.res * this.res * this.res, 64), 1, 1);
			Vk.barrier(cb);
		}

		this.counters.fill(cb, (C_FLUID + dst) * 4L, 4, 0);
		this.counters.fill(cb, (C_WW + wwDst) * 4L, 4, 0);
		this.counters.fill(cb, C_MAX_SPEED * 4L, 4, 0);
		p[2].bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.cellCount), Desc.ssbo(this.cellDens), Desc.ssbo(this.wwCount[wwDst]));
		p[2].dispatch(cb, ComputeProgram.groups((long) boxCells(box) + boxFaces(box), 256), 1, 1);
		Vk.barrier(cb);

		p[0].bind(cb, ubo, Desc.ssbo(this.counters), Desc.ssbo(this.args));
		p[0].push(cb, C_FLUID + src, C_WW + wwSrc);
		p[0].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);

		p[3].bind(cb, ubo, Desc.ssbo(this.particles[src]), Desc.ssbo(this.accum), Desc.ssbo(this.cellCount), Desc.ssbo(this.cellDens),
			Desc.ssbo(this.counters));
		p[3].push(cb, C_FLUID + src);
		p[3].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);

		p[4].bind(cb, ubo, Desc.ssbo(this.staticSolid), Desc.ssbo(this.cellCount), Desc.ssbo(this.cellFlags));
		p[4].dispatch(cb, cellGroups, 1, 1);
		Vk.barrier(cb);

		p[5].bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.vel), Desc.ssbo(this.velOld), Desc.ssbo(this.valid0), Desc.ssbo(this.cellFlags));
		p[5].dispatch(cb, faceGroups, 1, 1);
		Vk.barrier(cb);

		p[6].bind(cb, ubo, Desc.ssbo(this.vel), Desc.ssbo(this.cellFlags), Desc.ssbo(this.cellDens), Desc.ssbo(this.divergence),
			Desc.ssbo(this.pressure));
		p[6].dispatch(cb, cellGroups, 1, 1);
		Vk.barrier(cb);

		this.solver.solve(cb, ubo, this.cellFlags, this.divergence, this.pressure, Math.max(1, WaterSettings.cgIterations), box);

		p[7].bind(cb, ubo, Desc.ssbo(this.vel), Desc.ssbo(this.cellFlags), Desc.ssbo(this.pressure), Desc.ssbo(this.valid0));
		p[7].dispatch(cb, faceGroups, 1, 1);
		Vk.barrier(cb);

		VkBuf[] valid = {this.valid0, this.valid1};
		for (int k = 0; k < WaterSettings.extrapolationLayers; k++) {
			p[8].bind(cb, ubo, Desc.ssbo(this.vel), Desc.ssbo(valid[k % 2]), Desc.ssbo(valid[(k + 1) % 2]));
			p[8].dispatch(cb, faceGroups, 1, 1);
			Vk.barrier(cb);
		}

		if (ww) {
			p[9].bind(cb, ubo, Desc.ssbo(this.vel), Desc.ssbo(this.cellFlags), Desc.ssbo(this.cellDens), Desc.ssbo(this.potential));
			p[9].dispatch(cb, cellGroups, 1, 1);
			Vk.barrier(cb);
		}

		p[10].bind(cb, ubo,
			Desc.ssbo(this.particles[src]), Desc.ssbo(this.particles[dst]), Desc.ssbo(this.counters),
			Desc.ssbo(this.vel), Desc.ssbo(this.velOld), Desc.ssbo(this.cellFlags), Desc.ssbo(this.staticSolid),
			Desc.ssbo(this.potential), Desc.ssbo(this.whitewater[wwDst]), Desc.ssbo(this.wwCount[wwSrc]));
		p[10].push(cb, C_FLUID + src, C_FLUID + dst, 0, this.seed++, C_WW + wwDst, ww ? 1 : 0);
		p[10].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);

		if (ww) {
			p[11].bind(cb, ubo,
				Desc.ssbo(this.whitewater[wwSrc]), Desc.ssbo(this.whitewater[wwDst]), Desc.ssbo(this.counters),
				Desc.ssbo(this.vel), Desc.ssbo(this.cellCount), Desc.ssbo(this.staticSolid), Desc.ssbo(this.wwCount[wwDst]));
			p[11].push(cb, C_WW + wwSrc, C_WW + wwDst, 0, this.seed++);
			p[11].dispatchIndirect(cb, this.args, ARGS_WW_DISPATCH);
			Vk.barrier(cb);
		}

		this.cur = dst;
		this.wwCur = wwDst;
		this.pendingShiftHappened = false;
	}

	private boolean pendingShiftHappened;

	static int boxCells(int[] b) {
		return Math.max(0, b[3] - b[0]) * Math.max(0, b[4] - b[1]) * Math.max(0, b[5] - b[2]);
	}

	static int boxFaces(int[] b) {
		int dx = b[3] - b[0];
		int dy = b[4] - b[1];
		int dz = b[5] - b[2];
		if (dx <= 0 || dy <= 0 || dz <= 0) {
			return 0;
		}
		return (dx + 1) * dy * dz + dx * (dy + 1) * dz + dx * dy * (dz + 1);
	}

	VkBuf currentParticles() {
		return this.particles[this.cur];
	}

	VkBuf currentWhitewater() {
		return this.whitewater[this.wwCur];
	}

	boolean initialized() {
		return this.initialized;
	}

	@Override
	public void close() {
		this.releaseGrid();
		for (AutoCloseable c : new AutoCloseable[]{
			this.particles[0], this.particles[1], this.whitewater[0], this.whitewater[1], this.counters, this.args, this.emitterRing,
			this.statsReadback, this.fillBuffer, this.fillReadback[0], this.fillReadback[1], this.fillReadback[2]
		}) {
			if (c != null) {
				Vk.destroyLater(c);
			}
		}
	}
}
