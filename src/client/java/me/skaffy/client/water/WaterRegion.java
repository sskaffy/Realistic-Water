package me.skaffy.client.water;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.skaffy.ModContent;
import me.skaffy.block.LooseLayerBlock;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkOutOfMemory;
import me.skaffy.ocean.OceanWaves;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

public final class WaterRegion implements AutoCloseable {
	public static final int PARTICLE_BYTES = 32;
	public static final int EMITTER_BYTES = 64;
	static final int MPM_STATE_BYTES = 112;
	static final int SURFACE_PARTICLE_BYTES = 48;
	public static final int MAX_EMITTERS_PER_FRAME = 8192;
	static final int C_FLUID = 0;
	static final int C_WW = 2;
	static final int C_MAX_SPEED = 6;
	static final int C_AABB = 8;
	static final int C_AWAKE = 14;
	static final int C_IGNITED = 5;
	static final int C_IGNITE_POS = 7;
	static final int C_HEATED = 5;
	static final int C_MAX_TEMP = 15;
	static final int C_DIR_SPEED = 16;
	static final long STATS_SLOT = 96;
	static final long ARGS_FLUID_DISPATCH = 0;
	static final long ARGS_WW_DISPATCH = 16;
	static final long ARGS_FLUID_DRAW = 48;
	static final long ARGS_WW_DRAW = 64;
	static final long ARGS_GRAIN_DRAW = 80;
	static final long ARGS_DROPLET_DRAW = 96;
	static final int AABB_BIAS = 1 << 20;
	static final int MAX_REMOVE = 16;
	private static final int INITIAL_PARTICLES = 262_144;
	private static final int INITIAL_WHITEWATER = 262_144;

	public record Emitter(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, float vx, float vy, float vz, float heat) {
		public Emitter(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, float vx, float vy, float vz) {
			this(minX, minY, minZ, maxX, maxY, maxZ, vx, vy, vz, 0.0F);
		}

		int[] gasLattice() {
			double step = Math.max(this.heat > 0.0F ? this.heat - Math.floor(this.heat) : WaterSettings.steamParticleSize, 0.01);
			return new int[]{
				Math.max(1, (int) Math.round((this.maxX - this.minX) / step)),
				Math.max(1, (int) Math.round((this.maxY - this.minY) / step)),
				Math.max(1, (int) Math.round((this.maxZ - this.minZ) / step))
			};
		}

		int[] leafLattice() {
			double side = Math.cbrt(Math.max(1, WaterSettings.leavesPerBlock));
			int nx = Math.max(1, (int) Math.round((this.maxX - this.minX) * side));
			int nz = Math.max(1, (int) Math.round((this.maxZ - this.minZ) * side));
			double volume = (this.maxX - this.minX) * (this.maxY - this.minY) * (this.maxZ - this.minZ);
			int ny = Math.max(1, (int) Math.round(volume * WaterSettings.leavesPerBlock / (nx * nz)));
			return new int[]{nx, ny, nz};
		}

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

	record PortalSink(int[] box, long portal) {
	}

	public record Fill(int x, int y, int z, int dx, int dy, int dz, byte[] fill, byte[] speed, byte[] temperature, byte[] ice) {
		Fill(int x, int y, int z, int dx, int dy, int dz, byte[] fill, byte[] speed, byte[] temperature) {
			this(x, y, z, dx, dy, dz, fill, speed, temperature, new byte[fill.length]);
		}

		int iceShare(int i) {
			return this.ice[i] & 0xFF;
		}

		float fullness(int i) {
			return (this.fill[i] & 0xFF) / 255.0F;
		}

		int degrees(int i) {
			return this.temperature[i] & 0xFF;
		}

		float blocksPerSecond(int i) {
			return (this.speed[i] & 0xFF) / 16.0F;
		}
	}

	private static ComputeProgram[] programs;

	private static ComputeProgram[] programs() {
		if (programs == null) {
			int U = Desc.UBO;
			int S = Desc.SSBO;
			programs = new ComputeProgram[]{
				new ComputeProgram("args.comp", U, S, S),
				new ComputeProgram("emit.comp", U, S, S, S, S),
				new ComputeProgram("clear_box.comp", U, S, S, S, S, S),
				new ComputeProgram("p2g.comp", p2gTile(9), U, S, S, S, S, S, S),
				new ComputeProgram("classify.comp", U, S, S, S, S),
				new ComputeProgram("faces.comp", U, S, S, S, S, S),
				new ComputeProgram("divergence.comp", U, S, S, S, S, S),
				new ComputeProgram("project.comp", U, S, S, S, S),
				new ComputeProgram("extrapolate.comp", U, S, S, S),
				new ComputeProgram("potential.comp", U, S, S, S, S),
				new ComputeProgram("g2p.comp", U, S, S, S, S, S, S, S, S, S, S, S, S, S, S),
				new ComputeProgram("ww_update.comp", U, S, S, S, S, S, S, S, S),
				new ComputeProgram("inflow.comp", U, S, S, S, S),
				new ComputeProgram("probe.comp", U, S, S, S),
				new ComputeProgram("shift.comp", U, S, S),
				new ComputeProgram("remove.comp", U, S, S),
				new ComputeProgram("merge.comp", U, S, S, S, S),
				new ComputeProgram("blockfill.comp", U, S, S, S, S, S),
				new ComputeProgram("mpm_clear.comp", U, S, S),
				new ComputeProgram("mpm_p2g.comp", p2gTile(5), U, S, S, S, S, S),
				new ComputeProgram("mpm_grid.comp", U, S, S),
				new ComputeProgram("mpm_g2p.comp", U, S, S, S, S, S, S, S),
				new ComputeProgram("blockfill.comp", Map.of("MPM", "1"), U, S, S, S, S),
				new ComputeProgram("viscosity.comp", U, S, S, S),
				new ComputeProgram("blast.comp", U, S, S),
				new ComputeProgram("blast.comp", Map.of("MPM", "1"), U, S, S, S),
				new ComputeProgram("leaf_clear.comp", U, S, S),
				new ComputeProgram("leaf_count.comp", U, S, S, S, S, S),
				new ComputeProgram("leaf_step.comp", U, S, S, S, S, S, S, S),
				new ComputeProgram("leaf_fill.comp", U, S, S, S),
				new ComputeProgram("body_flow.comp", U, S, S, S),
				new ComputeProgram("portal.comp", U, S, S, S, S),
				new ComputeProgram("shadow_occ.comp", U, S, S),
				new ComputeProgram("shadow_occ.comp", Map.of("LEAVES", "1"), U, S, S),
				new ComputeProgram("steam_step.comp", U, S, S, S, S),
				new ComputeProgram("rain_kick_map.comp", U, S, S),
				new ComputeProgram("rain_kick.comp", U, S, S, S),
				new ComputeProgram("ocean_fill.comp", U, S, S, S, S, S),
				new ComputeProgram("ocean_height.comp", U, S, S, S, S, S),
				new ComputeProgram("leaf_tally.comp", U, S, S, S),
				new ComputeProgram("leaf_expire.comp", U, S, S, S)
			};
		}
		return programs;
	}

	private static Map<String, String> p2gTile(int slots) {
		int limit;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceProperties(Vk.vk().getPhysicalDevice(), props);
			limit = props.limits().maxComputeSharedMemorySize();
		}
		int tile = Math.clamp((limit - 64) / (slots * 4), 64, 640);
		return Map.of("P2G_TILE", Integer.toString(tile));
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
	final Material material;

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
	VkBuf cellHeat;
	VkBuf[] wwCount = new VkBuf[2];
	private @Nullable VkBuf mpmGrid;
	private final VkBuf[] mpmState = new VkBuf[2];
	@Nullable VkBuf surfaceParticles;
	@Nullable VkBuf dropletIds;
	private @Nullable VkBuf leafRank;
	PressureSolver solver;
	SurfaceMesher mesher;
	private boolean freshGrid;
	private int[] solidMirror = new int[0];
	private boolean solidsFullUpload;
	private final List<int[]> solidPatches = new ArrayList<>();
	private final LongOpenHashSet dirtyChunks = new LongOpenHashSet();
	private int solidRangeLo = Integer.MAX_VALUE;
	private int solidRangeHi = Integer.MIN_VALUE;

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
	boolean tallyRequested;
	final List<int[]> expireRequests = new ArrayList<>();
	private static final int TALLY_CAPACITY = 8192;
	private final VkBuf[] tallyReadback = new VkBuf[Vk.FRAME_RING];
	private @Nullable VkBuf tallyBuf;
	private final long[] tallyFrame = new long[Vk.FRAME_RING];
	private final int[][] tallyWindow = new int[Vk.FRAME_RING][];
	private final VkBuf[] expireBufs = new VkBuf[Vk.FRAME_RING];

	record Tally(long frame, int[] window, List<int[]> leaves, boolean complete) {
	}

	@Nullable Tally lastTally;
	int lastFluidCount;
	private final int[] slotFrame = new int[Vk.FRAME_RING];
	private int lastAwake = -1;
	private int lastAwakeFrame = -1;
	private int wakeFrame;
	private int @Nullable [] fieldBox;
	private boolean fieldStale = true;
	int lastWhitewaterCount;
	int ignitions;
	@Nullable BlockPos ignitionBlock;
	float lastMaxSpeed;
	final float[] lastDirSpeed = new float[6];
	private final int[] growFrame = {Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2};
	private int emittedSinceReadback;
	private float calmSeconds;
	private @Nullable VkBuf light;
	private int[] lightMirror = new int[0];
	private int @Nullable [] lightWindow;
	private int lightCursor;
	private static final int LIGHT_BLOCKS_PER_FRAME = 4096;
	static final int MAX_PORTAL_BOXES = 32;
	private static final long PORTAL_TAKEN_OFFSET = MAX_PORTAL_BOXES * 32L;
	List<PortalSink> portalSinks = List.of();
	private final VkBuf[] portalBufs = new VkBuf[Vk.FRAME_RING];
	private final long[][] slotPortals = new long[Vk.FRAME_RING][];
	final Long2IntOpenHashMap portalTaken = new Long2IntOpenHashMap();
	@Nullable BackgroundWorld background;
	@Nullable OceanZone ocean;
	float lastOceanDeviation;
	@Nullable VkBuf seaHeights;
	private @Nullable VkBuf seaHeightsRaw;
	int seaW;
	int seaD;
	private final VkBuf[] seaReadback = new VkBuf[Vk.FRAME_RING];
	private final VkBuf[] seaColumns = new VkBuf[Vk.FRAME_RING];
	boolean seaReady;
	private final int[][] seaReadMeta = new int[Vk.FRAME_RING][];
	float @Nullable [] seaDisturbance;
	int seaDistX0;
	int seaDistZ0;
	int seaDistW;
	int seaDistD;
	static final int C_OCEAN_DEVIATION = 7;
	static final int OCEAN_REST_WATER = 1 << 23;
	static final int OCEAN_AMP_SHIFT = 24;
	static final int OCEAN_TOP = 1 << 29;
	static final int VANILLA_SINK = 1 << 30;
	float rainCredit;
	RainSystem.@Nullable Kicks rainKicks;
	private @Nullable VkBuf rainMap;
	boolean shadowRequested;
	boolean foamRequested;
	private final VkBuf[] foamReadback = new VkBuf[Vk.FRAME_RING];
	private final int[][] foamMeta = new int[Vk.FRAME_RING][];
	float @Nullable [] packFoam;
	int packFoamCount;
	boolean leafCopyForced;
	PackMaterials.@Nullable Mesh packMesh;
	private final VkBuf[] meshReadback = new VkBuf[Vk.FRAME_RING];
	private final int[][] meshMeta = new int[Vk.FRAME_RING][];
	private int meshVertsSeen = 4096;
	private int meshQuadsSeen = 4096;
	ShadowCasters.@Nullable Casters shadow;
	private @Nullable VkBuf occBuffer;
	private final VkBuf[] shadowReadback = new VkBuf[Vk.FRAME_RING];
	private final int[][] shadowMeta = new int[Vk.FRAME_RING][];
	private static final int SHADOW_LEAVES = 1;
	static final int MAX_HEAT_EVENTS = 4096;
	private final VkBuf[] heatEvents = new VkBuf[Vk.FRAME_RING];
	private final int[][] heatEventOrigin = new int[Vk.FRAME_RING][];
	final Long2IntOpenHashMap boiled = new Long2IntOpenHashMap();
	final Long2IntOpenHashMap wisps = new Long2IntOpenHashMap();
	final Long2IntOpenHashMap simmering = new Long2IntOpenHashMap();
	int lastHeated;
	float lastMaxTemp;
	static final int LIGHT_OPAQUE = 0x100;
	static final int THERMAL_SHIFT = 20;
	static final int LIGHT_HOT = 0x2000;
	static final int LIGHT_COVER_SHIFT = 14;

