package me.skaffy.client.snow;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticSnowBlock;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterWorld;
import me.skaffy.net.SnowMeltPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class SnowCover {
	private static final SnowCover INSTANCE = new SnowCover();
	private static final int MARGIN = 8;
	private static final int TILE = 8;
	private static final int SCANS_PER_TICK = 4096;
	private static final int REFRESH_PER_TICK = 512;
	private static final int DIRTY_PER_TICK = 4096;
	private static final int COLUMN_BYTES = 48;
	private static final int MAX_ADJUST = 64;
	private static final int PARAM_BYTES = 112 + 128 * 48 + 48 + MAX_ADJUST * 16;
	private static final int SPRAY_CAPACITY = 16384;
	private static final int SPAWN_MAX = 4096;
	private static final int SPAWN_SLOT = SPAWN_MAX * 32;
	private static final int LANDED_MAX = 4096;
	private static final int LANDED_SLOT = 16 + LANDED_MAX * 16;
	private static final float SPRAY_GRAVITY = 16.0F;
	private static final float BALL_VOLUME = 0.008F;
	private static final float BALL_SPRAY_SHARE = 0.55F;
	private static final int BALL_CRUMBS = 26;
	private static final int ACTIVE_FRAMES = 240;
	private static final int SPRAY_FRAMES = 600;
	private static final int SAVE_INTERVAL = 30;
	private static final int EDIT_TICKS = 60;
	private static final int PARAM_SLOT = 8192;
	private static final int MAX_SHAPES = 128;
	private static final int COL_SNOW = 0x100;
	private static final int COL_WET = 0x200;
	private static final int COL_PACKED = 0x400;
	private static final int PENDING_TICKS = 60;
	private static final float[] LOD_DISTANCE = {12.0F, 28.0F, 56.0F};
	private static final int MAX_STACK = 4096;
	private static final int SCAN_DEPTH = 48;
	private static final int SOLID_RUN = 3;
	private static final float SIDE_BLEND = 0.6F;

	public static SnowCover get() {
		return INSTANCE;
	}

	static final class Columns {
		final int g;
		final int[] x;
		final int[] z;
		final int[] repY;
		final int[] repBottom;
		final int[] layers;
		final int[] flags;
		final float[] top;
		final float[] firm;
		final float[] base;
		final float[] melt;
		final int[] sides;
		final float[] sideHeight;
		final float[] meltRate;
		final float[] meltLayers;
		final int[] pending;
		final int[] pendingTicks;
		final boolean[] scorching;

		Columns(int g) {
			this.g = g;
			int n = g * g;
			this.x = new int[n];
			this.z = new int[n];
			java.util.Arrays.fill(this.x, Integer.MIN_VALUE);
			java.util.Arrays.fill(this.z, Integer.MIN_VALUE);
			this.repY = new int[n];
			java.util.Arrays.fill(this.repY, Integer.MIN_VALUE);
			this.repBottom = new int[n];
			java.util.Arrays.fill(this.repBottom, Integer.MIN_VALUE);
			this.layers = new int[n];
			this.flags = new int[n];
			this.top = new float[n];
			this.firm = new float[n];
			this.base = new float[n];
			this.melt = new float[n];
			this.sides = new int[n];
			this.sideHeight = new float[n * 4];
			this.meltRate = new float[n];
			this.meltLayers = new float[n];
			this.pending = new int[n];
			this.pendingTicks = new int[n];
			this.scorching = new boolean[n];
		}

		int slot(int bx, int bz) {
			return Math.floorMod(bx, this.g) + Math.floorMod(bz, this.g) * this.g;
		}

		boolean holds(int i, int bx, int bz) {
			return this.x[i] == bx && this.z[i] == bz;
		}
	}

	private volatile boolean active;
	private final SnowSplats splats = new SnowSplats();
	private boolean includeVanilla;
	private boolean failed;
	private @Nullable ClientLevel level;
	private volatile @Nullable Columns cols;
	private int builtRadius = -1;
	private final Long2IntOpenHashMap tileCount = new Long2IntOpenHashMap();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private final LongOpenHashSet melting = new LongOpenHashSet();
	private int refreshCursor;
	private long columnsVersion = 1L;
	private final long[] columnSlotVersion = new long[Vk.FRAME_RING];
	private long columnSlotBytes;
	private long lastLog;

	private @Nullable VkBuf columnBuf;
	private @Nullable VkBuf params;
	private @Nullable VkBuf tileBuf;
	private int tileCapacity;
	private final VkBuf[] detail = new VkBuf[2];
	private int detailCur;
	private @Nullable VkBuf depositBuf;
	private @Nullable VkBuf restBuf;
	private @Nullable VkBuf sprayBuf;
	private boolean sprayCleared;
	private int sprayOriginX;
	private int sprayOriginZ;
	private @Nullable VkBuf spawnBuf;
	private @Nullable VkBuf landedBuf;
	private final VkBuf[] saveBuf = new VkBuf[Vk.FRAME_RING];
	private final int[][] saveMeta = new int[Vk.FRAME_RING][];
	private int saveCounter;
	private int activeFrames;
	private int frameSeed;
	private @Nullable ComputeProgram settle;
	private @Nullable ComputeProgram spray;
	private final Map<Integer, GraphicsProgram> sprayDraws = new HashMap<>();
	private final java.util.ArrayDeque<float[]> spawnQueue = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<double[]> impacts = new java.util.ArrayDeque<>();
	private final List<int[]> adjustQueue = new ArrayList<>();
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<float[]> pendingEdits = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private final it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap landedLayers = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap();
	private int trackN;
	private int trackLoX;
	private int trackLoZ;
	private boolean trackValid;
	private int trackQ;
	private @Nullable ComputeProgram track;
	private final Map<Integer, GraphicsProgram> draws = new HashMap<>();
	private @Nullable Sampler linear;
	private @Nullable Sampler nearest;
	private double time;

	private SnowCover() {
	}

	public boolean active() {
		return this.active;
	}

	public boolean wanted() {
		Columns c = this.cols;
		return this.active && c != null && (!this.tileCount.isEmpty() || !this.splats.isEmpty() || this.activeFrames > 0);
	}

	static void log(String message, Object... args) {
		if (WaterSettings.snowDebugLog) {
			RealisticWater.LOGGER.info("[snow] " + message, args);
		}
	}


	public static boolean isSnowBlock(BlockState state) {
		return state.is(ModContent.REALISTIC_SNOW) || state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK);
	}

	private boolean isSnow(BlockState state) {
		return state.is(ModContent.REALISTIC_SNOW) || this.includeVanilla && (state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK));
	}

	private static int layersOf(BlockState state) {
		if (state.is(Blocks.SNOW)) {
			return state.getValue(SnowLayerBlock.LAYERS);
		}
		if (state.getBlock() instanceof RealisticSnowBlock) {
			return state.getValue(RealisticSnowBlock.LAYERS);
		}
		return 8;
	}

	private static float firmIn(BlockState state, int layers) {
		if (state.is(Blocks.SNOW)) {
			return (layers - 1) / 8.0F;
		}
		if (state.is(Blocks.POWDER_SNOW)) {
			return 0.0F;
		}
		if (state.getBlock() instanceof RealisticSnowBlock) {
			return state.getValue(RealisticSnowBlock.PACKED) ? layers / 8.0F : (float) RealisticSnowBlock.firmHeight(layers);
		}
		return 1.0F;
	}

	public static boolean hides(BlockAndTintGetter at, BlockPos pos) {
		SnowCover c = INSTANCE;
		if (!c.active || at instanceof MovingBlockRenderState) {
			return false;
		}
		Columns cols = c.cols;
		if (cols == null) {
			return false;
		}
		int i = cols.slot(pos.getX(), pos.getZ());
		int y = pos.getY();
		return cols.holds(i, pos.getX(), pos.getZ()) && y <= cols.repY[i] && y >= cols.repBottom[i];
	}


	public void onBlockChanged(ClientLevel level, BlockPos pos) {
		if (!this.active || level != this.level) {
			return;
		}
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				this.dirty.add(BlockPos.asLong(pos.getX() + dx, 0, pos.getZ() + dz));
			}
		}
	}

	public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		if (!this.active || level != this.level) {
			return;
		}
		int x0 = chunk.getPos().getMinBlockX();
		int z0 = chunk.getPos().getMinBlockZ();
		for (int dz = -1; dz <= 16; dz++) {
			for (int dx = -1; dx <= 16; dx++) {
				this.dirty.add(BlockPos.asLong(x0 + dx, 0, z0 + dz));
			}
		}
	}

	public void snowBurst(double x, double y, double z, float vx, float vy, float vz, int face) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel lvl = mc.level;
		if (lvl == null) {
			return;
		}
		Direction dir = face >= 0 && face < 6 ? Direction.from3DDataValue(face) : null;
		RandomSource random = lvl.getRandom();
		double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
		double nx = 0.0;
		double ny = 1.0;
		double nz = 0.0;
		if (dir != null) {
			nx = dir.getStepX();
			ny = dir.getStepY();
			nz = dir.getStepZ();
		} else if (speed > 1.0e-4) {
			nx = -vx / speed;
			ny = -vy / speed;
			nz = -vz / speed;
		}
		if (dir != null && isHot(lvl.getBlockState(BlockPos.containing(x - nx * 0.01, y - ny * 0.01, z - nz * 0.01)))) {
			for (int k = 0; k < 4; k++) {
				lvl.addParticle(ParticleTypes.CLOUD, x + nx * 0.1, y + ny * 0.1, z + nz * 0.1, (random.nextDouble() - 0.5) * 0.04,
					0.03 + random.nextDouble() * 0.04, (random.nextDouble() - 0.5) * 0.04);
			}
			lvl.playLocalSound(x, y, z, net.minecraft.sounds.SoundEvents.FIRE_EXTINGUISH, net.minecraft.sounds.SoundSource.BLOCKS, 0.2F,
				1.8F + random.nextFloat() * 0.4F, false);
			return;
		}

		Columns c = this.active ? this.cols : null;
		boolean onSnowTop = false;
		double surface = y;
		double shift = 0.0;
		if (c != null && dir != null) {
			int bx = Mth.floor(x - nx * 0.01);
			int bz = Mth.floor(z - nz * 0.01);
			int i = c.slot(bx, bz);
			if (c.holds(i, bx, bz) && (c.flags[i] & COL_SNOW) != 0 && y >= c.base[i] - 0.01 && y <= c.top[i] + 0.5) {
				if (dir == Direction.UP) {
					onSnowTop = true;
					surface = Math.max(c.top[i] - c.melt[i] + Math.max(WaterSettings.snowFluff, 0.0F), y);
					shift = surface - y;
				} else {
					shift = 0.06;
				}
			}
		}

		double px = x + nx * 0.1;
		double py = onSnowTop ? surface + 0.1 : y + ny * 0.1;
		double pz = z + nz * 0.1;
		boolean flown = false;
		if (c != null) {
			int gx = Mth.floor(px);
			int gz = Mth.floor(pz);
			int j = c.slot(gx, gz);
			if (c.holds(j, gx, gz)) {
				double ground = (c.flags[j] & COL_SNOW) != 0 ? c.top[j] - c.melt[j] + Math.max(WaterSettings.snowFluff, 0.0F) : c.top[j];
				flown = py > ground + 0.02;
			}
		}
		if (flown) {
			double into = Math.max(-(vx * nx + vy * ny + vz * nz), 0.0);
			double ax = vx + into * nx;
			double ay = vy + into * ny;
			double az = vz + into * nz;
			float each = BALL_VOLUME * BALL_SPRAY_SHARE / BALL_CRUMBS;
			for (int k = 0; k < BALL_CRUMBS; k++) {
				double out = (1.2 + 2.8 * random.nextDouble()) * (0.5 + Math.min(into, 1.5));
				double keep = 20.0 * (0.12 + 0.3 * random.nextDouble());
				double sx = ax * keep + nx * out + (random.nextDouble() - 0.5) * 2.4;
				double sy = ay * keep + ny * out + (random.nextDouble() - 0.5) * 2.4;
				double sz = az * keep + nz * out + (random.nextDouble() - 0.5) * 2.4;
				double away = sx * nx + sy * ny + sz * nz;
				if (away < 0.4) {
					sx += nx * (0.4 - away);
					sy += ny * (0.4 - away);
					sz += nz * (0.4 - away);
				}
				this.spawnQueue.add(new float[]{(float) (px + (random.nextDouble() - 0.5) * 0.1), (float) (py + (random.nextDouble() - 0.5) * 0.1),
					(float) (pz + (random.nextDouble() - 0.5) * 0.1), (float) sx, (float) sy, (float) sz, each});
			}
			this.activeFrames = Math.max(this.activeFrames, SPRAY_FRAMES);
		}
		for (int k = 0; k < (flown ? 3 : 10); k++) {
			double sx = -vx * 0.15 + (random.nextDouble() - 0.5) * 0.25;
			double sy = 0.05 + random.nextDouble() * 0.12 + speed * 0.05;
			double sz = -vz * 0.15 + (random.nextDouble() - 0.5) * 0.25;
			lvl.addParticle(k % 3 == 0 ? ParticleTypes.ITEM_SNOWBALL : ParticleTypes.SNOWFLAKE, x + nx * 0.08, y + ny * 0.08, z + nz * 0.08, sx, sy, sz);
		}
		if (dir == null || !this.active) {
			return;
		}
		Vec3 cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		int trackRadius = Math.max(2, WaterSettings.snowTrackRadius);
		if (onSnowTop && WaterSettings.snowTracks && Math.abs(x - cam.x) < trackRadius - 1 && Math.abs(z - cam.z) < trackRadius - 1) {
			if (this.impacts.size() < 64) {
				this.impacts.add(new double[]{x, surface, z, vx * 20.0, vz * 20.0, speed});
			}
			this.activeFrames = Math.max(this.activeFrames, ACTIVE_FRAMES);
			log("snowball crater at {} {} {}", x, surface, z);
			return;
		}
		this.splats.add(lvl, x, y, z, dir, vx, vy, vz, shift);
		log("snowball splat on {} at {} {} {}", dir, x, y, z);
	}

	private static boolean isHot(BlockState state) {
		return state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || CampfireBlock.isLitCampfire(state) || state.is(Blocks.MAGMA_BLOCK)
			|| state.getBlock() instanceof AbstractFurnaceBlock && state.getValue(AbstractFurnaceBlock.LIT) || me.skaffy.block.HotPlateBlock.isHot(state);
	}


	public void tick(Minecraft mc) {
		boolean want = !this.failed && Vk.tryDevice() != null && !WaterWorld.get().drawingFailed();
		boolean vanilla = WaterSettings.realisticSnow;
		ClientLevel lvl = mc.level;
		if (lvl != this.level) {
			this.level = lvl;
			this.resetColumns();
		}
		int radius = Math.max(4, WaterSettings.snowRadius);
		if (want != this.active || want && (radius != this.builtRadius || vanilla != this.includeVanilla)) {
			this.active = want;
			this.builtRadius = radius;
			this.includeVanilla = vanilla;
			this.resetColumns();
			mc.levelExtractor.allChanged();
			log("snow drawing {} (radius {}, vanilla snow {})", want ? "on" : "off", radius, vanilla ? "included" : "left vanilla");
		}
		if (!this.active || lvl == null || mc.getCameraEntity() == null) {
			return;
		}
		Columns c = this.cols;
		if (c == null) {
			c = new Columns(2 * (radius + MARGIN) + 4);
			this.cols = c;
		}
		Vec3 cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		int cx = Mth.floor(cam.x);
		int cz = Mth.floor(cam.z);
		int reach = radius + MARGIN;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int budget = SCANS_PER_TICK;
		int found = 0;
		for (int dz = -reach; dz <= reach && budget > 0; dz++) {
			for (int dx = -reach; dx <= reach && budget > 0; dx++) {
				int x = cx + dx;
				int z = cz + dz;
				int i = c.slot(x, z);
				if (!c.holds(i, x, z)) {
					this.scan(lvl, c, i, x, z, pos);
					budget--;
					found++;
				}
			}
		}
		int handled = 0;
		for (LongIterator it = this.dirty.iterator(); it.hasNext() && handled < DIRTY_PER_TICK; ) {
			long key = it.nextLong();
			it.remove();
			int x = BlockPos.getX(key);
			int z = BlockPos.getZ(key);
			if (Math.abs(x - cx) <= reach && Math.abs(z - cz) <= reach) {
				this.scan(lvl, c, c.slot(x, z), x, z, pos);
				handled++;
			}
		}
		int n = c.g * c.g;
		for (int k = 0; k < REFRESH_PER_TICK; k++) {
			int i = this.refreshCursor++ % n;
			if (c.x[i] != Integer.MIN_VALUE && Math.abs(c.x[i] - cx) <= reach && Math.abs(c.z[i] - cz) <= reach) {
				this.scan(lvl, c, i, c.x[i], c.z[i], pos);
			}
		}
		this.refreshCursor %= n;
		this.tickMelting(mc, lvl, c, pos);
		this.splats.tick(lvl);
		if (!mc.isPaused()) {
			RandomSource shed = lvl.getRandom();
			for (Entity e : lvl.entitiesForRendering()) {
				if (e instanceof me.skaffy.snow.RealisticSnowballEntity && shed.nextInt(2) == 0 && this.spawnQueue.size() < SPAWN_MAX) {
					Vec3 v = e.getDeltaMovement();
					this.spawnQueue.add(new float[]{(float) e.getX(), (float) (e.getY() + 0.12), (float) e.getZ(),
						(float) (v.x * 7.0 + (shed.nextDouble() - 0.5) * 0.6), (float) (v.y * 7.0 + (shed.nextDouble() - 0.5) * 0.6),
						(float) (v.z * 7.0 + (shed.nextDouble() - 0.5) * 0.6), BALL_VOLUME * 0.004F});
					this.activeFrames = Math.max(this.activeFrames, 120);
				}
			}
		}
		for (var it = this.pendingEdits.values().iterator(); it.hasNext(); ) {
			float[] edit = it.next();
			if (--edit[1] <= 0.0F) {
				it.remove();
			}
		}
		if (found > 0 && lvl.getGameTime() - this.lastLog > 100) {
			this.lastLog = lvl.getGameTime();
			log("scanned {} new columns, {} snow tiles, {} melting", found, this.tileCount.size(), this.melting.size());
		}
	}

	private void resetColumns() {
		this.cols = null;
		this.tileCount.clear();
		this.dirty.clear();
		this.melting.clear();
		this.trackValid = false;
		this.columnsVersion++;
		this.spawnQueue.clear();
		this.impacts.clear();
		this.adjustQueue.clear();
		this.pendingEdits.clear();
		this.landedLayers.clear();
		this.activeFrames = 0;
		this.sprayCleared = false;
	}

	private static long tileKey(int x, int z) {
		return BlockPos.asLong(Math.floorDiv(x, TILE), 0, Math.floorDiv(z, TILE));
	}

	private void scan(ClientLevel lvl, Columns c, int i, int x, int z, BlockPos.MutableBlockPos pos) {
		boolean same = c.holds(i, x, z);
		long before = fingerprint(c, i);
		int oldRep = c.repY[i];
		int oldBottom = c.repBottom[i];
		int oldLayers = c.layers[i];
		if (!same && oldRep != Integer.MIN_VALUE) {
			this.markDirty(c.x[i], oldBottom, oldRep, c.z[i]);
			this.tileCount.addTo(tileKey(c.x[i], c.z[i]), -1);
			if (this.tileCount.get(tileKey(c.x[i], c.z[i])) <= 0) {
				this.tileCount.remove(tileKey(c.x[i], c.z[i]));
			}
			this.melting.remove(BlockPos.asLong(c.x[i], 0, c.z[i]));
		}
		c.x[i] = x;
		c.z[i] = z;
		int rep = Integer.MIN_VALUE;
		int bottom = Integer.MIN_VALUE;
		int layers = 0;
		int flags = 0;
		float top = lvl.getMinY();
		float firm = top;
		float base = top;
		if (lvl.hasChunk(x >> 4, z >> 4)) {
			int surface = lvl.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
			int y = surface - 1;
			BlockState state = lvl.getBlockState(pos.set(x, y, z));
			int snowY = this.findSnowTop(lvl, x, y, z, pos);
			if (snowY != Integer.MIN_VALUE) {
				y = snowY;
				state = lvl.getBlockState(pos.set(x, y, z));
			}
			pos.set(x, y, z);
			int light = Math.max(lvl.getBrightness(LightLayer.BLOCK, pos), lvl.getBrightness(LightLayer.BLOCK, pos.above()))
				| Math.max(lvl.getBrightness(LightLayer.SKY, pos), lvl.getBrightness(LightLayer.SKY, pos.above())) << 4;
			if (this.isSnow(state)) {
				bottom = y;
				while (y - bottom < MAX_STACK - 1 && this.isSnow(lvl.getBlockState(pos.set(x, bottom - 1, z)))) {
					bottom--;
				}
				pos.set(x, y, z);
				layers = layersOf(state);
				rep = y;
				base = bottom;
				top = y + layers / 8.0F;
				firm = y + firmIn(state, layers);
				flags = COL_SNOW | light;
				if (state.getBlock() instanceof RealisticSnowBlock && state.getValue(RealisticSnowBlock.PACKED)) {
					flags |= COL_PACKED;
				}
				this.classifySides(lvl, c, i, x, y, z, bottom, top, pos);
				pos.set(x, y, z);
				this.checkHeat(lvl, c, i, x, y, z, layers, pos);
				if (c.meltRate[i] > 0.0F) {
					this.melting.add(BlockPos.asLong(x, 0, z));
				} else {
					this.melting.remove(BlockPos.asLong(x, 0, z));
				}
			} else {
				var shape = state.getCollisionShape(lvl, pos);
				FluidState fluid = state.getFluidState();
				double height = Math.max(shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y), fluid.isEmpty() ? 0.0 : fluid.getHeight(lvl, pos));
				top = (float) (y + (state.isAir() ? 0.0 : height));
				firm = top;
				base = top;
				flags = light;
				this.melting.remove(BlockPos.asLong(x, 0, z));
			}
		}
		if (same && rep == oldRep && rep != Integer.MIN_VALUE) {
			if (layers < oldLayers) {
				c.pending[i] = Math.max(0, c.pending[i] - (oldLayers - layers));
				c.meltLayers[i] = Math.min(c.meltLayers[i], layers);
			}
		} else {
			c.meltLayers[i] = 0.0F;
			c.pending[i] = 0;
		}
		if (rep == Integer.MIN_VALUE) {
			c.meltRate[i] = 0.0F;
			c.meltLayers[i] = 0.0F;
			c.pending[i] = 0;
		}
		if (c.meltRate[i] > 0.0F && c.meltRate[i] >= WaterSettings.snowMeltWater && (flags & COL_SNOW) != 0) {
			flags |= COL_WET;
		}
		boolean wasRep = same && oldRep != Integer.MIN_VALUE;
		if (rep != oldRep || bottom != oldBottom || !same) {
			if (wasRep) {
				this.markDirty(x, oldBottom, oldRep, z);
				this.tileCount.addTo(tileKey(x, z), -1);
				if (this.tileCount.get(tileKey(x, z)) <= 0) {
					this.tileCount.remove(tileKey(x, z));
				}
			}
			if (rep != Integer.MIN_VALUE) {
				this.markDirty(x, bottom, rep, z);
				this.tileCount.addTo(tileKey(x, z), 1);
			}
		}
		c.repY[i] = rep;
		c.repBottom[i] = bottom;
		c.layers[i] = layers;
		c.flags[i] = flags;
		c.top[i] = top;
		c.firm[i] = firm;
		c.base[i] = base;
		c.melt[i] = rep == Integer.MIN_VALUE ? 0.0F : Math.min((c.meltLayers[i] + c.pending[i]) / 8.0F, layers / 8.0F);
		if (rep == Integer.MIN_VALUE) {
			c.sides[i] = 0;
			java.util.Arrays.fill(c.sideHeight, i * 4, i * 4 + 4, 0.0F);
		}
		long editKey = BlockPos.asLong(x, 0, z);
		float[] edit = this.pendingEdits.get(editKey);
		if (edit != null && (rep == Integer.MIN_VALUE || Math.abs(top - edit[0]) > 1.0e-4F)) {
			this.pendingEdits.remove(editKey);
			if (rep != Integer.MIN_VALUE) {
				this.adjustQueue.add(new int[]{x, z, Float.floatToRawIntBits(top - edit[0])});
			}
		}
		if (!same || fingerprint(c, i) != before) {
			this.columnsVersion++;
		}
	}

	private static long fingerprint(Columns c, int i) {
		long h = c.flags[i] * 31L + c.sides[i];
		h = h * 31L + Float.floatToRawIntBits(c.top[i]);
		h = h * 31L + Float.floatToRawIntBits(c.firm[i]);
		h = h * 31L + Float.floatToRawIntBits(c.base[i]);
		h = h * 31L + Float.floatToRawIntBits(c.melt[i]);
		for (int k = 0; k < 4; k++) {
			h = h * 31L + Float.floatToRawIntBits(c.sideHeight[i * 4 + k]);
		}
		return h;
	}

	private int findSnowTop(ClientLevel lvl, int x, int startY, int z, BlockPos.MutableBlockPos pos) {
		int solid = 0;
		for (int y = startY; y >= Math.max(lvl.getMinY(), startY - SCAN_DEPTH); y--) {
			BlockState state = lvl.getBlockState(pos.set(x, y, z));
			if (this.isSnow(state)) {
				return y;
			}
			if (state.isSolidRender()) {
				if (++solid >= SOLID_RUN) {
					return Integer.MIN_VALUE;
				}
			} else {
				solid = 0;
			}
		}
		return Integer.MIN_VALUE;
	}

	private void classifySides(ClientLevel lvl, Columns c, int i, int x, int y, int z, int bottom, float top, BlockPos.MutableBlockPos pos) {
		int kinds = 0;
		for (int side = 0; side < 4; side++) {
			int nx = x + (side == 0 ? -1 : side == 1 ? 1 : 0);
			int nz = z + (side == 2 ? -1 : side == 3 ? 1 : 0);
			int kind;
			float height;
			BlockState above = lvl.getBlockState(pos.set(nx, y + 1, nz));
			if (this.isSnow(above)) {
				int ny = y + 1;
				while (ny - y < MAX_STACK && this.isSnow(lvl.getBlockState(pos.set(nx, ny + 1, nz)))) {
					ny++;
				}
				height = ny + layersOf(lvl.getBlockState(pos.set(nx, ny, nz))) / 8.0F;
				kind = height - top <= SIDE_BLEND ? 0 : 1;
			} else if (!above.getCollisionShape(lvl, pos.set(nx, y + 1, nz)).isEmpty() && above.isCollisionShapeFullBlock(lvl, pos)) {
				kind = 1;
				height = y + 2;
			} else {
				kind = 2;
				height = bottom;
				for (int ny = y; ny >= bottom - 1; ny--) {
					BlockState s = lvl.getBlockState(pos.set(nx, ny, nz));
					if (this.isSnow(s)) {
						height = ny + layersOf(s) / 8.0F;
						kind = Math.abs(height - top) <= SIDE_BLEND ? 0 : height > top ? 1 : 2;
						break;
					}
					var shape = s.getCollisionShape(lvl, pos);
					FluidState fluid = s.getFluidState();
					double h = Math.max(shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y), fluid.isEmpty() ? 0.0 : fluid.getHeight(lvl, pos));
					if (h > 0.0) {
						height = (float) (ny + h);
						kind = height >= top - 0.02F ? 1 : 2;
						break;
					}
				}
				height = Math.max(height, bottom);
			}
			kinds |= kind << (side * 2);
			c.sideHeight[i * 4 + side] = height;
		}
		c.sides[i] = kinds;
	}

	private void markDirty(int x, int y0, int y1, int z) {
		Minecraft.getInstance().levelExtractor.setBlocksDirty(x, y0, z, x, y1, z);
	}

	private void checkHeat(ClientLevel lvl, Columns c, int i, int x, int y, int z, int layers, BlockPos.MutableBlockPos pos) {
		c.meltRate[i] = 0.0F;
		c.scorching[i] = false;
		if (!WaterSettings.snowMelting) {
			return;
		}
		float heat = 0.0F;
		boolean water = false;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;
					}
					BlockState s = lvl.getBlockState(pos.set(x + dx, y + dy, z + dz));
					if (s.isAir()) {
						continue;
					}
					FluidState fluid = s.getFluidState();
					boolean side = Math.abs(dx) + Math.abs(dz) <= 1;
					if (fluid.is(FluidTags.LAVA) || s.is(BlockTags.FIRE) || CampfireBlock.isLitCampfire(s)) {
						heat = Math.max(heat, side ? 1.0F : 0.5F);
						c.scorching[i] = true;
					} else if (s.is(Blocks.MAGMA_BLOCK) || s.getBlock() instanceof AbstractFurnaceBlock && s.getValue(AbstractFurnaceBlock.LIT)) {
						heat = Math.max(heat, side ? 1.0F / 3.0F : 1.0F / 6.0F);
					} else if (me.skaffy.block.HotPlateBlock.isHot(s)) {
						heat = Math.max(heat, side ? 0.5F : 0.25F);
					} else if (me.skaffy.client.water.WaterRegion.conductsHeat(s)) {
						int code = me.skaffy.client.water.WaterRegion.heatCodeAt(lvl, pos.immutable());
						float carried = code == 2 || code == 3 || code == 7 ? 0.4F : code == 1 || code == 4 ? 0.15F : 0.0F;
						heat = Math.max(heat, side ? carried : carried * 0.5F);
					} else if (fluid.is(FluidTags.WATER) && side && dy >= 0) {
						water = true;
					}
				}
			}
		}
		FluidState inside = lvl.getBlockState(pos.set(x, y, z)).getFluidState();
		if (inside.is(FluidTags.WATER)) {
			water = true;
		}
		float rate = heat * Math.max(WaterSettings.snowMeltHeat, 0.0F);
		if (water) {
			rate += Math.max(WaterSettings.snowMeltWater, 0.0F);
		}
		int light = lvl.getBrightness(LightLayer.BLOCK, pos.set(x, y + 1, z));
		int level = Math.clamp(WaterSettings.snowMeltLightLevel, 1, 16);
		if (light >= level) {
			rate += Math.max(WaterSettings.snowMeltLight, 0.0F) * (1 + light - level);
		}
		c.meltRate[i] = rate;
	}

	private void tickMelting(Minecraft mc, ClientLevel lvl, Columns c, BlockPos.MutableBlockPos pos) {
		if (this.melting.isEmpty() || mc.isPaused()) {
			return;
		}
		boolean host = mc.getSingleplayerServer() != null;
		boolean send = host && ClientPlayNetworking.canSend(SnowMeltPayload.TYPE);
		LongArrayList positions = new LongArrayList();
		ByteArrayList lost = new ByteArrayList();
		for (LongIterator it = this.melting.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			int x = BlockPos.getX(key);
			int z = BlockPos.getZ(key);
			int i = c.slot(x, z);
			if (!c.holds(i, x, z) || c.repY[i] == Integer.MIN_VALUE || c.meltRate[i] <= 0.0F) {
				it.remove();
				continue;
			}
			float cap = host ? c.layers[i] - c.pending[i] : Math.min(0.9F, c.layers[i]);
			c.meltLayers[i] = Math.min(c.meltLayers[i] + c.meltRate[i] * 0.05F, cap);
			if (c.pending[i] > 0 && --c.pendingTicks[i] <= 0) {
				c.pending[i] = 0;
			}
			int whole = (int) c.meltLayers[i];
			if (whole >= 1 && send && positions.size() < SnowMeltPayload.MAX_ENTRIES) {
				int y = c.repY[i];
				positions.add(BlockPos.asLong(x, y, z));
				lost.add((byte) whole);
				c.meltLayers[i] -= whole;
				c.pending[i] += whole;
				c.pendingTicks[i] = PENDING_TICKS;
				this.meltOff(lvl, c, i, x, y, z, whole);
			}
			c.melt[i] = Math.min((c.meltLayers[i] + c.pending[i]) / 8.0F, c.layers[i] / 8.0F);
			this.columnsVersion++;
		}
		if (!positions.isEmpty()) {
			ClientPlayNetworking.send(new SnowMeltPayload(positions.toLongArray(), lost.toByteArray()));
			log("melted {} snow blocks by a layer or more", positions.size());
		}
	}

	private void meltOff(ClientLevel lvl, Columns c, int i, int x, int y, int z, int layers) {
		if (c.scorching[i]) {
			WaterWorld.get().addSteam(x + 0.5, c.top[i], z + 0.5, layers * 0.5F);
			return;
		}
		float water = layers * Math.max(WaterSettings.snowMeltWaterYield, 0.0F) / 8.0F;
		if (water > 0.01F) {
			double y0 = Math.clamp((c.layers[i] - c.pending[i]) / 8.0, 0.0, 1.0);
			double y1 = Math.min(y0 + water, 1.0);
			if (y1 - y0 > 0.01) {
				WaterWorld.get().addWater(new BlockPos(x, y, z), y0, y1);
				log("meltwater {} blocks at {} {} {}", y1 - y0, x, y, z);
			}
		}
	}


	private void ensureGpu() {
		Columns c = this.cols;
		long colBytes = (long) c.g * c.g * COLUMN_BYTES;
		if (this.columnBuf == null || this.columnSlotBytes != colBytes) {
			if (this.columnBuf != null) {
				Vk.destroyLater(this.columnBuf);
			}
			this.columnSlotBytes = colBytes;
			this.columnBuf = VkBuf.upload(colBytes * Vk.FRAME_RING, VkBuf.STORAGE);
			java.util.Arrays.fill(this.columnSlotVersion, 0L);
		}
		if (this.params == null) {
			this.params = VkBuf.upload((long) PARAM_SLOT * Vk.FRAME_RING, VkBuf.STORAGE);
		}
		int tilesAcross = c.g / TILE + 2;
		int wantTiles = tilesAcross * tilesAcross;
		if (this.tileBuf == null || this.tileCapacity < wantTiles) {
			if (this.tileBuf != null) {
				Vk.destroyLater(this.tileBuf);
			}
			this.tileCapacity = wantTiles;
			this.tileBuf = VkBuf.upload((long) wantTiles * 16L * Vk.FRAME_RING, VkBuf.STORAGE);
		}
		if (this.track == null) {
			this.track = new ComputeProgram("snow_track.comp", Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO);
			this.linear = new Sampler(true);
			this.nearest = new Sampler(false);
		}
	}

	private Desc uploadColumns(int slot) {
		Columns c = this.cols;
		long base = slot * this.columnSlotBytes;
		if (c != null && this.columnSlotVersion[slot] != this.columnsVersion) {
			this.columnSlotVersion[slot] = this.columnsVersion;
			ByteBuffer m = this.columnBuf.mapped();
			int n = c.g * c.g;
			for (int i = 0; i < n; i++) {
				int o = (int) (base + (long) i * COLUMN_BYTES);
				m.putInt(o, c.x[i]).putInt(o + 4, c.z[i]).putInt(o + 8, c.flags[i]).putInt(o + 12, c.sides[i]);
				m.putFloat(o + 16, c.top[i]).putFloat(o + 20, c.firm[i]).putFloat(o + 24, c.base[i]).putFloat(o + 28, c.melt[i]);
				m.putFloat(o + 32, c.sideHeight[i * 4]).putFloat(o + 36, c.sideHeight[i * 4 + 1]).putFloat(o + 40, c.sideHeight[i * 4 + 2])
					.putFloat(o + 44, c.sideHeight[i * 4 + 3]);
			}
			this.columnBuf.flush(base, this.columnSlotBytes);
		}
		return Desc.ssbo(this.columnBuf, base, this.columnSlotBytes);
	}

	private GraphicsProgram draw(int colorFormat) {
		return this.draws.computeIfAbsent(colorFormat, format -> new GraphicsProgram("snow.vert", "snow.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO,
			Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.TEX, Desc.TEX));
	}

	public void record(VkCommandBuffer cb, Desc camera, int width, int height, int colorFormat, long colorView, long depthView, long lightmap,
		long packShadowView, float frameDt) {
		Columns c = this.cols;
		ClientLevel lvl = this.level;
		if (!this.active || c == null || lvl == null || this.tileCount.isEmpty() && this.splats.isEmpty() && this.activeFrames <= 0) {
			return;
		}
		try {
			this.recordUnsafe(cb, camera, width, height, colorFormat, colorView, depthView, lightmap, packShadowView, frameDt, c, lvl);
		} catch (RuntimeException e) {
			this.failed = true;
			this.active = false;
			Minecraft.getInstance().levelExtractor.allChanged();
			RealisticWater.LOGGER.error("Realistic snow failed and was turned off; vanilla snow is drawn again", e);
		}
	}

	private void recordUnsafe(VkCommandBuffer cb, Desc camera, int width, int height, int colorFormat, long colorView, long depthView, long lightmap,
		long packShadowView, float frameDt, Columns c, ClientLevel lvl) {
		this.ensureGpu();

		Minecraft mc = Minecraft.getInstance();
		LevelRenderState levelState = mc.gameRenderer.gameRenderState().levelRenderState;
		CameraRenderState cameraState = levelState.cameraRenderState;
		Vec3 cam = cameraState.pos;
		int bx = Mth.floor(cam.x);
		int by = Mth.floor(cam.y);
		int bz = Mth.floor(cam.z);
		float partial = levelState.worldPartialTicks;
		this.time = (this.time + frameDt) % 3600.0;
		int slot = Vk.ringSlot();

		int q = Math.clamp(WaterSettings.snowDetail, 1, 32);
		int trackRadius = Math.max(2, WaterSettings.snowTrackRadius);
		int n = 2 * trackRadius * q + 4;
		boolean tracks = WaterSettings.snowTracks;
		this.ensureMoving(n, q);
		this.readLanded(slot, lvl, c, bx, by, bz);
		this.readSave(slot, lvl, c);
		int loX = (bx - trackRadius) * q - 2;
		int loZ = (bz - trackRadius) * q - 2;
		List<float[]> shapes = tracks ? this.shapes(mc, lvl, c, cam, partial, trackRadius, bx, by, bz) : new ArrayList<>();
		if (!tracks) {
			this.impacts.clear();
		} else if (!mc.isPaused()) {
			while (!this.impacts.isEmpty() && shapes.size() < MAX_SHAPES) {
				double[] hit = this.impacts.poll();
				float radius = 0.17F;
				float depth = Math.clamp(0.06F + 0.05F * (float) hit[5], 0.06F, 0.14F);
				float lx = (float) (hit[0] - bx);
				float lz = (float) (hit[2] - bz);
				float centre = (float) (hit[1] - by) - depth + radius;
				shapes.add(new float[]{lx, lz, lx, lz, radius, centre, centre, 0.0F, 2.0F, 14.0F, (float) hit[3], (float) hit[4]});
			}
		}
		boolean snowing = lvl.isRaining() && lvl.getPrecipitationAt(BlockPos.containing(cam)) == net.minecraft.world.level.biome.Biome.Precipitation.SNOW;
		float refill = snowing ? Math.max(WaterSettings.snowRefill, 0.0F) * lvl.getRainLevel(1.0F) * frameDt : 0.0F;
		boolean moved = loX != this.trackLoX || loZ != this.trackLoZ || !this.trackValid;
		if (!this.sprayCleared || Math.abs(bx - this.sprayOriginX) > 4096 || Math.abs(bz - this.sprayOriginZ) > 4096) {
			this.sprayOriginX = bx;
			this.sprayOriginZ = bz;
			this.sprayBuf.fill(cb, Float.floatToRawIntBits(-1.0F));
			Vk.barrier(cb);
			this.sprayCleared = true;
		}
		int spawnCount = this.writeSpawns(slot, q, bx, by, bz);
		List<int[]> adjusts = new ArrayList<>(this.adjustQueue.subList(0, Math.min(this.adjustQueue.size(), MAX_ADJUST)));
		this.adjustQueue.subList(0, adjusts.size()).clear();
		if (!shapes.isEmpty() || refill > 0.0F || moved || !adjusts.isEmpty()) {
			this.activeFrames = Math.max(this.activeFrames, ACTIVE_FRAMES);
		}
		if (spawnCount > 0) {
			this.activeFrames = Math.max(this.activeFrames, SPRAY_FRAMES);
		}
		boolean run = this.activeFrames > 0 && !mc.isPaused();
		if (run) {
			this.activeFrames--;
		}
		float dt = Math.min(frameDt, 0.05F);

		ByteBuffer b = this.params.mapped();
		int o = slot * PARAM_SLOT;
		b.putInt(o, c.g).putInt(o + 4, bx).putInt(o + 8, bz).putInt(o + 12, q);
		b.putInt(o + 16, n).putInt(o + 20, loX).putInt(o + 24, loZ).putInt(o + 28, Math.min(shapes.size(), MAX_SHAPES));
		b.putInt(o + 32, this.trackLoX).putInt(o + 36, this.trackLoZ).putInt(o + 40, this.trackValid ? 1 : 0).putInt(o + 44, 0);
		putFloats(b, o + 48, Math.max(WaterSettings.snowFluff, 0.0F), Math.max(WaterSettings.snowDrift, 0.0F), Mth.clamp(WaterSettings.snowEdgeRounding, 0.0F, 0.5F),
			Math.max(WaterSettings.snowBrightness, 0.0F));
		putFloats(b, o + 64, Math.max(WaterSettings.snowSparkle, 0.0F), Mth.clamp(WaterSettings.snowShadowTint, 0.0F, 1.0F),
			Mth.clamp(WaterSettings.snowPackedTint, 0.0F, 1.0F), (float) this.time);
		putFloats(b, o + 80, 0.0F, refill, by, 0.0F);
		putFloats(b, o + 96, Math.floorMod(bx, 4096), Math.floorMod(bz, 4096), Mth.clamp(lvl.getRainLevel(partial), 0.0F, 1.0F), 0.0F);
		for (int k = 0; k < Math.min(shapes.size(), MAX_SHAPES); k++) {
			float[] s = shapes.get(k);
			int so = o + 112 + k * 48;
			putFloats(b, so, s[0], s[1], s[2], s[3]);
			putFloats(b, so + 16, s[4], s[5], s[6], s[7]);
			putFloats(b, so + 32, s[8], s[9], s[10], s[11]);
		}
		int eo = o + 112 + MAX_SHAPES * 48;
		b.putInt(eo, adjusts.size()).putInt(eo + 4, spawnCount).putInt(eo + 8, this.frameSeed++).putInt(eo + 12, SPRAY_CAPACITY);
		float repose = (float) Math.tan(Math.toRadians(Mth.clamp(WaterSettings.snowRepose, 5.0F, 80.0F))) / q;
		putFloats(b, eo + 16, dt, SPRAY_GRAVITY, repose, Mth.clamp(WaterSettings.snowCompress, 0.0F, 1.0F));
		b.putInt(eo + 32, this.sprayOriginX).putInt(eo + 36, this.sprayOriginZ).putInt(eo + 40, 0).putInt(eo + 44, 0);
		for (int k = 0; k < adjusts.size(); k++) {
			int[] a = adjusts.get(k);
			b.putInt(eo + 48 + k * 16, a[0]).putInt(eo + 52 + k * 16, a[1]).putInt(eo + 56 + k * 16, a[2]).putInt(eo + 60 + k * 16, 0);
		}
		this.params.flush(o, PARAM_BYTES);
		Desc params = Desc.ssbo(this.params, o, PARAM_BYTES);
		Desc columns = this.uploadColumns(slot);
		Desc landed = Desc.ssbo(this.landedBuf, (long) slot * LANDED_SLOT, LANDED_SLOT);
		Desc spawn = Desc.ssbo(this.spawnBuf, (long) slot * SPAWN_SLOT, SPAWN_SLOT);
		Desc deposit = Desc.ssbo(this.depositBuf);
		Desc rest = Desc.ssbo(this.restBuf);
		Desc sprayDesc = Desc.ssbo(this.sprayBuf);

		Vk.barrier(cb);
		if (run) {
			int groups = ComputeProgram.groups((long) n * n, 256);
			this.track.bind(cb, params, columns, Desc.ssbo(this.detail[this.detailCur]), deposit, rest, sprayDesc);
			this.track.dispatch(cb, groups, 1, 1);
			Vk.barrier(cb);
			this.trackLoX = loX;
			this.trackLoZ = loZ;
			this.trackValid = true;
			this.spray.bind(cb, params, columns, Desc.ssbo(this.detail[this.detailCur]), deposit, sprayDesc, spawn, landed);
			if (spawnCount > 0) {
				this.spray.push(cb, 0);
				this.spray.dispatch(cb, ComputeProgram.groups(spawnCount, 256), 1, 1);
				Vk.barrier(cb);
			}
			this.spray.push(cb, 1);
			this.spray.dispatch(cb, ComputeProgram.groups(SPRAY_CAPACITY, 256), 1, 1);
			Vk.barrier(cb);
			int next = 1 - this.detailCur;
			this.settle.bind(cb, params, columns, Desc.ssbo(this.detail[this.detailCur]), Desc.ssbo(this.detail[next]), deposit, rest, landed);
			this.settle.dispatch(cb, groups, 1, 1);
			Vk.barrier(cb);
			this.detailCur = next;
			if (mc.getSingleplayerServer() != null && WaterSettings.snowSaveTracks && ++this.saveCounter >= SAVE_INTERVAL) {
				this.saveCounter = 0;
				this.detail[this.detailCur].copyTo(cb, this.saveBuf[slot], 0, 0, (long) n * n * 4L);
				this.saveMeta[slot] = new int[]{loX, loZ, n, q, bx, by, bz};
				Vk.barrier(cb);
			}
		}
		Desc detail = Desc.ssbo(this.detail[this.detailCur]);

		int[] levels = lodLevels(q);
		List<List<int[]>> byLevel = new ArrayList<>();
		for (int k = 0; k < levels.length; k++) {
			byLevel.add(new ArrayList<>());
		}
		Frustum frustum = cameraState.cullFrustum;
		int reach = Math.max(4, WaterSettings.snowRadius) + MARGIN;
		for (Long2IntOpenHashMap.Entry e : this.tileCount.long2IntEntrySet()) {
			if (e.getIntValue() <= 0) {
				continue;
			}
			long key = e.getLongKey();
			int tx = BlockPos.getX(key) * TILE;
			int tz = BlockPos.getZ(key) * TILE;
			if (tx + TILE < bx - reach || tx > bx + reach || tz + TILE < bz - reach || tz > bz + reach) {
				continue;
			}
			float minY = Float.MAX_VALUE;
			float maxY = -Float.MAX_VALUE;
			for (int z = tz; z < tz + TILE; z++) {
				for (int x = tx; x < tx + TILE; x++) {
					int i = c.slot(x, z);
					if (c.holds(i, x, z) && (c.flags[i] & COL_SNOW) != 0) {
						minY = Math.min(minY, c.base[i]);
						maxY = Math.max(maxY, c.top[i]);
					}
				}
			}
			if (minY > maxY) {
				continue;
			}
			AABB box = new AABB(tx - 1, minY - 0.5, tz - 1, tx + TILE + 1, maxY + 1.5, tz + TILE + 1);
			if (frustum != null && !frustum.isVisible(box)) {
				continue;
			}
			double ddx = Math.max(Math.max(tx - cam.x, cam.x - (tx + TILE)), 0.0);
			double ddz = Math.max(Math.max(tz - cam.z, cam.z - (tz + TILE)), 0.0);
			double ddy = Math.max(Math.max(minY - cam.y, cam.y - maxY), 0.0);
			double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
			int level = levels.length - 1;
			for (int k = 0; k < levels.length - 1; k++) {
				if (dist < LOD_DISTANCE[Math.min(k, LOD_DISTANCE.length - 1)]) {
					level = k;
					break;
				}
			}
			byLevel.get(level).add(new int[]{tx - bx, tz - bz});
		}
		ByteBuffer tb = this.tileBuf.mapped();
		long tileBase = (long) slot * this.tileCapacity * 16L;
		int written = 0;
		int[] first = new int[levels.length];
		int[] count = new int[levels.length];
		for (int k = 0; k < levels.length; k++) {
			first[k] = written;
			for (int[] t : byLevel.get(k)) {
				if (written >= this.tileCapacity) {
					break;
				}
				int to = (int) (tileBase + written * 16L);
				tb.putInt(to, t[0]).putInt(to + 4, t[1]).putInt(to + 8, levels[k]).putInt(to + 12, 0);
				written++;
			}
			count[k] = written - first[k];
		}
		Desc light = Desc.tex(lightmap, this.linear.handle);
		if (written > 0) {
			this.tileBuf.flush(tileBase, written * 16L);
			GraphicsProgram p = this.draw(colorFormat);
			Desc tiles = Desc.ssbo(this.tileBuf, tileBase, (long) this.tileCapacity * 16L);
			Desc shadow = Desc.tex(packShadowView != 0L ? packShadowView : lightmap, this.nearest.handle);
			GraphicsProgram.begin(cb, width, height, colorView, null, depthView);
			p.bind(cb, camera, params, columns, detail, tiles, light, shadow);
			for (int k = 0; k < levels.length; k++) {
				if (count[k] == 0) {
					continue;
				}
				int side = TILE * levels[k];
				int rows = 1;
				int vertices = side * side * 6 + TILE * TILE * 4 * levels[k] * rows * 6;
				p.push(cb, first[k], levels[k], rows);
				p.draw(cb, vertices, count[k]);
			}
			GraphicsProgram.end(cb);
		}
		this.splats.record(cb, camera, params, columns, detail, light, Desc.tex(packShadowView != 0L ? packShadowView : lightmap, this.nearest.handle),
			width, height, colorFormat, colorView, depthView);
		if (this.activeFrames > 0) {
			GraphicsProgram sp = this.sprayDraw(colorFormat);
			GraphicsProgram.begin(cb, width, height, colorView, null, depthView);
			sp.bind(cb, camera, params, columns, detail, sprayDesc, light);
			sp.draw(cb, 6, SPRAY_CAPACITY);
			GraphicsProgram.end(cb);
		}
	}

	private GraphicsProgram sprayDraw(int colorFormat) {
		return this.sprayDraws.computeIfAbsent(colorFormat, format -> new GraphicsProgram("snow_spray.vert", "snow_spray.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, false, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.PREMULTIPLIED), Desc.UBO, Desc.SSBO,
			Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.TEX));
	}

	private void ensureMoving(int n, int q) {
		if (this.detail[0] != null && n == this.trackN && q == this.trackQ) {
			return;
		}
		for (AutoCloseable r : new AutoCloseable[]{this.detail[0], this.detail[1], this.depositBuf, this.restBuf}) {
			if (r != null) {
				Vk.destroyLater(r);
			}
		}
		for (int k = 0; k < Vk.FRAME_RING; k++) {
			if (this.saveBuf[k] != null) {
				Vk.destroyLater(this.saveBuf[k]);
			}
			this.saveBuf[k] = VkBuf.readback((long) n * n * 4L);
			this.saveMeta[k] = null;
		}
		long points = (long) n * n;
		this.detail[0] = VkBuf.device(points * 4L, 0);
		this.detail[1] = VkBuf.device(points * 4L, 0);
		this.depositBuf = VkBuf.device(points * 4L, 0);
		this.restBuf = VkBuf.device(points * 4L, 0);
		this.detailCur = 0;
		this.trackN = n;
		this.trackQ = q;
		this.trackValid = false;
		if (this.sprayBuf == null) {
			this.sprayBuf = VkBuf.device(16L + SPRAY_CAPACITY * 32L, 0);
			this.spawnBuf = VkBuf.upload((long) SPAWN_SLOT * Vk.FRAME_RING, VkBuf.STORAGE);
			this.landedBuf = VkBuf.upload((long) LANDED_SLOT * Vk.FRAME_RING, VkBuf.STORAGE);
			for (int k = 0; k < Vk.FRAME_RING; k++) {
				this.landedBuf.mapped().putInt(k * LANDED_SLOT, 0);
			}
			this.landedBuf.flush(0, (long) LANDED_SLOT * Vk.FRAME_RING);
			this.settle = new ComputeProgram("snow_settle.comp", Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO);
			this.spray = new ComputeProgram("snow_spray.comp", Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO);
		}
	}

	private static int[] lodLevels(int q) {
		int[] wanted = {q, Math.max(1, q / 2), Math.max(1, Math.min(2, q / 4)), 1};
		List<Integer> out = new ArrayList<>();
		for (int w : wanted) {
			if (out.isEmpty() || out.get(out.size() - 1) != w) {
				out.add(w);
			}
		}
		return out.stream().mapToInt(Integer::intValue).toArray();
	}

	private List<float[]> shapes(Minecraft mc, ClientLevel lvl, Columns c, Vec3 cam, float partial, int trackRadius, int bx, int by, int bz) {
		List<float[]> out = new ArrayList<>();
		List<Entity> near = new ArrayList<>();
		double reach2 = (double) trackRadius * trackRadius;
		List<Entity> candidates = new ArrayList<>();
		for (Entity e : lvl.entitiesForRendering()) {
			candidates.add(e);
		}
		AABB area = new AABB(cam.x - trackRadius, cam.y - 64.0, cam.z - trackRadius, cam.x + trackRadius, cam.y + 64.0, cam.z + trackRadius);
		for (me.skaffy.realistic.RealisticBlockEntity body : me.skaffy.realistic.RealisticBlockEntity.near(lvl, area)) {
			if (!candidates.contains(body)) {
				candidates.add(body);
			}
		}
		for (Entity e : candidates) {
			boolean arrow = e instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow;
			if (e.isSpectator() || e instanceof Projectile && !arrow || e.distanceToSqr(cam) > reach2) {
				continue;
			}
			if (e.getBbWidth() < 0.2F && !(e instanceof ItemEntity) && !arrow) {
				continue;
			}
			AABB bb = e.getBoundingBox();
			int ex = Mth.floor(e.getX());
			int ez = Mth.floor(e.getZ());
			double y = bb.minY;
			double reachUp = bb.maxY;
			boolean touches = false;
			for (int dz = -1; dz <= 1 && !touches; dz++) {
				for (int dx = -1; dx <= 1 && !touches; dx++) {
					int i = c.slot(ex + dx, ez + dz);
					touches = c.holds(i, ex + dx, ez + dz) && (c.flags[i] & COL_SNOW) != 0
						&& y <= c.top[i] + Math.max(WaterSettings.snowFluff, 0.0F) + 0.15 && reachUp >= c.base[i] - 1.0;
				}
			}
			if (touches) {
				near.add(e);
			}
		}
		near.sort(Comparator.comparingDouble(e -> e.distanceToSqr(cam)));
		int limit = Math.clamp(WaterSettings.snowTrackEntities, 1, MAX_SHAPES / 2);
		for (int k = 0; k < near.size() && k < limit && out.size() < MAX_SHAPES - 8; k++) {
			Entity e = near.get(k);
			double x = Mth.lerp(partial, e.xo, e.getX()) - bx;
			double y = Mth.lerp(partial, e.yo, e.getY()) - by;
			double z = Mth.lerp(partial, e.zo, e.getZ()) - bz;
			float vx = (float) (e.getX() - e.xo) * 20.0F;
			float vz = (float) (e.getZ() - e.zo) * 20.0F;
			float down = Math.max((float) (e.yo - e.getY()) * 20.0F, 0.0F);
			float yaw = (float) Math.toRadians(e instanceof LivingEntity living ? living.getPreciseBodyRotation(partial) : e.getYRot(partial));
			float fx = -Mth.sin(yaw);
			float fz = Mth.cos(yaw);
			float w = e.getBbWidth();
			float h = e.getBbHeight();
			if (e instanceof me.skaffy.realistic.RealisticBlockEntity body) {
				addBodyBoxes(out, body, partial, bx, by, bz);
				continue;
			}
			if (e instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) {
				addArrow(out, e, x, y, z, partial, down, vx, vz);
				continue;
			}
			if (e instanceof LivingEntity living && WaterSettings.snowFootprints && h >= 1.2F && w <= 0.8F) {
				float scale = h / 1.8F;
				float pos = living.walkAnimation.position(partial);
				float speed = Math.min(living.walkAnimation.speed(partial), 1.0F);
				for (int side = -1; side <= 1; side += 2) {
					float swing = Mth.cos(pos * 0.6662F + (side > 0 ? 0.0F : Mth.PI)) * 1.4F * speed;
					addLeg(out, x, y, z, fx, fz, 0.0F, 0.12F * scale * side, 0.75F * scale, 0.125F * scale, swing, down, vx, vz);
				}
				addBody(out, x, y + 0.45 * scale, z, fx, fz, w, down, vx, vz);
			} else if (e instanceof LivingEntity living && WaterSettings.snowFootprints && h >= 0.4F) {
				float pos = living.walkAnimation.position(partial);
				float speed = Math.min(living.walkAnimation.speed(partial), 1.0F);
				float legLength = Math.clamp(h * 0.45F, 0.2F, 0.85F);
				float radius = Math.clamp(w * 0.12F, 0.05F, 0.16F);
				for (int leg = 0; leg < 4; leg++) {
					float along = (leg < 2 ? 0.35F : -0.35F) * w;
					float side = ((leg & 1) == 0 ? 0.3F : -0.3F) * w;
					boolean inPhase = (leg == 0 || leg == 3);
					float swing = Mth.cos(pos * 0.6662F + (inPhase ? 0.0F : Mth.PI)) * 1.4F * speed;
					addLeg(out, x, y, z, fx, fz, along, side, legLength, radius, swing, down, vx, vz);
				}
				addBody(out, x, y + legLength, z, fx, fz, w, down, vx, vz);
			} else {
				float half = Math.max(w * 0.45F, 0.06F);
				out.add(new float[]{(float) x, (float) z, fx, fz, half, half, Math.min(half, 0.12F), (float) y, 0.0F, down, vx, vz});
			}
		}
		return out;
	}

	private static void addBodyBoxes(List<float[]> out, me.skaffy.realistic.RealisticBlockEntity e, float partial, int bx, int by, int bz) {
		Vec3 now = e.renderPosition(partial);
		org.joml.Quaternionf q = new org.joml.Quaternionf(e.renderRotation(partial));
		e.syncClientBody(now, q);
		org.joml.Vector3d vel = e.clientVelocity();
		float vx = (float) vel.x;
		float vz = (float) vel.z;
		float down = (float) Math.max(-vel.y, 0.0);
		org.joml.Vector3d c = new org.joml.Vector3d();
		org.joml.Vector3d o = new org.joml.Vector3d();
		double[] cx = new double[8];
		double[] cy = new double[8];
		double[] cz = new double[8];
		for (me.skaffy.physics.RigidBody.Cell cell : e.body.cells) {
			if (!(cell.payload instanceof BlockState state)) {
				continue;
			}
			for (double[] w : me.skaffy.client.realistic.BodyShapes.boxes(state)) {
				if (out.size() >= MAX_SHAPES - 8) {
					return;
				}
				double bottom = Double.MAX_VALUE;
				for (int k = 0; k < 8; k++) {
					e.body.toWorld(cell.x + ((k & 1) != 0 ? w[3] : w[0]), cell.y + ((k & 2) != 0 ? w[4] : w[1]), cell.z + ((k & 4) != 0 ? w[5] : w[2]), c);
					cx[k] = c.x;
					cy[k] = c.y;
					cz[k] = c.z;
					bottom = Math.min(bottom, c.y);
				}
				e.body.toWorld(cell.x, cell.y, cell.z, o);
				e.body.toWorld(cell.x + 1.0, cell.y, cell.z, c);
				double ax = c.x - o.x;
				double az = c.z - o.z;
				double flatX = Math.sqrt(ax * ax + az * az);
				e.body.toWorld(cell.x, cell.y, cell.z + 1.0, c);
				double zx = c.x - o.x;
				double zz = c.z - o.z;
				double flatZ = Math.sqrt(zx * zx + zz * zz);
				double fx;
				double fz;
				if (flatZ >= flatX && flatZ > 1e-4) {
					fx = zx / flatZ;
					fz = zz / flatZ;
				} else if (flatX > 1e-4) {
					fx = ax / flatX;
					fz = az / flatX;
				} else {
					fx = 0.0;
					fz = 1.0;
				}
				double sx = fz;
				double sz = -fx;
				double loS = Double.MAX_VALUE;
				double hiS = -Double.MAX_VALUE;
				double loF = Double.MAX_VALUE;
				double hiF = -Double.MAX_VALUE;
				for (int k = 0; k < 8; k++) {
					double ps = cx[k] * sx + cz[k] * sz;
					double pf = cx[k] * fx + cz[k] * fz;
					loS = Math.min(loS, ps);
					hiS = Math.max(hiS, ps);
					loF = Math.min(loF, pf);
					hiF = Math.max(hiF, pf);
				}
				double ms = (loS + hiS) * 0.5;
				double mf = (loF + hiF) * 0.5;
				float halfS = (float) ((hiS - loS) * 0.5);
				float halfF = (float) ((hiF - loF) * 0.5);
				double centreX = sx * ms + fx * mf;
				double centreZ = sz * ms + fz * mf;
				out.add(new float[]{(float) (centreX - bx), (float) (centreZ - bz), (float) fx, (float) fz, halfS, halfF,
					Math.min(0.04F, 0.5F * Math.min(halfS, halfF)), (float) (bottom - by), 0.0F, down, vx, vz});
			}
		}
	}

	private static void addArrow(List<float[]> out, Entity e, double x, double y, double z, float partial, float down, float vx, float vz) {
		if (out.size() >= MAX_SHAPES - 8) {
			return;
		}
		double yaw = Math.toRadians(e.getYRot(partial));
		double pitch = Math.toRadians(e.getXRot(partial));
		double dx = Math.sin(yaw) * Math.cos(pitch);
		double dy = Math.sin(pitch);
		double dz = Math.cos(yaw) * Math.cos(pitch);
		boolean trident = e instanceof net.minecraft.world.entity.projectile.arrow.ThrownTrident;
		double back = trident ? 1.0 : 0.45;
		double ahead = trident ? 0.25 : 0.12;
		float radius = trident ? 0.06F : 0.04F;
		double cy = y + e.getBbHeight() * 0.5;
		out.add(new float[]{(float) (x - dx * back), (float) (z - dz * back), (float) (x + dx * ahead), (float) (z + dz * ahead), radius,
			(float) (cy - dy * back), (float) (cy + dy * ahead), 0.0F, 2.0F, down, vx, vz});
	}

	private static void addLeg(List<float[]> out, double x, double y, double z, float fx, float fz, float along, float side, float length,
		float radius, float swing, float down, float vx, float vz) {
		if (out.size() >= MAX_SHAPES - 8) {
			return;
		}
		float reach = Math.max(length - radius, 0.01F);
		double hipX = x + fx * along + fz * side;
		double hipZ = z + fz * along - fx * side;
		double hipY = y + length;
		float forward = -Mth.sin(swing) * reach;
		float reachDown = Mth.cos(swing) * reach;
		out.add(new float[]{(float) hipX, (float) hipZ, (float) (hipX + fx * forward), (float) (hipZ + fz * forward), radius, (float) hipY,
			(float) (hipY - reachDown), 0.0F, 2.0F, down, vx, vz});
	}

	private static void addBody(List<float[]> out, double x, double bottom, double z, float fx, float fz, float width, float down, float vx, float vz) {
		if (out.size() >= MAX_SHAPES - 8) {
			return;
		}
		float half = width * 0.5F + 0.05F;
		out.add(new float[]{(float) x, (float) z, fx, fz, half, half, Math.min(half, 0.15F), (float) bottom, 0.0F, down, vx, vz});
	}

	private static void putFloats(ByteBuffer b, int o, float x, float y, float z, float w) {
		b.putFloat(o, x).putFloat(o + 4, y).putFloat(o + 8, z).putFloat(o + 12, w);
	}

	public void clear() {
		this.level = null;
		this.resetColumns();
		this.splats.clear();
	}

	public void shutdown() {
		this.splats.shutdown();
		this.draws.values().forEach(GraphicsProgram::close);
		this.draws.clear();
		this.sprayDraws.values().forEach(GraphicsProgram::close);
		this.sprayDraws.clear();
		for (ComputeProgram p : new ComputeProgram[]{this.track, this.settle, this.spray}) {
			if (p != null) {
				p.close();
			}
		}
		this.track = null;
		this.settle = null;
		this.spray = null;
		for (AutoCloseable r : new AutoCloseable[]{this.columnBuf, this.params, this.tileBuf, this.detail[0], this.detail[1], this.depositBuf, this.restBuf,
			this.sprayBuf, this.spawnBuf, this.landedBuf}) {
			if (r != null) {
				Vk.destroyLater(r);
			}
		}
		for (int k = 0; k < Vk.FRAME_RING; k++) {
			if (this.saveBuf[k] != null) {
				Vk.destroyLater(this.saveBuf[k]);
				this.saveBuf[k] = null;
				this.saveMeta[k] = null;
			}
			this.landedMeta[k] = null;
		}
		if (this.linear != null) {
			this.linear.close();
			this.nearest.close();
			this.linear = null;
			this.nearest = null;
		}
		this.columnBuf = null;
		this.params = null;
		this.tileBuf = null;
		this.detail[0] = null;
		this.detail[1] = null;
		this.depositBuf = null;
		this.restBuf = null;
		this.sprayBuf = null;
		this.sprayCleared = false;
		this.spawnBuf = null;
		this.landedBuf = null;
		this.trackValid = false;
		this.trackN = 0;
	}


	public void snowBlast(double cx, double cy, double cz, float radius, long[] positions, byte[] layers) {
		Minecraft mc = Minecraft.getInstance();
		if (!this.active || mc.level == null) {
			return;
		}
		RandomSource random = mc.level.getRandom();
		int thrown = 0;
		for (int k = 0; k < positions.length; k++) {
			int x = BlockPos.getX(positions[k]);
			int y = BlockPos.getY(positions[k]);
			int z = BlockPos.getZ(positions[k]);
			float volume = Math.clamp(layers[k], 1, 8) / 8.0F;
			int count = Math.max(4, layers[k] * 5);
			for (int p = 0; p < count && this.spawnQueue.size() < 200_000; p++) {
				double px = x + random.nextDouble();
				double py = y + random.nextDouble() * volume;
				double pz = z + random.nextDouble();
				double dx = px - cx;
				double dz = pz - cz;
				double flat = Math.sqrt(dx * dx + dz * dz);
				if (flat < 1e-3) {
					double a = random.nextDouble() * Math.PI * 2.0;
					dx = Math.cos(a);
					dz = Math.sin(a);
					flat = 1.0;
				}
				double range = Math.max(radius * (1.0 + random.nextDouble()) - flat, 0.5);
				double angle = Math.toRadians(40.0 + random.nextDouble() * 25.0);
				double speed = Math.sqrt(range * SPRAY_GRAVITY / Math.sin(2.0 * angle)) * 1.15;
				float vx = (float) (dx / flat * Math.cos(angle) * speed);
				float vz = (float) (dz / flat * Math.cos(angle) * speed);
				float vy = (float) (Math.sin(angle) * speed);
				this.spawnQueue.add(new float[]{(float) px, (float) py, (float) pz, vx, vy, vz, volume / count});
				thrown++;
			}
		}
		this.activeFrames = Math.max(this.activeFrames, SPRAY_FRAMES);
		log("explosion threw {} bits of snow from {} blocks", thrown, positions.length);
	}

	private int writeSpawns(int slot, int q, int bx, int by, int bz) {
		if (this.spawnQueue.isEmpty() || this.spawnBuf == null || !this.sprayCleared) {
			return 0;
		}
		ByteBuffer m = this.spawnBuf.mapped();
		long base = (long) slot * SPAWN_SLOT;
		int count = 0;
		while (count < SPAWN_MAX && !this.spawnQueue.isEmpty()) {
			float[] s = this.spawnQueue.poll();
			int o = (int) (base + count * 32L);
			m.putFloat(o, s[0] - this.sprayOriginX).putFloat(o + 4, s[1]).putFloat(o + 8, s[2] - this.sprayOriginZ).putFloat(o + 12, s[6] * q * q);
			m.putFloat(o + 16, s[3]).putFloat(o + 20, s[4]).putFloat(o + 24, s[5]).putFloat(o + 28, 0.0F);
			count++;
		}
		this.spawnBuf.flush(base, count * 32L);
		return count;
	}

	private void readLanded(int slot, ClientLevel lvl, Columns c, int bx, int by, int bz) {
		if (this.landedBuf == null) {
			return;
		}
		ByteBuffer m = this.landedBuf.mapped();
		long base = (long) slot * LANDED_SLOT;
		this.landedBuf.invalidate(base, 16);
		int n = Math.min(m.getInt((int) base), LANDED_MAX);
		int[] meta = this.landedMeta[slot];
		if (n > 0 && meta != null && Minecraft.getInstance().getSingleplayerServer() != null) {
			this.landedBuf.invalidate(base + 16, n * 16L);
			int q = meta[3];
			for (int k = 0; k < n; k++) {
				int o = (int) (base + 16 + k * 16L);
				double x = m.getFloat(o) + meta[0];
				double y = m.getFloat(o + 4) + meta[1];
				double z = m.getFloat(o + 8) + meta[2];
				float layers = m.getFloat(o + 12) / (q * q) * 8.0F;
				int px = Mth.floor(x);
				int pz = Mth.floor(z);
				int i = c.slot(px, pz);
				int y0 = c.holds(i, px, pz) && c.repY[i] != Integer.MIN_VALUE && Math.abs(c.top[i] - y) < 1.0 ? c.repY[i] : Mth.floor(y + 0.01);
				this.landedLayers.addTo(BlockPos.asLong(px, y0, pz), layers);
			}
		}
		m.putInt((int) base, 0);
		this.landedBuf.flush(base, 16);
		this.landedMeta[slot] = new int[]{bx, by, bz, this.trackQ};
		if (this.landedLayers.isEmpty()) {
			return;
		}
		LongArrayList positions = new LongArrayList();
		ByteArrayList deltas = new ByteArrayList();
		ByteArrayList packed = new ByteArrayList();
		for (var it = this.landedLayers.long2FloatEntrySet().iterator(); it.hasNext(); ) {
			var e = it.next();
			if (e.getFloatValue() >= 1.0F && positions.size() < me.skaffy.net.SnowEditPayload.MAX_ENTRIES) {
				int whole = (int) Math.min(e.getFloatValue(), 8.0F);
				positions.add(e.getLongKey());
				deltas.add((byte) whole);
				packed.add((byte) 0);
				e.setValue(e.getFloatValue() - whole);
			}
		}
		if (this.landedLayers.size() > 65536) {
			this.landedLayers.clear();
		}
		if (!positions.isEmpty()) {
			this.sendEdits(positions, deltas, packed);
			log("thrown snow built {} new layers", positions.size());
		}
	}

	private final int[][] landedMeta = new int[Vk.FRAME_RING][];

	private void readSave(int slot, ClientLevel lvl, Columns c) {
		int[] meta = this.saveMeta[slot];
		VkBuf buf = this.saveBuf[slot];
		this.saveMeta[slot] = null;
		if (meta == null || buf == null || Minecraft.getInstance().getSingleplayerServer() == null) {
			return;
		}
		int loX = meta[0];
		int loZ = meta[1];
		int n = meta[2];
		int q = meta[3];
		buf.invalidate(0, (long) n * n * 4L);
		ByteBuffer m = buf.mapped();
		int bx0 = Math.floorDiv(loX, q) + 1;
		int bz0 = Math.floorDiv(loZ, q) + 1;
		int bx1 = Math.floorDiv(loX + n, q) - 1;
		int bz1 = Math.floorDiv(loZ + n, q) - 1;
		LongArrayList positions = new LongArrayList();
		ByteArrayList deltas = new ByteArrayList();
		ByteArrayList packed = new ByteArrayList();
		for (int bz = bz0; bz < bz1; bz++) {
			for (int bx = bx0; bx < bx1; bx++) {
				int i = c.slot(bx, bz);
				if (!c.holds(i, bx, bz) || c.repY[i] == Integer.MIN_VALUE || this.pendingEdits.containsKey(BlockPos.asLong(bx, 0, bz))) {
					continue;
				}
				double sum = 0.0;
				double packSum = 0.0;
				for (int pz = 0; pz < q; pz++) {
					for (int px = 0; px < q; px++) {
						int wx = bx * q + px;
						int wz = bz * q + pz;
						int at = (Math.floorMod(wx, n) + Math.floorMod(wz, n) * n) * 4;
						int raw = m.getInt(at);
						sum += Float.float16ToFloat((short) (raw & 0xFFFF));
						packSum += Float.float16ToFloat((short) (raw >>> 16));
					}
				}
				double avg = sum / (q * q);
				double pack = packSum / (q * q);
				int layers = (int) Math.floor(Math.abs(avg) * 8.0 + 1e-4);
				if (layers < 1) {
					continue;
				}
				int delta = avg < 0.0 ? -Math.min(layers, c.layers[i]) : Math.min(layers, 8);
				if (delta == 0) {
					continue;
				}
				positions.add(BlockPos.asLong(bx, c.repY[i], bz));
				deltas.add((byte) delta);
				packed.add((byte) (delta < 0 && pack > 0.4 ? 1 : 0));
				this.pendingEdits.put(BlockPos.asLong(bx, 0, bz), new float[]{c.top[i], EDIT_TICKS});
			}
		}
		if (!positions.isEmpty()) {
			this.sendEdits(positions, deltas, packed);
			log("saved moved snow: {} columns changed", positions.size());
		}
	}

	private void sendEdits(LongArrayList positions, ByteArrayList deltas, ByteArrayList packed) {
		if (!ClientPlayNetworking.canSend(me.skaffy.net.SnowEditPayload.TYPE)) {
			return;
		}
		for (int start = 0; start < positions.size(); start += me.skaffy.net.SnowEditPayload.MAX_ENTRIES) {
			int end = Math.min(positions.size(), start + me.skaffy.net.SnowEditPayload.MAX_ENTRIES);
			ClientPlayNetworking.send(new me.skaffy.net.SnowEditPayload(positions.subList(start, end).toLongArray(),
				deltas.subList(start, end).toByteArray(), packed.subList(start, end).toByteArray()));
		}
	}
}