	WaterRegion(int id, int res, Material material) {
		this.id = id;
		this.res = res;
		this.material = material;
		for (int i = 0; i < 2; i++) {
			this.particles[i] = VkBuf.device((long) this.fluidCapacity * PARTICLE_BYTES, 0);
			this.whitewater[i] = VkBuf.device((long) this.wwCapacity * PARTICLE_BYTES, 0);
			if (material.isGranular()) {
				this.mpmState[i] = VkBuf.device((long) this.fluidCapacity * MPM_STATE_BYTES, 0);
			}
		}
		if (material.isGranular()) {
			this.surfaceParticles = VkBuf.device((long) this.fluidCapacity * SURFACE_PARTICLE_BYTES, 0);
		} else if (material.isLeaves()) {
			this.leafRank = VkBuf.device((long) this.fluidCapacity * 4L, 0);
		} else if (!material.isGas()) {
			this.dropletIds = VkBuf.device((long) this.fluidCapacity * 4L, 0);
		}
		this.counters = VkBuf.device(STATS_SLOT, 0);
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
		this.pendingCells = union(this.pendingCells, e.cells(this.res));
		this.pendingBlocks = union(this.pendingBlocks, blocks(e));
		this.pendingParticles += this.emitCount(e);
	}

	private int @Nullable [] pendingCells;
	private int @Nullable [] pendingBlocks;
	private long pendingParticles;

	private static int[] blocks(Emitter e) {
		return new int[]{(int) Math.floor(e.minX()), (int) Math.floor(e.minY()), (int) Math.floor(e.minZ()),
			(int) Math.ceil(e.maxX()), (int) Math.ceil(e.maxY()), (int) Math.ceil(e.maxZ())};
	}

	private void recountPending() {
		if (this.pending.isEmpty()) {
			this.pendingCells = null;
			this.pendingBlocks = null;
			this.pendingParticles = 0L;
			return;
		}
		int r = this.res;
		int[] c = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		int[] b = c.clone();
		long n = 0L;
		for (Emitter e : this.pending) {
			c[0] = Math.min(c[0], (int) Math.floor(e.minX() * r));
			c[1] = Math.min(c[1], (int) Math.floor(e.minY() * r));
			c[2] = Math.min(c[2], (int) Math.floor(e.minZ() * r));
			c[3] = Math.max(c[3], (int) Math.ceil(e.maxX() * r));
			c[4] = Math.max(c[4], (int) Math.ceil(e.maxY() * r));
			c[5] = Math.max(c[5], (int) Math.ceil(e.maxZ() * r));
			b[0] = Math.min(b[0], (int) Math.floor(e.minX()));
			b[1] = Math.min(b[1], (int) Math.floor(e.minY()));
			b[2] = Math.min(b[2], (int) Math.floor(e.minZ()));
			b[3] = Math.max(b[3], (int) Math.ceil(e.maxX()));
			b[4] = Math.max(b[4], (int) Math.ceil(e.maxY()));
			b[5] = Math.max(b[5], (int) Math.ceil(e.maxZ()));
			n += this.emitCount(e);
		}
		this.pendingCells = c;
		this.pendingBlocks = b;
		this.pendingParticles = n;
	}


	private void recordExpire(VkCommandBuffer cb, Desc ubo, int slot) {
		int n = Math.min(this.expireRequests.size(), 64);
		long bytes = 64L * 32L;
		if (this.expireBufs[slot] == null) {
			this.expireBufs[slot] = VkBuf.upload(bytes, VkBuf.STORAGE);
		}
		java.nio.ByteBuffer m = this.expireBufs[slot].mapped();
		for (int i = 0; i < n; i++) {
			int[] r = this.expireRequests.get(i);
			int o = i * 32;
			m.putInt(o, r[0]).putInt(o + 4, r[1]).putInt(o + 8, r[2]).putInt(o + 12, r[3]);
			m.putInt(o + 16, r[4]).putInt(o + 20, 0).putInt(o + 24, 0).putInt(o + 28, 0);
		}
		this.expireRequests.subList(0, n).clear();
		this.expireBufs[slot].flush(0, bytes);
		ComputeProgram expire = programs()[40];
		Vk.barrier(cb);
		expire.bind(cb, ubo, Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters), Desc.ssbo(this.expireBufs[slot]));
		expire.push(cb, C_FLUID + this.cur, n, 0, 0, this.ox, this.oy, this.oz, this.res);
		expire.dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);
	}

	private void recordTally(VkCommandBuffer cb, Desc ubo, int slot) {
		this.tallyRequested = false;
		long bytes = 16L + TALLY_CAPACITY * 16L;
		if (this.tallyReadback[slot] == null) {
			this.tallyReadback[slot] = VkBuf.readback(bytes);
		}
		if (this.tallyBuf == null) {
			this.tallyBuf = VkBuf.device(bytes, 0);
		}
		VkBuf out = this.tallyBuf;
		Vk.barrier(cb);
		out.fill(cb, 0, 16, 0);
		Vk.barrier(cb);
		ComputeProgram tally = programs()[39];
		tally.bind(cb, ubo, Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters), Desc.ssbo(out));
		tally.push(cb, C_FLUID + this.cur, TALLY_CAPACITY, 0, 0, this.ox, this.oy, this.oz, this.res);
		tally.dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);
		out.copyTo(cb, this.tallyReadback[slot], 0, 0, bytes);
		this.tallyFrame[slot] = Vk.frame();
		this.tallyWindow[slot] = new int[]{this.ox, this.oy, this.oz, this.ox + this.sx, this.oy + this.sy, this.oz + this.sz};
	}

	private void readTally(int slot) {
		int[] window = this.tallyWindow[slot];
		VkBuf buf = this.tallyReadback[slot];
		if (window == null || buf == null) {
			return;
		}
		this.tallyWindow[slot] = null;
		buf.invalidate(0, 16);
		java.nio.ByteBuffer m = buf.mapped();
		int count = m.getInt(0);
		int n = Math.min(count, TALLY_CAPACITY);
		if (n > 0) {
			buf.invalidate(16, n * 16L);
		}
		List<int[]> leaves = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			int o = 16 + i * 16;
			leaves.add(new int[]{m.getInt(o), m.getInt(o + 4), m.getInt(o + 8), m.getInt(o + 12)});
		}
		this.lastTally = new Tally(this.tallyFrame[slot], window, leaves, count <= TALLY_CAPACITY);
	}

	@Nullable Fill readBack() {
		int slot = Vk.ringSlot();
		if (this.material.isLeaves()) {
			this.readTally(slot);
		}
		if (this.ocean != null) {
			this.readSeaDisturbance(slot);
		}
		int off = (int) (slot * STATS_SLOT);
		this.statsReadback.invalidate(off, STATS_SLOT);
		ByteBuffer b = this.statsReadback.mapped();
		this.lastFluidCount = Math.max(b.getInt(off), b.getInt(off + 4));
		this.lastWhitewaterCount = Math.max(b.getInt(off + 8), b.getInt(off + 12));
		this.lastMaxSpeed = b.getFloat(off + 24);
		for (int a = 0; a < 6; a++) {
			this.lastDirSpeed[a] = b.getFloat(off + C_DIR_SPEED * 4 + a * 4);
		}
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
				int[] previous = this.particleBoxWorld;
				this.particleBoxWorld = box[3] > box[0] && box[4] > box[1] && box[5] > box[2] ? box : null;
				if (previous != null && this.particleBoxWorld != null) {
					for (int a = 0; a < 3; a++) {
						if (box[a] < previous[a]) {
							this.growFrame[a] = frameNow();
						}
						if (box[3 + a] > previous[3 + a]) {
							this.growFrame[3 + a] = frameNow();
						}
					}
				}
			}
			this.emittedSinceReadback = 0;
			this.lastAwake = b.getInt(off + C_AWAKE * 4);
			this.lastAwakeFrame = this.slotFrame[slot];
			if (this.material == Material.WATER) {
				this.lastHeated = b.getInt(off + C_HEATED * 4);
				this.lastMaxTemp = b.getFloat(off + C_MAX_TEMP * 4);
			}
			if (this.ocean != null) {
				long sum = Integer.toUnsignedLong(b.getInt(off + C_OCEAN_DEVIATION * 4));
				this.lastOceanDeviation = (float) Math.sqrt(sum / 64.0 / Math.max(this.lastFluidCount, 1));
			}
			if (this.material.isLeaves()) {
				int ignited = b.getInt(off + C_IGNITED * 4);
				int packed = b.getInt(off + C_IGNITE_POS * 4) - 1;
				if (ignited > 0 && packed >= 0) {
					int bx = Math.floorDiv(o[0], this.res) + (packed & 1023);
					int by = Math.floorDiv(o[1], this.res) + ((packed >> 10) & 1023);
					int bz = Math.floorDiv(o[2], this.res) + ((packed >> 20) & 1023);
					this.ignitions += ignited;
					this.ignitionBlock = new BlockPos(bx, by, bz);
				}
			}
		}
		this.slotHasAabb[slot] = false;

		this.readShadow(slot);
		this.readPackMesh(slot);
		this.readFoam(slot);
		this.readHeatEvents(slot);
		long[] portals = this.slotPortals[slot];
		if (portals != null) {
			this.slotPortals[slot] = null;
			VkBuf buf = this.portalBufs[slot];
			buf.invalidate(PORTAL_TAKEN_OFFSET, portals.length * 4L);
			ByteBuffer m = buf.mapped();
			for (int k = 0; k < portals.length; k++) {
				int taken = m.getInt((int) PORTAL_TAKEN_OFFSET + k * 4);
				if (taken > 0) {
					this.portalTaken.addTo(portals[k], taken);
				}
			}
		}

		Fill fill = null;
		int[] meta = this.fillMeta[slot];
		if (meta != null) {
			int n = meta[3] * meta[4] * meta[5];
			VkBuf rb = this.fillReadback[slot];
			rb.invalidate(0, n * 4L);
			byte[] data = new byte[n];
			byte[] speed = new byte[n];
			byte[] temperature = new byte[n];
			byte[] ice = new byte[n];
			ByteBuffer fb = rb.mapped();
			boolean water = this.material == Material.WATER;
			for (int i = 0; i < n; i++) {
				int packed = fb.getInt(i * 4);
				data[i] = (byte) packed;
				speed[i] = (byte) (packed >>> 8);
				temperature[i] = water ? (byte) (packed >>> 16) : 0;
				ice[i] = water ? (byte) (packed >>> 24) : 0;
			}
			if (this.material.isLeaves()) {
				carryLeavesUp(fb, data, speed, meta[3], meta[4], meta[5]);
			}
			fill = new Fill(meta[0], meta[1], meta[2], meta[3], meta[4], meta[5], data, speed, temperature, ice);
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
		if (this.pendingCells != null) {
			b = union(b, this.pendingCells);
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
		for (int a = 0; a < 3; a++) {
			b[a] -= WaterSettings.boxMargin + (int) Math.ceil(this.lastDirSpeed[a] * 0.12F);
			b[3 + a] += WaterSettings.boxMargin + (int) Math.ceil(this.lastDirSpeed[3 + a] * 0.12F);
		}
		return b;
	}

	private void clearSpeeds(VkCommandBuffer cb) {
		this.counters.fill(cb, C_MAX_SPEED * 4L, 4, 0);
		this.counters.fill(cb, C_DIR_SPEED * 4L, 24, 0);
	}

	static int[] unionBox(int[] a, int[] b) {
		return union(a, b);
	}


	boolean planWindow(ClientLevel level, SolidCache solids, int[] boxWorld) {
		this.boxWorld = boxWorld;
		boolean fits = this.hasWindow
			&& boxWorld[0] >= this.originCellX() && boxWorld[1] >= this.originCellY() && boxWorld[2] >= this.originCellZ()
			&& boxWorld[3] <= this.originCellX() + this.nx && boxWorld[4] <= this.originCellY() + this.ny && boxWorld[5] <= this.originCellZ() + this.nz;
		int[] wanted = this.windowFor(boxWorld, WINDOW_FULL);
		long wantedVolume = (long) wanted[3] * wanted[4] * wanted[5];
		if (fits && (long) this.sx * this.sy * this.sz > 2L * wantedVolume) {
			this.oversizeFrames++;
		} else {
			this.oversizeFrames = 0;
		}
		if (this.gridMissing && fits) {
			if (frameNow() < this.gridRetryFrame) {
				return false;
			}
			fits = false;
		}
		if (!fits && !this.gridMissing && this.hasWindow && this.ocean == null
			&& wanted[3] <= this.sx && wanted[4] <= this.sy && wanted[5] <= this.sz) {
			int[] oldWindow = {this.ox, this.oy, this.oz, this.sx, this.sy, this.sz};
			int[] oldMirror = this.solidMirror;
			this.setWindow(new int[]{
				wanted[0] - (this.sx - wanted[3]) / 2, wanted[1] - (this.sy - wanted[4]) / 2, wanted[2] - (this.sz - wanted[5]) / 2,
				this.sx, this.sy, this.sz
			});
			this.freshGrid = true;
			this.oversizeFrames = 0;
			this.rebuildSolids(level, solids, oldMirror, oldWindow);
		} else if (!fits || this.oversizeFrames > 120) {
			if (!this.reallocate(level, solids, boxWorld)) {
				return false;
			}
		} else if (this.ocean != null && this.ocean.solidsStale) {
			this.rebuildSolids(level, solids);
		}
		if (this.ocean != null) {
			this.ocean.solidsStale = false;
		}
		this.rewriteDirtyChunks(level, solids);
		this.claim = new int[]{this.ox, this.oy, this.oz, this.ox + this.sx, this.oy + this.sy, this.oz + this.sz};
		if (this.pendingBlocks != null) {
			int[] pb = this.pendingBlocks;
			this.claimBox(pb[0], pb[1], pb[2], pb[3], pb[4], pb[5]);
		}
		int[] b = new int[6];
		int[] o = {this.originCellX(), this.originCellY(), this.originCellZ()};
		int[] n = {this.nx, this.ny, this.nz};
		for (int a = 0; a < 3; a++) {
			b[a] = Math.max(0, boxWorld[a] - o[a]);
			b[3 + a] = Math.min(n[a], boxWorld[3 + a] - o[a]);
		}
		this.box = b;
		return true;
	}

	private boolean reallocate(ClientLevel level, SolidCache solids, int[] boxWorld) {
		int wanted = Math.max(1, WaterSettings.surfaceSubdivision);
		int mode = WINDOW_FULL;
		int sub = wanted;
		int @Nullable [] oldWindow = this.hasWindow ? new int[]{this.ox, this.oy, this.oz, this.sx, this.sy, this.sz} : null;
		int[] oldMirror = this.solidMirror;
		while (true) {
			this.setWindow(this.windowFor(boxWorld, mode));
			String failure;
			try {
				this.allocateGrid(sub);
				break;
			} catch (VkOutOfMemory | OutOfMemoryError e) {
				this.releaseGrid();
				failure = e.getMessage();
			}
			if (mode < WINDOW_TIGHT) {
				mode++;
			} else if (sub > 1) {
				sub--;
			} else {
				this.gridMissing = true;
				this.gridRetryFrame = frameNow() + GRID_RETRY_FRAMES;
				this.solidMirror = new int[0];
				this.memoryWarning = "Realistic Water: a " + this.material.label() + " area of " + this.sx + "x" + this.sy + "x" + this.sz
					+ " blocks needs more GPU memory than is free, so it is paused. Lower the resolution or the surface subdivision.";
				me.skaffy.RealisticWater.LOGGER.warn("{} ({})", this.memoryWarning, failure);
				return false;
			}
		}
		if (sub < wanted && this.memoryWarning == null) {
			this.memoryWarning = "Realistic Water: GPU memory is low, so a big " + this.material.label()
				+ " area uses a coarser surface (subdivision " + sub + " instead of " + wanted + ").";
			me.skaffy.RealisticWater.LOGGER.warn(this.memoryWarning);
		}
		this.gridMissing = false;
		this.oversizeFrames = 0;
		this.rebuildSolids(level, solids, oldMirror, oldWindow);
		return true;
	}

	static final int WINDOW_FULL = 0;
	static final int WINDOW_NO_LOOKAHEAD = 1;
	static final int WINDOW_TIGHT = 2;
	private static final double LOOKAHEAD_MAX_GROWTH = 1.5;
	private static final int LOOKAHEAD_GROW_FRAMES = 30;

	private int[] windowFor(int[] boxWorld, int mode) {
		boolean tight = mode == WINDOW_TIGHT;
		int[] lo = new int[3];
		int[] hi = new int[3];
		float[] ahead = new float[6];
		double base = 1.0;
		for (int a = 0; a < 3; a++) {
			int bMin = Math.floorDiv(boxWorld[a], this.res);
			int bMax = Math.ceilDiv(boxWorld[3 + a], this.res);
			int slack = tight ? 1 : this.ocean != null && a != 1 ? Math.max(8, (bMax - bMin) / 2) : Math.max(2, (bMax - bMin) / 4);
			lo[a] = bMin - slack;
			hi[a] = bMax + slack;
			base *= hi[a] - lo[a];
			if (mode == WINDOW_FULL && this.ocean == null) {
				ahead[a] = this.lookaheadBlocks(a);
				ahead[3 + a] = this.lookaheadBlocks(3 + a);
			}
		}
		float f = 1.0F;
		while (f > 0.05F) {
			double v = 1.0;
			for (int a = 0; a < 3; a++) {
				v *= hi[a] - lo[a] + Math.ceil(f * ahead[a]) + Math.ceil(f * ahead[3 + a]);
			}
			if (v <= LOOKAHEAD_MAX_GROWTH * base) {
				break;
			}
			f *= 0.75F;
		}
		if (f <= 0.05F) {
			f = 0.0F;
		}
		int[] w = new int[6];
		for (int a = 0; a < 3; a++) {
			w[a] = lo[a] - (int) Math.ceil(f * ahead[a]);
			w[3 + a] = hi[a] + (int) Math.ceil(f * ahead[3 + a]) - w[a];
			if (w[3 + a] * this.res % 2 != 0) {
				w[3 + a]++;
			}
		}
		return w;
	}

	private int lookaheadBlocks(int side) {
		if (frameNow() - this.growFrame[side] > LOOKAHEAD_GROW_FRAMES) {
			return 0;
		}
		float blocks = this.lastDirSpeed[side] / this.res * WaterSettings.windowLookahead;
		return Float.isFinite(blocks) && blocks >= 0.5F ? (int) Math.ceil(blocks) : 0;
	}

	private void setWindow(int[] w) {
		if (this.hasWindow) {
			int[] d = {(this.ox - w[0]) * this.res, (this.oy - w[1]) * this.res, (this.oz - w[2]) * this.res};
			if (this.pendingShift != null) {
				for (int a = 0; a < 3; a++) {
					d[a] += this.pendingShift[a];
				}
			}
			this.pendingShift = d[0] == 0 && d[1] == 0 && d[2] == 0 ? null : d;
		}
		this.ox = w[0];
		this.oy = w[1];
		this.oz = w[2];
		this.sx = w[3];
		this.sy = w[4];
		this.sz = w[5];
		this.nx = this.sx * this.res;
		this.ny = this.sy * this.res;
		this.nz = this.sz * this.res;
		this.hasWindow = true;
	}

	@Nullable String takeMemoryWarning() {
		String w = this.memoryWarning;
		if (w != null && !this.memoryWarned) {
			this.memoryWarned = true;
			return w;
		}
		return null;
	}

	boolean gridMissing() {
		return this.gridMissing;
	}

	private static int frameNow() {
		return (int) Vk.frame();
	}

	private static final int GRID_RETRY_FRAMES = 600;
	private boolean gridMissing;
	private int gridRetryFrame;
	private @Nullable String memoryWarning;
	private boolean memoryWarned;

	private void allocateGrid(int subdivision) {
		this.releaseGrid();
		long cells = (long) this.nx * this.ny * this.nz;
		if (cells > Integer.MAX_VALUE / 8 - 1_000_000L) {
			throw new VkOutOfMemory("grid of " + cells + " cells is too large");
		}
		this.numCells = this.nx * this.ny * this.nz;
		this.numU = (this.nx + 1) * this.ny * this.nz;
		this.numV = this.nx * (this.ny + 1) * this.nz;
		this.numW = this.nx * this.ny * (this.nz + 1);
		this.numFaces = this.numU + this.numV + this.numW;
		this.cellCount = VkBuf.device(this.numCells * 4L, 0);
		this.cellFlags = VkBuf.device(this.numCells * 4L, 0);
		this.staticSolid = VkBuf.device(this.numCells * 4L, 0);
		if (this.material.isGranular()) {
			this.mpmGrid = VkBuf.device(this.numCells * 16L, 0);
		} else if (!this.material.isParticleOnly()) {
			this.accum = VkBuf.device(this.numFaces * 8L, 0);
			this.vel = VkBuf.device(this.numFaces * 4L, 0);
			this.velOld = VkBuf.device(this.numFaces * 4L, 0);
			this.valid0 = VkBuf.device(this.numFaces * 4L, 0);
			this.valid1 = VkBuf.device(this.numFaces * 4L, 0);
			this.cellDens = VkBuf.device(this.numCells * 4L, 0);
			this.pressure = VkBuf.device(this.numCells * 4L, 0);
			this.divergence = VkBuf.device(this.numCells * 4L, 0);
			this.potential = VkBuf.device(this.numCells * 8L, 0);
			this.cellHeat = VkBuf.device(this.numCells * 4L, 0);
			this.wwCount[0] = VkBuf.device(this.numCells * 4L, 0);
			this.wwCount[1] = VkBuf.device(this.numCells * 4L, 0);
			this.solver = new PressureSolver(this.nx, this.ny, this.nz);
		}
		this.mesher = new SurfaceMesher(this.nx, this.ny, this.nz, subdivision);
		this.freshGrid = true;
	}

	private void releaseGrid() {
		for (AutoCloseable c : new AutoCloseable[]{
			this.accum, this.vel, this.velOld, this.valid0, this.valid1, this.cellCount, this.cellDens, this.cellFlags, this.staticSolid,
			this.pressure, this.divergence, this.potential, this.cellHeat, this.wwCount[0], this.wwCount[1], this.mpmGrid, this.solver, this.mesher
		}) {
			if (c != null) {
				Vk.destroyLater(c);
			}
		}
		this.accum = this.vel = this.velOld = this.valid0 = this.valid1 = null;
		this.cellCount = this.cellDens = this.cellFlags = this.staticSolid = null;
		this.pressure = this.divergence = this.potential = this.cellHeat = null;
		this.wwCount[0] = this.wwCount[1] = null;
		this.mpmGrid = null;
		this.solver = null;
		this.mesher = null;
	}

	private void rebuildSolids(ClientLevel level, SolidCache solids, int[] oldMirror, int @Nullable [] old) {
		int r = this.res;
		if (old == null || this.ocean != null || oldMirror.length != old[3] * r * old[4] * r * old[5] * r) {
			this.rebuildSolids(level, solids);
			return;
		}
		int x0 = Math.max(this.ox, old[0]);
		int y0 = Math.max(this.oy, old[1]);
		int z0 = Math.max(this.oz, old[2]);
		int x1 = Math.min(this.ox + this.sx, old[0] + old[3]);
		int y1 = Math.min(this.oy + this.sy, old[1] + old[4]);
		int z1 = Math.min(this.oz + this.sz, old[2] + old[5]);
		if (x0 >= x1 || y0 >= y1 || z0 >= z1) {
			this.rebuildSolids(level, solids);
			return;
		}
		int[] mirror = new int[this.numCells];
		int onx = old[3] * r;
		int ony = old[4] * r;
		int len = (x1 - x0) * r;
		for (int cz = (z0 - old[2]) * r, dz = (z0 - this.oz) * r, ez = (z1 - old[2]) * r; cz < ez; cz++, dz++) {
			for (int cy = (y0 - old[1]) * r, dy = (y0 - this.oy) * r, ey = (y1 - old[1]) * r; cy < ey; cy++, dy++) {
				System.arraycopy(oldMirror, (x0 - old[0]) * r + onx * (cy + ony * cz), mirror, (x0 - this.ox) * r + this.nx * (dy + this.ny * dz), len);
			}
		}
		this.solidMirror = mirror;
		for (int z = this.oz; z < this.oz + this.sz; z++) {
			boolean zIn = z >= z0 && z < z1;
			for (int y = this.oy; y < this.oy + this.sy; y++) {
				boolean yzIn = zIn && y >= y0 && y < y1;
				for (int x = this.ox; x < this.ox + this.sx; x++) {
					if (yzIn && x >= x0 && x < x1) {
						x = x1 - 1;
						continue;
					}
					this.writeSolid(level, solids, x, y, z);
				}
			}
		}
		this.solidsFullUpload = true;
		this.solidPatches.clear();
		this.solidRangeLo = Integer.MAX_VALUE;
		this.solidRangeHi = Integer.MIN_VALUE;
	}

	private void rebuildSolids(ClientLevel level, SolidCache solids) {
		this.solidMirror = new int[this.numCells];
		for (int bz = 0; bz < this.sz; bz++) {
			for (int by = 0; by < this.sy; by++) {
				for (int bx = 0; bx < this.sx; bx++) {
					this.writeSolid(level, solids, this.ox + bx, this.oy + by, this.oz + bz);
				}
			}
		}
		this.solidsFullUpload = true;
		this.solidPatches.clear();
		this.dirtyChunks.clear();
		this.solidRangeLo = Integer.MAX_VALUE;
		this.solidRangeHi = Integer.MIN_VALUE;
	}

	void patchSolid(ClientLevel level, SolidCache solids, BlockPos pos) {
		if (!this.windowContains(pos, 0) || this.gridMissing) {
			return;
		}
		int bx = pos.getX() - this.ox;
		int by = pos.getY() - this.oy;
		int bz = pos.getZ() - this.oz;
		this.writeSolid(level, solids, pos.getX(), pos.getY(), pos.getZ());
		if (this.solidsFullUpload) {
			return;
		}
		if (this.solidPatches.size() > 256) {
			this.solidsFullUpload = true;
			this.solidPatches.clear();
		} else {
			this.solidPatches.add(new int[]{bx, by, bz});
		}
	}

	private void writeSolid(ClientLevel level, SolidCache solids, int x, int y, int z) {
		int cx = (x - this.ox) * this.res;
		int cy = (y - this.oy) * this.res;
		int cz = (z - this.oz) * this.res;
		if (this.ocean != null && this.ocean.walls(x, y, z)) {
			SolidCache.writeFull(this.solidMirror, cx, cy, cz, this.nx, this.ny, this.res);
		} else {
			solids.writeBlock(level, x, y, z, this.solidMirror, cx, cy, cz, this.nx, this.ny);
		}
	}

	void markSolidsDirty(int chunkX, int chunkZ) {
		if (this.hasWindow) {
			this.dirtyChunks.add(ChunkPos.pack(chunkX, chunkZ));
		}
	}

	private void rewriteDirtyChunks(ClientLevel level, SolidCache solids) {
		if (this.dirtyChunks.isEmpty()) {
			return;
		}
		for (LongIterator it = this.dirtyChunks.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			int x0 = Math.max(ChunkPos.getX(key) << 4, this.ox);
			int z0 = Math.max(ChunkPos.getZ(key) << 4, this.oz);
			int x1 = Math.min((ChunkPos.getX(key) << 4) + 16, this.ox + this.sx);
			int z1 = Math.min((ChunkPos.getZ(key) << 4) + 16, this.oz + this.sz);
			if (x0 >= x1 || z0 >= z1) {
				continue;
			}
			for (int z = z0; z < z1; z++) {
				for (int y = this.oy; y < this.oy + this.sy; y++) {
					for (int x = x0; x < x1; x++) {
						this.writeSolid(level, solids, x, y, z);
					}
				}
			}
			int slab = this.nx * this.ny * this.res;
			this.solidRangeLo = Math.min(this.solidRangeLo, (z0 - this.oz) * slab);
			this.solidRangeHi = Math.max(this.solidRangeHi, (z1 - this.oz) * slab);
		}
		this.dirtyChunks.clear();
	}

	private void uploadSolids(VkCommandBuffer cb) {
		if (this.solidsFullUpload) {
			VkBuf staging = VkBuf.upload(this.numCells * 4L, 0);
			staging.mapped().asIntBuffer().put(0, this.solidMirror);
			staging.flush(0, this.numCells * 4L);
			staging.copyTo(cb, this.staticSolid, 0, 0, this.numCells * 4L);
			Vk.destroyLater(staging);
			this.solidRangeLo = Integer.MAX_VALUE;
			this.solidRangeHi = Integer.MIN_VALUE;
		}
		if (this.solidRangeHi > this.solidRangeLo) {
			int lo = this.solidRangeLo;
			int n = this.solidRangeHi - lo;
			VkBuf staging = VkBuf.upload(n * 4L, 0);
			staging.mapped().asIntBuffer().put(0, this.solidMirror, lo, n);
			staging.flush(0, n * 4L);
			staging.copyTo(cb, this.staticSolid, 0, lo * 4L, n * 4L);
			Vk.destroyLater(staging);
			this.solidRangeLo = Integer.MAX_VALUE;
			this.solidRangeHi = Integer.MIN_VALUE;
		}
		if (!this.solidsFullUpload && !this.solidPatches.isEmpty()) {
			int r = this.res;
			int rows = this.solidPatches.size() * r * r;
			VkBuf staging = VkBuf.upload((long) rows * r * 4L, 0);
			ByteBuffer mapped = staging.mapped();
			VkBufferCopy.Buffer regions = VkBufferCopy.malloc(rows);
			try {
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
			} finally {
				regions.free();
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
			if (this.material.isGranular()) {
				VkBuf[] grown = grow(cb, this.mpmState, this.cur, (long) this.fluidCapacity * MPM_STATE_BYTES, (long) next * MPM_STATE_BYTES);
				this.mpmState[0] = grown[0];
				this.mpmState[1] = grown[1];
				Vk.destroyLater(this.surfaceParticles);
				this.surfaceParticles = VkBuf.device((long) next * SURFACE_PARTICLE_BYTES, 0);
			} else if (this.material.isLeaves()) {
				Vk.destroyLater(this.leafRank);
				this.leafRank = VkBuf.device((long) next * 4L, 0);
			} else if (!this.material.isGas()) {
				Vk.destroyLater(this.dropletIds);
				this.dropletIds = VkBuf.device((long) next * 4L, 0);
			}
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
			int count = this.emitCount(e);
			if (n >= MAX_EMITTERS_PER_FRAME || (long) total + count > this.fluidCapacity - this.lastFluidCount - this.emittedSinceReadback) {
				break;
			}
			int o = (int) (base + (long) n * EMITTER_BYTES);
			int[] lattice = this.emitLattice(e);
			buf.putFloat(o, (float) ((e.minX() - this.ox) * r)).putFloat(o + 4, (float) ((e.minY() - this.oy) * r))
				.putFloat(o + 8, (float) ((e.minZ() - this.oz) * r)).putFloat(o + 12, 0);
			buf.putFloat(o + 16, (float) ((e.maxX() - this.ox) * r)).putFloat(o + 20, (float) ((e.maxY() - this.oy) * r))
				.putFloat(o + 24, (float) ((e.maxZ() - this.oz) * r)).putFloat(o + 28, 0);
			buf.putFloat(o + 32, e.vx() * r).putFloat(o + 36, e.vy() * r).putFloat(o + 40, e.vz() * r).putFloat(o + 44, e.heat());
			buf.putInt(o + 48, total).putInt(o + 52, lattice[0]).putInt(o + 56, lattice[1]).putInt(o + 60, lattice[2]);
			total += count;
			n++;
		}
		this.emitterRing.flush(base, (long) Math.max(n, 1) * EMITTER_BYTES);
		totalOut[0] = total;
		return n;
	}

	private int[] emitLattice(Emitter e) {
		return this.material.isLeaves() ? e.leafLattice() : this.material.isGas() ? e.gasLattice() : e.lattice(this.res);
	}

	private int emitCount(Emitter e) {
		int[] n = this.emitLattice(e);
		return n[0] * n[1] * n[2];
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
			this.recountPending();
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
		this.recountPending();
		this.removals.addAll(other.removals);
		this.owned.addAll(other.owned);
		this.emittedSinceReadback += other.lastFluidCount + other.emittedSinceReadback;
		if (other.boxWorld != null) {
			this.recentEmitWorld = union(this.recentEmitWorld, other.boxWorld);
		}
	}

	void updateLight(ClientLevel level) {
		if (!this.hasWindow) {
			return;
		}
		int[] window = {this.ox, this.oy, this.oz, this.sx, this.sy, this.sz};
		int count = this.sx * this.sy * this.sz;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		if (this.light == null || !java.util.Arrays.equals(window, this.lightWindow)) {
			int[] fresh = new int[count];
			int[] old = this.lightWindow;
			for (int i = 0; i < count; i++) {
				int x = i % this.sx;
				int y = (i / this.sx) % this.sy;
				int z = i / (this.sx * this.sy);
				if (old != null) {
					int px = this.ox + x - old[0];
					int py = this.oy + y - old[1];
					int pz = this.oz + z - old[2];
					if (px >= 0 && py >= 0 && pz >= 0 && px < old[3] && py < old[4] && pz < old[5]) {
						fresh[i] = this.lightMirror[px + old[3] * (py + old[4] * pz)];
						continue;
					}
				}
				fresh[i] = this.lightAt(level, pos.set(this.ox + x, this.oy + y, this.oz + z));
			}
			this.lightMirror = fresh;
			this.lightWindow = window;
			this.lightCursor = 0;
			if (this.light != null) {
				Vk.destroyLater(this.light);
			}
			this.light = VkBuf.upload(Math.max(count, 1) * 4L, VkBuf.STORAGE);
			this.light.mapped().asIntBuffer().put(0, fresh);
			this.light.flush(0, count * 4L);
			return;
		}
		int n = Math.min(count, LIGHT_BLOCKS_PER_FRAME);
		int start = this.lightCursor;
		for (int k = 0; k < n; k++) {
			int i = (start + k) % count;
			int x = i % this.sx;
			int y = (i / this.sx) % this.sy;
			int z = i / (this.sx * this.sy);
			this.lightMirror[i] = this.lightAt(level, pos.set(this.ox + x, this.oy + y, this.oz + z));
		}
		this.lightCursor = (start + n) % count;
		int first = Math.min(n, count - start);
		this.light.mapped().asIntBuffer().put(start, this.lightMirror, start, first);
		this.light.flush(start * 4L, first * 4L);
		if (first < n) {
			this.light.mapped().asIntBuffer().put(0, this.lightMirror, 0, n - first);
			this.light.flush(0, (n - first) * 4L);
		}
	}

	private int lightAt(ClientLevel level, BlockPos pos) {
		int block = level.getBrightness(LightLayer.BLOCK, pos);
		int sky = level.getBrightness(LightLayer.SKY, pos);
		BlockState state = level.getBlockState(pos);
		int opaque = state.canOcclude() ? LIGHT_OPAQUE : 0;
		int medium = 0;
		if (this.material.isLeaves()) {
			FluidState fluid = state.getFluidState();
			if (fluid.is(FluidTags.WATER)) {
				medium |= Math.clamp(fluid.getAmount(), 1, 8) << 9;
			}
			if (fluid.is(FluidTags.LAVA) || state.is(BlockTags.FIRE)) {
				medium |= LIGHT_HOT;
			}
		}
		int thermal = 0;
		if (this.material == Material.WATER) {
			int code = thermalCode(state);
			if (code == 0 && conductsHeat(state)) {
				code = conductedCode(level, pos, CONDUCT_REACH);
			}
			thermal = code << THERMAL_SHIFT;
		}
		int sea = 0;
		if (this.ocean != null && OceanWaves.isRestWater(state)) {
			sea = OCEAN_REST_WATER;
			if (!OceanWaves.isRestWater(level.getBlockState(pos.above()))) {
				float amp = WaterWorld.get().oceanAmplitude(pos.getX(), pos.getY(), pos.getZ());
				sea |= OCEAN_TOP | Math.clamp(Math.round(amp / OceanWaves.AMP_MAX * 31.0F), 0, 31) << OCEAN_AMP_SHIFT;
			}
		}
		int sink = 0;
		if (this.material == Material.WATER && this.ocean == null && WaterSettings.vanillaWaterExchange
			&& (state.getFluidState().getType() == Fluids.WATER || me.skaffy.block.RealisticWaterBlock.isOcean(state))) {
			sink = VANILLA_SINK;
		}
		return block | sky << 4 | opaque | medium | shadowCover(state) << LIGHT_COVER_SHIFT | thermal | sea | sink;
	}

	private static final int CONDUCT_REACH = 3;
	private static final java.util.Map<Block, Boolean> CONDUCTORS = new java.util.concurrent.ConcurrentHashMap<>();

	/** Iron and copper (every copper variant) carry heat; so does gold. Ores, ingots, torches and the like don't count. */
	static boolean conductsHeat(BlockState state) {
		return CONDUCTORS.computeIfAbsent(state.getBlock(), b -> {
			String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
			if (path.contains("ore") || path.contains("torch") || path.contains("golem") || path.contains("raw")) {
				return false;
			}
			return path.equals("iron_block") || path.equals("iron_bars") || path.equals("gold_block") || path.endsWith("iron_trapdoor")
				|| path.contains("copper") || path.equals("heavy_weighted_pressure_plate") || path.equals("light_weighted_pressure_plate");
		});
	}

	/** Heat reaching a metal block from a hot block touching it, or from more metal that is itself heated, a few blocks along. */
	private static int conductedCode(ClientLevel level, BlockPos pos, int depth) {
		int best = 0;
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			BlockState s = level.getBlockState(n);
			int c = thermalCode(s);
			if (c == 7) {
				return 7;
			}
			if (c == 1 || c == 2 || c == 3) {
				return 3;
			}
			if (c == 4) {
				best = 4;
			} else if (depth > 1 && conductsHeat(s)) {
				int through = conductedCode(level, n, depth - 1);
				if (through == 3 || through == 7) {
					return through;
				}
				best = Math.max(best, through);
			}
		}
		return best;
	}

	static int thermalCode(BlockState state) {
		if (state.is(Blocks.MAGMA_BLOCK)) {
			return 1;
		}
		if (state.getFluidState().is(FluidTags.LAVA)) {
			return 2;
		}
		if (state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES) && state.getValue(CampfireBlock.LIT)) {
			return 3;
		}
		if (state.getBlock() instanceof AbstractFurnaceBlock && state.getValue(AbstractFurnaceBlock.LIT)) {
			return 4;
		}
		if (me.skaffy.block.HotPlateBlock.isHot(state)) {
			return 7;
		}
		if (state.is(BlockTags.ICE)) {
			return 5;
		}
		if (state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.SNOW) || state.is(Blocks.POWDER_SNOW)) {
			return 6;
		}
		return 0;
	}

	void refreshLightAt(ClientLevel level, BlockPos pos) {
		if (this.light == null || this.lightWindow == null || !this.windowContains(pos, 0)) {
			return;
		}
		int[] w = this.lightWindow;
		int x = pos.getX() - w[0];
		int y = pos.getY() - w[1];
		int z = pos.getZ() - w[2];
		if (x < 0 || y < 0 || z < 0 || x >= w[3] || y >= w[4] || z >= w[5]) {
			return;
		}
		int i = x + w[3] * (y + w[4] * z);
		this.lightMirror[i] = this.lightAt(level, pos);
		this.light.mapped().putInt(i * 4, this.lightMirror[i]);
		this.light.flush(i * 4L, 4);
	}

	private static int shadowCover(BlockState state) {
		if (state.getBlock() == ModContent.REALISTIC_SAND || state.getBlock() == ModContent.REALISTIC_LEAVES) {
			return state.getValue(LooseLayerBlock.LAYERS);
		}
		if (state.getBlock() == ModContent.REALISTIC_SLIME_BLOCK) {
			return Math.clamp(state.getFluidState().getAmount(), 0, 8);
		}
		if (state.isAir() || state.canOcclude()) {
			return 0;
		}
		return state.getLightDampening() > 0 || state.is(BlockTags.LEAVES) ? 6 : 0;
	}

	VkBuf light() {
		return this.light;
	}

	boolean heating() {
		return this.material == Material.WATER && WaterSettings.waterTemperature
			&& (this.lastHeated > 0 || this.lastMaxTemp > WaterSettings.steamWispTemperature);
	}

	boolean whitewaterOn() {
		return this.material.usesWhitewater() && (this.ocean != null ? WaterSettings.oceanWhitewater : WaterSettings.whitewater);
	}

	boolean dormant(boolean fed) {
		if (this.ocean != null) {
			return false;
		}
		boolean settled = this.material.isGranular() || this.material.isParticleOnly()
			? this.lastAwake == 0
			: WaterSettings.fluidRestTime > 0.0F && !fed && this.calmSeconds >= WaterSettings.fluidRestTime
				&& this.lastWhitewaterCount <= WaterSettings.fluidRestWhitewater && !this.heating();
		return settled && this.initialized && this.lastAwakeFrame > this.wakeFrame
			&& this.pending.isEmpty() && this.removals.isEmpty() && this.absorbQueue.isEmpty() && this.pendingShift == null
			&& !this.solidsFullUpload && this.solidPatches.isEmpty() && this.solidRangeHi <= this.solidRangeLo && !this.freshGrid;
	}

	void trackCalm(float seconds, int steps) {
		if (steps <= 0 || this.material.isGranular() || this.material.isParticleOnly()) {
			return;
		}
		if (this.lastMaxSpeed / this.res < WaterSettings.fluidRestSpeed) {
			this.calmSeconds += seconds;
		} else {
			this.calmSeconds = 0.0F;
		}
	}

	void wake(int frame) {
		this.wakeFrame = frame;
		this.calmSeconds = 0.0F;
	}

	boolean takeFieldStale() {
		boolean stale = this.fieldStale || !java.util.Arrays.equals(this.fieldBox, this.box);
		this.fieldStale = false;
		this.fieldBox = this.box == null ? null : this.box.clone();
		return stale;
	}

	void queueAbsorb(WaterRegion other) {
		this.absorbQueue.add(other);
	}

	private final List<WaterRegion> absorbQueue = new ArrayList<>();

	void record(VkCommandBuffer cb, Desc ubo, int steps, int numSources, int frame, VkBuf frameView, int numRemovals, List<float[]> blasts) {
		int slot = Vk.ringSlot();
		int[] box = this.box;
		ComputeProgram[] p = programs();
		if (!this.pending.isEmpty() || numRemovals > 0 || !blasts.isEmpty() || !this.absorbQueue.isEmpty() || this.pendingShift != null || this.freshGrid
			|| this.solidsFullUpload || !this.solidPatches.isEmpty() || this.solidRangeHi > this.solidRangeLo) {
			this.wake(frame);
		}
		if (steps > 0) {
			this.fieldStale = true;
			this.counters.fill(cb, C_AWAKE * 4L, 4, 0);
			if (this.material == Material.WATER) {
				this.counters.fill(cb, C_HEATED * 4L, 4, 0);
				this.counters.fill(cb, C_MAX_TEMP * 4L, 4, 0);
			}
			if (this.material.isLeaves()) {
				this.counters.fill(cb, C_IGNITED * 4L, 4, 0);
				this.counters.fill(cb, C_IGNITE_POS * 4L, 4, 0);
			}
		}

		if (!this.material.isGranular() && !this.material.isParticleOnly()) {
			VkBuf events = this.heatEventBuffer(slot);
			events.mapped().putInt(0, 0);
			events.flush(0, 16);
			this.heatEventOrigin[slot] = steps > 0 && this.material == Material.WATER
				? new int[]{this.originCellX(), this.originCellY(), this.originCellZ()} : null;
		}
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
			this.cellCount.fill(cb, 0);
			this.cellFlags.fill(cb, 0);
			if (this.material.isGranular()) {
				this.mpmGrid.fill(cb, 0);
			} else if (!this.material.isParticleOnly()) {
				this.pressure.fill(cb, 0);
				this.wwCount[0].fill(cb, 0);
				this.wwCount[1].fill(cb, 0);
				this.cellDens.fill(cb, 0);
				this.cellHeat.fill(cb, 0);
			}
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
		int incoming = (int) Math.min(this.pendingParticles, Integer.MAX_VALUE);
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
		if (!this.whitewaterOn()) {
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
			this.fieldStale = true;
			done.clear();
			this.recountPending();
		}
		if (numRemovals > 0 && this.initialized) {
			this.fieldStale = true;
			p[15].bind(cb, ubo, Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters));
			p[15].push(cb, C_FLUID + this.cur);
			p[15].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
			Vk.barrier(cb);
		}
		if (steps > 0 && this.initialized && !this.portalSinks.isEmpty()) {
			this.recordPortals(cb, ubo, slot);
		}
		if (steps > 0 && this.initialized && this.rainKicks != null) {
			this.recordRainKicks(cb, ubo, this.rainKicks);
		}
		if (!blasts.isEmpty() && this.initialized) {
			this.fieldStale = true;
			boolean mpm = this.material.isGranular();
			ComputeProgram blast = p[mpm ? 25 : 24];
			for (float[] b : blasts) {
				if (mpm) {
					blast.bind(cb, ubo, Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters), Desc.ssbo(this.mpmState[this.cur]));
				} else {
					blast.bind(cb, ubo, Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters));
				}
				blast.push(cb, C_FLUID + this.cur, this.seed++, 0, 0, Program.f(b[0]), Program.f(b[1]), Program.f(b[2]), Program.f(b[3]),
					Program.f(b[4]), Program.f(b[5]), Program.f(b[6]), 0);
				blast.dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
				Vk.barrier(cb);
			}
		}

		if (this.material.isLeaves() && this.initialized && !this.expireRequests.isEmpty()) {
			this.recordExpire(cb, ubo, slot);
		}
		for (int s = 0; s < steps; s++) {
			if (this.material.isGranular()) {
				this.mpmStep(cb, ubo, box);
			} else if (this.material.isLeaves()) {
				this.leafStep(cb, ubo, box);
			} else if (this.material.isGas()) {
				this.steamStep(cb, ubo);
			} else {
				this.step(cb, ubo, numSources, box, s == 0);
			}
		}
		if (this.ocean != null) {
			this.recordSeaHeights(cb, ubo);
		}

		p[0].bind(cb, ubo, Desc.ssbo(this.counters), Desc.ssbo(this.args));
		p[0].push(cb, C_FLUID + this.cur, C_WW + this.wwCur);
		p[0].dispatch(cb, 1, 1, 1);
		if (this.material.usesWhitewater() && !(this.ocean != null && OceanMode.packDrawsSea())) {
			p[13].bind(cb, ubo, Desc.ssbo(this.cellDens), Desc.ssbo(this.cellFlags), Desc.ssbo(frameView));
			p[13].dispatch(cb, 1, 1, 1);
		}
		if (this.fillRequested) {
			this.recordFill(cb, ubo, slot);
		}
		if (this.tallyRequested && this.material.isLeaves() && this.initialized) {
			this.recordTally(cb, ubo, slot);
		}
		if (this.shadowRequested) {
			this.recordShadow(cb, ubo, slot);
		}
		if (this.foamRequested) {
			this.recordFoam(cb, slot);
		}
		Vk.barrier(cb);
		this.counters.copyTo(cb, this.statsReadback, 0, slot * STATS_SLOT, STATS_SLOT);
		this.slotHasAabb[slot] = steps > 0;
		this.slotFrame[slot] = frame;
		this.slotOrigin[slot] = new int[]{this.originCellX(), this.originCellY(), this.originCellZ()};
		this.slotWindow[slot] = new int[]{this.nx, this.ny, this.nz};
		this.initialized = true;
	}

	private void recordPortals(VkCommandBuffer cb, Desc ubo, int slot) {
		int n = Math.min(this.portalSinks.size(), MAX_PORTAL_BOXES);
		if (this.portalBufs[slot] == null) {
			this.portalBufs[slot] = VkBuf.upload(PORTAL_TAKEN_OFFSET + MAX_PORTAL_BOXES * 4L, VkBuf.STORAGE);
		}
		VkBuf buf = this.portalBufs[slot];
		ByteBuffer m = buf.mapped();
		float r = this.res;
		long[] portals = new long[n];
		for (int k = 0; k < n; k++) {
			PortalSink sink = this.portalSinks.get(k);
			int[] b = sink.box();
			m.putFloat(k * 32, (b[0] - this.ox) * r).putFloat(k * 32 + 4, (b[1] - this.oy) * r).putFloat(k * 32 + 8, (b[2] - this.oz) * r)
				.putFloat(k * 32 + 12, 0.0F);
			m.putFloat(k * 32 + 16, (b[3] - this.ox) * r).putFloat(k * 32 + 20, (b[4] - this.oy) * r).putFloat(k * 32 + 24, (b[5] - this.oz) * r)
				.putFloat(k * 32 + 28, 0.0F);
			m.putInt((int) PORTAL_TAKEN_OFFSET + k * 4, 0);
			portals[k] = sink.portal();
		}
		buf.flush(0, PORTAL_TAKEN_OFFSET + MAX_PORTAL_BOXES * 4L);
		this.slotPortals[slot] = portals;
		ComputeProgram portal = programs()[31];
		portal.bind(cb, ubo, Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters), Desc.ssbo(buf, 0, PORTAL_TAKEN_OFFSET),
			Desc.ssbo(buf, PORTAL_TAKEN_OFFSET, MAX_PORTAL_BOXES * 4L));
		portal.push(cb, C_FLUID + this.cur, n);
		portal.dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);
	}

	private void recordShadow(VkCommandBuffer cb, Desc ubo, int slot) {
		this.shadowRequested = false;
		int[] b = this.box;
		if (b == null || !this.initialized) {
			return;
		}
		Vk.barrier(cb);
		if (this.material.isLeaves() && (WaterSettings.packShadowPerLeaf || this.leafCopyForced)) {
			long n = Math.min((long) this.lastFluidCount + this.emittedSinceReadback, this.fluidCapacity);
			if (n <= 0) {
				return;
			}
			VkBuf rb = this.shadowBuffer(slot, 16L + n * PARTICLE_BYTES);
			this.counters.copyTo(cb, rb, (long) (C_FLUID + this.cur) * 4L, 0, 4);
			this.particles[this.cur].copyTo(cb, rb, 0, 16, n * PARTICLE_BYTES);
			this.shadowMeta[slot] = new int[]{SHADOW_LEAVES, this.ox, this.oy, this.oz, this.res, (int) n};
			return;
		}
		int d = Math.max(1, WaterSettings.packShadowDetail);
		int r = this.res;
		int px0 = Math.floorDiv(b[0] * d, r);
		int py0 = Math.floorDiv(b[1] * d, r);
		int pz0 = Math.floorDiv(b[2] * d, r);
		int nx = Math.ceilDiv(b[3] * d, r) - px0;
		int ny = Math.ceilDiv(b[4] * d, r) - py0;
		int nz = Math.ceilDiv(b[5] * d, r) - pz0;
		int words = Math.ceilDiv(nx, 4);
		long n = (long) words * ny * nz;
		if (nx <= 0 || ny <= 0 || nz <= 0) {
			return;
		}
		if (this.occBuffer == null || this.occBuffer.size < n * 4) {
			if (this.occBuffer != null) {
				Vk.destroyLater(this.occBuffer);
			}
			this.occBuffer = VkBuf.device(n * 4 * 2, 0);
		}
		VkBuf rb = this.shadowBuffer(slot, n * 4);
		ComputeProgram occ = programs()[this.material.isLeaves() ? 33 : 32];
		occ.bind(cb, ubo, Desc.ssbo(this.cellCount), Desc.ssbo(this.occBuffer));
		occ.push(cb, px0, py0, pz0, d, nx, ny, nz, words);
		occ.dispatch(cb, ComputeProgram.groups(n, 64), 1, 1);
		Vk.barrier(cb);
		this.occBuffer.copyTo(cb, rb, 0, 0, n * 4);
		this.shadowMeta[slot] = new int[]{0, this.ox, this.oy, this.oz, d, px0, py0, pz0, nx, ny, nz, words};
	}

	private VkBuf heatEventBuffer(int slot) {
		if (this.heatEvents[slot] == null) {
			this.heatEvents[slot] = VkBuf.readback(16L + MAX_HEAT_EVENTS * 16L);
		}
		return this.heatEvents[slot];
	}

	private void readHeatEvents(int slot) {
		int[] o = this.heatEventOrigin[slot];
		if (o == null) {
			return;
		}
		this.heatEventOrigin[slot] = null;
		VkBuf buf = this.heatEvents[slot];
		buf.invalidate(0, 16);
		int n = Math.min(buf.mapped().getInt(0), MAX_HEAT_EVENTS);
		if (n <= 0) {
			return;
		}
		buf.invalidate(16, n * 16L);
		ByteBuffer m = buf.mapped();
		for (int k = 0; k < n; k++) {
			int at = 16 + k * 16;
			int bx = Math.floorDiv(o[0] + (int) Math.floor(m.getFloat(at)), this.res);
			int by = Math.floorDiv(o[1] + (int) Math.floor(m.getFloat(at + 4)), this.res);
			int bz = Math.floorDiv(o[2] + (int) Math.floor(m.getFloat(at + 8)), this.res);
			float kind = m.getFloat(at + 12);
			(kind < 0.5F ? this.boiled : kind < 1.5F ? this.wisps : this.simmering).addTo(BlockPos.asLong(bx, by, bz), 1);
		}
	}

	private void recordFoam(VkCommandBuffer cb, int slot) {
		this.foamRequested = false;
		if (!this.initialized || this.whitewater[this.wwCur] == null) {
			return;
		}
		long n = Math.min((long) this.lastWhitewaterCount + this.lastWhitewaterCount / 4 + 256, this.wwCapacity);
		long size = 16L + n * PARTICLE_BYTES;
		VkBuf rb = this.foamReadback[slot];
		if (rb == null || rb.size < size) {
			if (rb != null) {
				Vk.destroyLater(rb);
			}
			rb = VkBuf.readback(size + size / 2);
			this.foamReadback[slot] = rb;
		}
		Vk.barrier(cb);
		this.counters.copyTo(cb, rb, (long) (C_WW + this.wwCur) * 4L, 0, 4);
		this.whitewater[this.wwCur].copyTo(cb, rb, 0, 16, n * PARTICLE_BYTES);
		this.foamMeta[slot] = new int[]{this.ox, this.oy, this.oz, this.res, (int) n};
	}

	private void readFoam(int slot) {
		int[] m = this.foamMeta[slot];
		if (m == null) {
			return;
		}
		this.foamMeta[slot] = null;
		VkBuf rb = this.foamReadback[slot];
		long n = m[4];
		rb.invalidate(0, 16L + n * PARTICLE_BYTES);
		ByteBuffer b = rb.mapped();
		int count = (int) Math.min(n, Integer.toUnsignedLong(b.getInt(0)));
		float[] out = this.packFoam != null && this.packFoam.length >= count * 8 ? this.packFoam : new float[Math.max(count, 64) * 8];
		float inv = 1.0F / m[3];
		for (int i = 0; i < count; i++) {
			int at = 16 + i * PARTICLE_BYTES;
			int o = i * 8;
			out[o] = m[0] + b.getFloat(at) * inv;
			out[o + 1] = m[1] + b.getFloat(at + 4) * inv;
			out[o + 2] = m[2] + b.getFloat(at + 8) * inv;
			out[o + 3] = b.getFloat(at + 16) * inv;
			out[o + 4] = b.getFloat(at + 20) * inv;
			out[o + 5] = b.getFloat(at + 24) * inv;
			out[o + 6] = b.getFloat(at + 28);
			out[o + 7] = b.getFloat(at + 12);
		}
		this.packFoam = out;
		this.packFoamCount = count;
	}

	private VkBuf shadowBuffer(int slot, long size) {
		VkBuf rb = this.shadowReadback[slot];
		if (rb == null || rb.size < size) {
			if (rb != null) {
				Vk.destroyLater(rb);
			}
			rb = VkBuf.readback(size + size / 2);
			this.shadowReadback[slot] = rb;
		}
		return rb;
	}

	void recordPackMesh(VkCommandBuffer cb, Desc ubo) {
		if (this.box == null || !this.initialized) {
			return;
		}
		int slot = Vk.ringSlot();
		if (this.takeFieldStale()) {
			this.mesher.build(cb, ubo, this.particles[this.cur], this.counters, C_FLUID + this.cur, this.args, this.staticSolid, this.cellFlags,
				this.mesher.fineBox(this.box), WaterSettings.sheetFill, null, 0);
			Vk.barrier(cb);
		}
		int verts = Math.min(this.mesher.vertexCapacity, this.meshVertsSeen + this.meshVertsSeen / 4 + 1024);
		int quads = Math.min(this.mesher.quadCapacity, this.meshQuadsSeen + this.meshQuadsSeen / 4 + 1024);
		long size = 16L + (verts + (long) quads) * 16L;
		VkBuf rb = this.meshReadback[slot];
		if (rb == null || rb.size < size) {
			if (rb != null) {
				Vk.destroyLater(rb);
			}
			rb = VkBuf.readback(size + size / 2);
			this.meshReadback[slot] = rb;
		}
		this.mesher.meshCounters.copyTo(cb, rb, 0, 0, 16);
		this.mesher.verts.copyTo(cb, rb, 0, 16, verts * 16L);
		this.mesher.quads.copyTo(cb, rb, 0, 16 + verts * 16L, quads * 16L);
		this.meshMeta[slot] = new int[]{verts, quads, this.ox, this.oy, this.oz};
	}

	private void readPackMesh(int slot) {
		int[] m = this.meshMeta[slot];
		if (m == null) {
			return;
		}
		this.meshMeta[slot] = null;
		VkBuf rb = this.meshReadback[slot];
		rb.invalidate(0, 16L + (m[0] + (long) m[1]) * 16L);
		ByteBuffer b = rb.mapped();
		this.meshVertsSeen = Math.max(1024, b.getInt(0));
		this.meshQuadsSeen = Math.max(1024, b.getInt(4));
		this.packMesh = PackMaterials.mesh(this.material, b, m[0], m[1], m[2], m[3], m[4], this.res);
	}

	private void readShadow(int slot) {
		int[] m = this.shadowMeta[slot];
		if (m == null) {
			return;
		}
		this.shadowMeta[slot] = null;
		VkBuf rb = this.shadowReadback[slot];
		if (m[0] == SHADOW_LEAVES) {
			long n = m[5];
			rb.invalidate(0, 16L + n * PARTICLE_BYTES);
			this.shadow = ShadowCasters.leaves(rb.mapped(), (int) Math.min(n, Integer.toUnsignedLong(rb.mapped().getInt(0))), m[1], m[2], m[3], m[4]);
			return;
		}
		long n = (long) m[11] * m[9] * m[10];
		rb.invalidate(0, n * 4);
		this.shadow = ShadowCasters.pieces(this.material, rb.mapped(), m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11]);
	}

	private void recordRainKicks(VkCommandBuffer cb, Desc ubo, RainSystem.Kicks kicks) {
		long columns = (long) this.nx * this.nz;
		if (this.rainMap == null || this.rainMap.size < columns * 8L) {
			if (this.rainMap != null) {
				Vk.destroyLater(this.rainMap);
			}
			this.rainMap = VkBuf.device(columns * 8L, 0);
		}
		this.rainMap.fill(cb, 0, columns * 8L, 0);
		Vk.barrier(cb);
		ComputeProgram[] p = programs();
		p[35].bind(cb, ubo, Desc.ssbo(kicks.events()), Desc.ssbo(this.rainMap));
		p[35].push(cb, Program.f(kicks.originX() - this.ox), Program.f(-this.oy), Program.f(kicks.originZ() - this.oz), Program.f(this.res),
			kicks.capacity(), 0, 0, 0);
		p[35].dispatch(cb, ComputeProgram.groups(kicks.capacity(), 64), 1, 1);
		Vk.barrier(cb);
		p[36].bind(cb, ubo, Desc.ssbo(this.particles[this.cur]), Desc.ssbo(this.counters), Desc.ssbo(this.rainMap));
		p[36].push(cb, C_FLUID + this.cur, this.seed++, 0, 0, Program.f(WaterSettings.rainKick * this.res), Program.f(0.35F * this.res), 0, 0);
		p[36].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);
	}

	private static void carryLeavesUp(ByteBuffer fb, byte[] data, byte[] speed, int dx, int dy, int dz) {
		for (int z = 0; z < dz; z++) {
			for (int x = 0; x < dx; x++) {
				float carry = 0.0F;
				int carrySpeed = 0;
				for (int y = 0; y < dy; y++) {
					int i = x + dx * (y + dy * z);
					float amount = (fb.getInt(i * 4) >>> 16) / 2048.0F + carry;
					float kept = Math.min(amount, 1.0F);
					int own = speed[i] & 0xFF;
					if (carry > 0.0F && carrySpeed > own) {
						speed[i] = (byte) carrySpeed;
					}
					carrySpeed = carry > 0.0F ? Math.max(carrySpeed, own) : own;
					carry = amount - kept;
					data[i] = (byte) Math.round(kept * 255.0F);
				}
			}
		}
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
		if (this.material.isLeaves()) {
			double packed = (double) r * r * r * Math.max(1, WaterSettings.leafCellCapacity) / Math.max(1, WaterSettings.leavesPerBlock);
			dy += (int) Math.ceil(dy * Math.max(0.0, packed - 1.0));
		}
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
		if (this.material.isLeaves()) {
			ComputeProgram leafFill = programs()[29];
			leafFill.bind(cb, ubo, Desc.ssbo(this.cellCount), Desc.ssbo(this.cellFlags), Desc.ssbo(this.fillBuffer));
			leafFill.push(cb, x0, y0, z0, Math.max(1, WaterSettings.leavesPerBlock), dx, dy, dz, 0);
			leafFill.dispatch(cb, ComputeProgram.groups(n, 64), 1, 1);
			Vk.barrier(cb);
			this.fillBuffer.copyTo(cb, this.fillReadback[slot], 0, 0, n * 4);
			this.fillMeta[slot] = new int[]{this.ox + x0, this.oy + y0, this.oz + z0, dx, dy, dz};
			return;
		}
		boolean sand = this.material.isGranular();
		if (sand) {
			ComputeProgram classify = programs()[4];
			classify.bind(cb, ubo, Desc.ssbo(this.staticSolid), Desc.ssbo(this.cellCount), Desc.ssbo(this.cellFlags),
				Desc.ssbo(this.cellHeat != null ? this.cellHeat : this.cellCount));
			classify.dispatch(cb, ComputeProgram.groups(boxCells(b), 256), 1, 1);
			Vk.barrier(cb);
		}
		ComputeProgram fill = programs()[sand ? 22 : 17];
		if (sand) {
			fill.bind(cb, ubo, Desc.ssbo(this.cellCount), Desc.ssbo(this.cellFlags), Desc.ssbo(this.fillBuffer), Desc.ssbo(this.mpmGrid));
		} else {
			fill.bind(cb, ubo, Desc.ssbo(this.cellCount), Desc.ssbo(this.cellFlags), Desc.ssbo(this.fillBuffer), Desc.ssbo(this.vel),
				Desc.ssbo(this.cellHeat));
		}
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
		boolean ww = this.whitewaterOn();
		int cellGroups = ComputeProgram.groups(boxCells(box), 256);
		int faceGroups = ComputeProgram.groups(boxFaces(box), 256);

		if (numSources > 0 && !(first && this.pendingShiftHappened)) {
			p[12].bind(cb, ubo, Desc.ssbo(this.particles[src]), Desc.ssbo(this.counters), Desc.ssbo(this.cellCount), Desc.ssbo(this.staticSolid));
			p[12].push(cb, C_FLUID + src, this.seed++);
			p[12].dispatch(cb, ComputeProgram.groups((long) numSources * this.res * this.res * this.res, 64), 1, 1);
			Vk.barrier(cb);
		}

		if (this.ocean != null && this.initialized) {
			p[37].bind(cb, ubo, Desc.ssbo(this.particles[src]), Desc.ssbo(this.counters), Desc.ssbo(this.cellCount), Desc.ssbo(this.staticSolid),
				Desc.ssbo(this.light));
			p[37].push(cb, C_FLUID + src, this.seed++);
			p[37].dispatch(cb, ComputeProgram.groups(boxCells(box), 64), 1, 1);
			Vk.barrier(cb);
		}

		this.counters.fill(cb, (C_FLUID + dst) * 4L, 4, 0);
		this.counters.fill(cb, (C_WW + wwDst) * 4L, 4, 0);
		this.clearSpeeds(cb);
		if (this.ocean != null) {
			this.counters.fill(cb, C_OCEAN_DEVIATION * 4L, 4, 0);
		}
		p[2].bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.cellCount), Desc.ssbo(this.cellDens), Desc.ssbo(this.wwCount[wwDst]),
			Desc.ssbo(this.cellHeat));
		p[2].dispatch(cb, ComputeProgram.groups((long) boxCells(box) + boxFaces(box), 256), 1, 1);

		p[0].bind(cb, ubo, Desc.ssbo(this.counters), Desc.ssbo(this.args));
		p[0].push(cb, C_FLUID + src, C_WW + wwSrc);
		p[0].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);

		p[3].bind(cb, ubo, Desc.ssbo(this.particles[src]), Desc.ssbo(this.accum), Desc.ssbo(this.cellCount), Desc.ssbo(this.cellDens),
			Desc.ssbo(this.counters), Desc.ssbo(this.cellHeat));
		p[3].push(cb, C_FLUID + src);
		p[3].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);

		p[4].bind(cb, ubo, Desc.ssbo(this.staticSolid), Desc.ssbo(this.cellCount), Desc.ssbo(this.cellFlags),
			Desc.ssbo(this.cellHeat != null ? this.cellHeat : this.cellCount));
		p[4].dispatch(cb, cellGroups, 1, 1);
		Vk.barrier(cb);

		p[5].bind(cb, ubo, Desc.ssbo(this.accum), Desc.ssbo(this.vel), Desc.ssbo(this.velOld), Desc.ssbo(this.valid0), Desc.ssbo(this.cellFlags));
		p[5].dispatch(cb, faceGroups, 1, 1);
		Vk.barrier(cb);

		if (this.material == Material.SLIME && WaterSettings.slimeViscosity > 0.0F && WaterSettings.slimeViscosityIterations > 0) {
			this.viscosity(cb, ubo, faceGroups);
		}

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
			Desc.ssbo(this.potential), Desc.ssbo(this.whitewater[wwDst]), Desc.ssbo(this.wwCount[wwSrc]), Desc.ssbo(this.cellCount),
			Desc.ssbo(this.cellHeat), Desc.ssbo(this.light), Desc.ssbo(this.heatEventBuffer(Vk.ringSlot())));
		p[10].push(cb, C_FLUID + src, C_FLUID + dst, 0, this.seed++, C_WW + wwDst, ww ? 1 : 0, MAX_HEAT_EVENTS);
		p[10].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);

		if (ww) {
			p[11].bind(cb, ubo,
				Desc.ssbo(this.whitewater[wwSrc]), Desc.ssbo(this.whitewater[wwDst]), Desc.ssbo(this.counters),
				Desc.ssbo(this.vel), Desc.ssbo(this.cellCount), Desc.ssbo(this.staticSolid), Desc.ssbo(this.wwCount[wwDst]), Desc.ssbo(this.cellHeat));
			p[11].push(cb, C_WW + wwSrc, C_WW + wwDst, 0, this.seed++);
			p[11].dispatchIndirect(cb, this.args, ARGS_WW_DISPATCH);
			Vk.barrier(cb);
		}

		this.cur = dst;
		this.wwCur = wwDst;
		this.pendingShiftHappened = false;
	}

	private boolean pendingShiftHappened;

	private void recordSeaHeights(VkCommandBuffer cb, Desc ubo) {
		OceanZone z = this.ocean;
		int w = (z.x1 - z.x0) * this.res;
		int d = (z.z1 - z.z0) * this.res;
		if (w <= 0 || d <= 0) {
			this.seaW = 0;
			this.seaReady = false;
			return;
		}
		long bytes = (long) w * d * 4L;
		if (this.seaHeights == null || this.seaHeights.size < bytes) {
			if (this.seaHeights != null) {
				Vk.destroyLater(this.seaHeights);
				Vk.destroyLater(this.seaHeightsRaw);
			}
			this.seaHeights = VkBuf.device(bytes, 0);
			this.seaHeightsRaw = VkBuf.device(bytes, 0);
		}
		this.seaW = w;
		this.seaD = d;
		int zx = (z.x0 - this.ox) * this.res;
		int zz = (z.z0 - this.oz) * this.res;
		int sea = (z.seaY - this.oy) * this.res;
		ComputeProgram heights = programs()[38];
		Vk.barrier(cb);
		heights.bind(cb, ubo, Desc.ssbo(this.cellCount), Desc.ssbo(this.staticSolid), Desc.ssbo(this.seaHeights), Desc.ssbo(this.seaHeightsRaw),
			Desc.ssbo(this.cellFlags));
		heights.push(cb, zx, zz, w, d, 0, sea, 0, 0);
		heights.dispatch(cb, ComputeProgram.groups((long) w * d, 64), 1, 1);
		Vk.barrier(cb);
		heights.bind(cb, ubo, Desc.ssbo(this.cellCount), Desc.ssbo(this.staticSolid), Desc.ssbo(this.seaHeightsRaw), Desc.ssbo(this.seaHeights),
			Desc.ssbo(this.cellFlags));
		heights.push(cb, zx, zz, w, d, 1, sea, 0, 0);
		heights.dispatch(cb, ComputeProgram.groups((long) w * d, 64), 1, 1);
		Vk.barrier(cb);
		this.seaReady = true;
		if (OceanMode.packDrawsSea()) {
			int slot = Vk.ringSlot();
			if (this.seaReadback[slot] == null || this.seaReadback[slot].size < bytes) {
				if (this.seaReadback[slot] != null) {
					Vk.destroyLater(this.seaReadback[slot]);
				}
				this.seaReadback[slot] = VkBuf.readback(bytes);
			}
			this.seaHeights.copyTo(cb, this.seaReadback[slot], 0, 0, bytes);
			this.seaReadMeta[slot] = new int[]{z.x0, z.z0, w, d};
		}
	}

	boolean seaDrawable() {
		return this.ocean != null && this.seaReady && this.seaHeights != null && this.seaW > 0 && this.hasWindow && !this.gridMissing;
	}

	Desc writeSeaColumns(net.minecraft.client.multiplayer.ClientLevel level, OceanSurfaces surfaces) {
		OceanZone z = this.ocean;
		int x0 = z.x0 - 1;
		int z0 = z.z0 - 1;
		int w = z.x1 - z.x0 + 2;
		int d = z.z1 - z.z0 + 2;
		long bytes = (long) w * d * 4L;
		int slot = Vk.ringSlot();
		VkBuf b = this.seaColumns[slot];
		if (b == null || b.size < bytes) {
			if (b != null) {
				Vk.destroyLater(b);
			}
			b = VkBuf.upload(Math.max(bytes * 2L, 4096L), VkBuf.STORAGE);
			this.seaColumns[slot] = b;
		}
		java.nio.ByteBuffer m = b.mapped();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int k = 0; k < d; k++) {
			for (int i = 0; i < w; i++) {
				int x = x0 + i;
				int zz = z0 + k;
				int v = 0;
				if (surfaces.restTop(x, zz) == z.seaY) {
					float amp = surfaces.amplitude(x, zz);
					v = Math.clamp(Math.round(Math.min(amp, OceanWaves.AMP_MAX) / OceanWaves.AMP_MAX * 31.0F), 0, 31) | 32;
				}
				pos.set(x, z.seaY + 1, zz);
				v |= (level.getBrightness(LightLayer.BLOCK, pos) | level.getBrightness(LightLayer.SKY, pos) << 4) << 8;
				m.putInt((i + w * k) * 4, v);
			}
		}
		b.flush(0, bytes);
		return Desc.ssbo(b, 0, bytes);
	}

	private void readSeaDisturbance(int slot) {
		int[] meta = this.seaReadMeta[slot];
		if (meta == null) {
			return;
		}
		this.seaReadMeta[slot] = null;
		int n = meta[2] * meta[3];
		VkBuf rb = this.seaReadback[slot];
		rb.invalidate(0, n * 4L);
		float[] out = this.seaDisturbance != null && this.seaDisturbance.length == n ? this.seaDisturbance : new float[n];
		rb.mapped().asFloatBuffer().get(0, out, 0, n);
		this.seaDisturbance = out;
		this.seaDistX0 = meta[0];
		this.seaDistZ0 = meta[1];
		this.seaDistW = meta[2];
		this.seaDistD = meta[3];
	}

	static final int MAX_FLOW_POINTS = 256;
	private final VkBuf[] flowPoints = new VkBuf[Vk.FRAME_RING];
	private final int[][] flowOwners = new int[Vk.FRAME_RING][];
	private final int[] flowOrigin = new int[Vk.FRAME_RING];

	void recordFlowProbe(VkCommandBuffer cb, Desc ubo, float[] points, int[] owners, int count) {
		if (count <= 0 || this.vel == null || !this.initialized) {
			return;
		}
		int slot = Vk.ringSlot();
		if (this.flowPoints[slot] == null) {
			this.flowPoints[slot] = VkBuf.upload((long) MAX_FLOW_POINTS * 32L, VkBuf.STORAGE);
		}
		ByteBuffer m = this.flowPoints[slot].mapped();
		for (int k = 0; k < count; k++) {
			m.putFloat(k * 32, points[k * 3]).putFloat(k * 32 + 4, points[k * 3 + 1]).putFloat(k * 32 + 8, points[k * 3 + 2]).putFloat(k * 32 + 12, 0.0F);
		}
		this.flowPoints[slot].flush(0, (long) count * 32L);
		this.flowOwners[slot] = java.util.Arrays.copyOf(owners, count);
		this.flowOrigin[slot] = this.oy;
		Vk.barrier(cb);
		ComputeProgram probe = programs()[30];
		probe.bind(cb, ubo, Desc.ssbo(this.vel), Desc.ssbo(this.cellFlags), Desc.ssbo(this.flowPoints[slot]));
		probe.push(cb, count);
		probe.dispatch(cb, ComputeProgram.groups(count, 64), 1, 1);
	}

	void readFlow(java.util.Map<Integer, double[]> out) {
		int slot = Vk.ringSlot();
		int[] owners = this.flowOwners[slot];
		if (owners == null) {
			return;
		}
		this.flowOwners[slot] = null;
		VkBuf buf = this.flowPoints[slot];
		buf.invalidate(0, (long) owners.length * 32L);
		ByteBuffer m = buf.mapped();
		int originY = this.flowOrigin[slot];
		for (int k = 0; k < owners.length; k++) {
			double[] acc = out.computeIfAbsent(owners[k], id -> new double[7]);
			acc[6] += 1.0;
			if (m.getFloat(k * 32 + 12) > 0.5F) {
				acc[0] += m.getFloat(k * 32);
				acc[1] += m.getFloat(k * 32 + 4);
				acc[2] += m.getFloat(k * 32 + 8);
				acc[3] += 1.0;
			}
			if (m.getFloat(k * 32 + 20) > 0.5F) {
				acc[4] += originY + m.getFloat(k * 32 + 16) / this.res;
				acc[5] += 1.0;
			}
		}
	}

	private void viscosity(VkCommandBuffer cb, Desc ubo, int faceGroups) {
		ComputeProgram visc = programs()[23];
		int sweeps = Math.max(2, WaterSettings.slimeViscosityIterations);
		float nu = WaterSettings.slimeViscosity * this.res * this.res;
		for (int k = 0; k < sweeps; k++) {
			int src = k == 0 ? -1 : ((k - 1) % 2) * this.numFaces;
			int dst = k == sweeps - 1 ? -1 : (k % 2) * this.numFaces;
			visc.bind(cb, ubo, Desc.ssbo(this.vel), Desc.ssbo(this.accum), Desc.ssbo(this.cellFlags));
			visc.push(cb, src, dst, 0, 0, Program.f(nu), 0, 0, 0);
			visc.dispatch(cb, faceGroups, 1, 1);
			Vk.barrier(cb);
		}
	}

	private void mpmStep(VkCommandBuffer cb, Desc ubo, int[] box) {
		ComputeProgram[] p = programs();
		int src = this.cur;
		int dst = 1 - this.cur;
		int cellGroups = ComputeProgram.groups(boxCells(box), 256);

		this.counters.fill(cb, (C_FLUID + dst) * 4L, 4, 0);
		this.clearSpeeds(cb);
		p[18].bind(cb, ubo, Desc.ssbo(this.mpmGrid), Desc.ssbo(this.cellCount));
		p[18].dispatch(cb, cellGroups, 1, 1);

		p[0].bind(cb, ubo, Desc.ssbo(this.counters), Desc.ssbo(this.args));
		p[0].push(cb, C_FLUID + src, C_WW + this.wwCur);
		p[0].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);

		p[19].bind(cb, ubo, Desc.ssbo(this.particles[src]), Desc.ssbo(this.mpmState[src]), Desc.ssbo(this.mpmGrid), Desc.ssbo(this.cellCount),
			Desc.ssbo(this.counters));
		p[19].push(cb, C_FLUID + src);
		p[19].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);

		p[20].bind(cb, ubo, Desc.ssbo(this.mpmGrid), Desc.ssbo(this.staticSolid));
		p[20].dispatch(cb, cellGroups, 1, 1);
		Vk.barrier(cb);

		p[21].bind(cb, ubo, Desc.ssbo(this.particles[src]), Desc.ssbo(this.particles[dst]), Desc.ssbo(this.counters), Desc.ssbo(this.mpmGrid),
			Desc.ssbo(this.mpmState[src]), Desc.ssbo(this.mpmState[dst]), Desc.ssbo(this.staticSolid));
		p[21].push(cb, C_FLUID + src, C_FLUID + dst, 0, this.seed++);
		p[21].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);

		this.cur = dst;
		this.pendingShiftHappened = false;
	}

	private void leafStep(VkCommandBuffer cb, Desc ubo, int[] box) {
		ComputeProgram[] p = programs();
		int src = this.cur;
		int dst = 1 - this.cur;

		this.counters.fill(cb, (C_FLUID + dst) * 4L, 4, 0);
		this.clearSpeeds(cb);
		p[26].bind(cb, ubo, Desc.ssbo(this.cellCount), Desc.ssbo(this.cellFlags));
		p[26].dispatch(cb, ComputeProgram.groups(boxCells(box), 256), 1, 1);

		p[0].bind(cb, ubo, Desc.ssbo(this.counters), Desc.ssbo(this.args));
		p[0].push(cb, C_FLUID + src, C_WW + this.wwCur);
		p[0].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);

		p[27].bind(cb, ubo, Desc.ssbo(this.particles[src]), Desc.ssbo(this.cellCount), Desc.ssbo(this.cellFlags), Desc.ssbo(this.leafRank),
			Desc.ssbo(this.counters));
		p[27].push(cb, C_FLUID + src);
		p[27].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);

		p[28].bind(cb, ubo, Desc.ssbo(this.particles[src]), Desc.ssbo(this.particles[dst]), Desc.ssbo(this.counters), Desc.ssbo(this.cellCount),
			Desc.ssbo(this.staticSolid), Desc.ssbo(this.leafRank), Desc.ssbo(this.light));
		p[28].push(cb, C_FLUID + src, C_FLUID + dst, 0, this.seed++);
		p[28].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);

		this.cur = dst;
		this.pendingShiftHappened = false;
	}

	private void steamStep(VkCommandBuffer cb, Desc ubo) {
		ComputeProgram[] p = programs();
		int src = this.cur;
		int dst = 1 - this.cur;
		this.counters.fill(cb, (C_FLUID + dst) * 4L, 4, 0);
		this.clearSpeeds(cb);
		p[0].bind(cb, ubo, Desc.ssbo(this.counters), Desc.ssbo(this.args));
		p[0].push(cb, C_FLUID + src, C_WW + this.wwCur);
		p[0].dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
		p[34].bind(cb, ubo, Desc.ssbo(this.particles[src]), Desc.ssbo(this.particles[dst]), Desc.ssbo(this.counters), Desc.ssbo(this.staticSolid));
		p[34].push(cb, C_FLUID + src, C_FLUID + dst, 0, this.seed++);
		p[34].dispatchIndirect(cb, this.args, ARGS_FLUID_DISPATCH);
		Vk.barrier(cb);
		this.cur = dst;
		this.pendingShiftHappened = false;
	}

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

	VkBuf spareParticles() {
		return this.particles[1 - this.cur];
	}

	void useSpareParticles() {
		this.cur = 1 - this.cur;
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
			this.particles[0], this.particles[1], this.whitewater[0], this.whitewater[1], this.mpmState[0], this.mpmState[1], this.surfaceParticles,
			this.dropletIds, this.leafRank, this.counters, this.light,
			this.args, this.emitterRing,
			this.statsReadback, this.fillBuffer, this.fillReadback[0], this.fillReadback[1], this.fillReadback[2],
			this.flowPoints[0], this.flowPoints[1], this.flowPoints[2],
			this.portalBufs[0], this.portalBufs[1], this.portalBufs[2],
			this.occBuffer, this.shadowReadback[0], this.shadowReadback[1], this.shadowReadback[2],
			this.meshReadback[0], this.meshReadback[1], this.meshReadback[2], this.rainMap, this.heatEvents[0], this.heatEvents[1], this.heatEvents[2],
			this.seaHeights, this.seaHeightsRaw, this.seaReadback[0], this.seaReadback[1], this.seaReadback[2],
			this.seaColumns[0], this.seaColumns[1], this.seaColumns[2],
			this.foamReadback[0], this.foamReadback[1], this.foamReadback[2],
			this.tallyReadback[0], this.tallyReadback[1], this.tallyReadback[2], this.tallyBuf, this.expireBufs[0], this.expireBufs[1], this.expireBufs[2]
		}) {
			if (c != null) {
				Vk.destroyLater(c);
			}
		}
	}
}
