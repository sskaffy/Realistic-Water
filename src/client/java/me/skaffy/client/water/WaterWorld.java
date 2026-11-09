package me.skaffy.client.water;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticLeavesBlock;
import me.skaffy.block.RealisticSandBlock;
import me.skaffy.client.compat.FrameTarget;
import me.skaffy.client.compat.ShaderCompat;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import me.skaffy.client.grass.GrassRenderer;
import me.skaffy.client.grass.RealisticGrass;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.block.WaveMakerBlock;
import me.skaffy.block.WaterDrainBlock;
import me.skaffy.fluid.RealisticLavaFluid;
import me.skaffy.fluid.RealisticSlimeFluid;
import me.skaffy.fluid.RealisticWaterFluid;
import me.skaffy.fluid.FluidMixing;
import me.skaffy.net.FlowPayload;
import me.skaffy.net.FluidMixPayload;
import me.skaffy.net.VanillaFeedPayload;
import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.BoatHull;
import me.skaffy.realistic.RealisticBlockEntity;
import me.skaffy.realistic.RealisticBoatEntity;
import me.skaffy.realistic.RealisticPhysics;
import me.skaffy.net.LavaSyncPayload;
import me.skaffy.net.LeavesSyncPayload;
import me.skaffy.net.PortalTransitPayload;
import me.skaffy.net.BackgroundSyncPayload;
import me.skaffy.net.BoilingWaterPayload;
import net.minecraft.resources.Identifier;
import me.skaffy.net.RemoveAllWaterPayload;
import me.skaffy.net.SandSyncPayload;
import me.skaffy.net.SlimeSyncPayload;
import me.skaffy.net.WaterSyncPayload;
import me.skaffy.ocean.OceanOrigin;
import me.skaffy.ocean.OceanWaves;
import net.minecraft.world.level.block.Blocks;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.tags.FluidTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class WaterWorld {
	private static final WaterWorld INSTANCE = new WaterWorld();
	static final int UBO_SLOT = 8192;
	static final int UBO_SIZE = 4464;
	static final float LATENT_FREEZE = 79.8F;
	static final float PARTICLES_PER_CELL = 8.0F;
	static final float SAND_POISSON = 0.3F;

	static float sandWaveSpeed() {
		float nu = SAND_POISSON;
		return (float) Math.sqrt(WaterSettings.sandStiffness * (1.0F - nu) / ((1.0F + nu) * (1.0F - 2.0F * nu)));
	}
	private static final double WORLD_WRAP = 4096.0;
	static final int MAX_ENTITIES = 32;
	static final int MAX_SOURCES = 64;
	static final int MAX_REGIONS = 16;
	static final int SLOTS_PER_FRAME = MAX_REGIONS + 4;
	static final int JELLY_SLOT = MAX_REGIONS + 2;
	static final int HAND_SLOT = MAX_REGIONS + 3;
	static final int GRASS_SLOT = MAX_REGIONS + 1;
	private static final int JOIN_DISTANCE = 16;
	private static final int SYNC_INTERVAL = 30;
	private static final int MIX_INTERVAL = 2;
	private static final int SHADOW_INTERVAL = 3;
	private static final double MIX_REACH = 4.0;
	private static final int MIX_MEMORY = 60;
	private static final int ECHO_TIMEOUT = 200;

	public static final Matrix4f LEVEL_PROJECTION = new Matrix4f();

	private static final class Echoes {
		final Long2IntOpenHashMap expected = new Long2IntOpenHashMap();
		final Long2IntOpenHashMap frame = new Long2IntOpenHashMap();

		boolean consume(long key, int amount) {
			if (this.expected.containsKey(key) && this.expected.get(key) == amount) {
				this.expected.remove(key);
				this.frame.remove(key);
				return true;
			}
			return false;
		}

		void expect(long key, int amount, int frame) {
			this.expected.put(key, amount);
			this.frame.put(key, frame);
		}

		void expire(int now) {
			this.frame.long2IntEntrySet().removeIf(e -> {
				if (now - e.getIntValue() > ECHO_TIMEOUT) {
					this.expected.remove(e.getLongKey());
					return true;
				}
				return false;
			});
		}

		void clear() {
			this.expected.clear();
			this.frame.clear();
		}
	}

	private final List<WaterRegion> regions = new ArrayList<>();
	private final List<WaterRegion> background = new ArrayList<>();
	private static final int BACKGROUND_PORTAL_REACH = 48;
	private final Map<BlockPos, Material> sources = new LinkedHashMap<>();
	private final ArrayDeque<ChunkPos> chunkScanQueue = new ArrayDeque<>();
	private final Echoes waterEchoes = new Echoes();
	private final Echoes sandEchoes = new Echoes();
	private final Echoes lavaEchoes = new Echoes();
	private final Echoes slimeEchoes = new Echoes();
	private final Echoes leafEchoes = new Echoes();
	private final List<double[]> blasts = new ArrayList<>();
	private final List<double[]> recentBlasts = new ArrayList<>();
	private final List<Object[]> pendingFills = new ArrayList<>();
	private final FlowSounds sounds = new FlowSounds();
	private final WaveMakerSounds waveMakerSounds = new WaveMakerSounds();
	private final BoilSounds boilSounds = new BoilSounds();
	private final Map<WaterRegion, WaterRegion.Fill> latestFills = new IdentityHashMap<>();
	private final Long2IntOpenHashMap recentMixes = new Long2IntOpenHashMap();
	private final Map<Integer, double[]> bodyFlows = new HashMap<>();
	private final LongOpenHashSet waveMakers = new LongOpenHashSet();
	private final LongOpenHashSet waterDrains = new LongOpenHashSet();
	private int freezeForceFrames;
	private float freezeForceValue;
	private final LongOpenHashSet portalBlocks = new LongOpenHashSet();
	private final LongOpenHashSet vanillaFeeds = new LongOpenHashSet();
	private final Map<Integer, SolidCache> solidCaches = new HashMap<>();
	private final OceanSurfaces oceanSurfaces = new OceanSurfaces();
	private final OceanSim oceanSim = new OceanSim(this, this.oceanSurfaces);
	private @Nullable OceanRenderer oceanRenderer;
	private boolean cameraUnderSea;
	private boolean seaDrawsFog;
	private @Nullable WaterRenderer renderer;
	private @Nullable SandRenderer sandRenderer;
	private @Nullable IceCrystalRenderer crystalRenderer;
	private @Nullable GrassRenderer grassRenderer;
	private @Nullable GrassFlow grassFlow;
	private final List<WaterRenderer.Body> lateGrassWater = new ArrayList<>();
	private @Nullable Desc lateGrassUbo;
	private float lateGrassDt;
	private @Nullable Desc lateJellyUbo;
	private @Nullable Desc lateIceUbo;
	private me.skaffy.client.trees.@Nullable TreeRenderer treeRenderer;
	private @Nullable Desc lateTreeUbo;
	private @Nullable LeafRenderer leafRenderer;
	private @Nullable VkBuf uboRing;
	private @Nullable VkBuf frameView;
	private @Nullable VkTex layerDepth;
	private final List<WaterRenderer.Body> lateSand = new ArrayList<>();
	private final List<WaterRenderer.Body> lateLeaves = new ArrayList<>();
	private final List<WaterRenderer.Body> lateSteam = new ArrayList<>();
	private @Nullable SteamRenderer steamRenderer;
	private @Nullable RainSystem rain;
	private static final int RAIN_FEED_SAMPLES = 8;
	private long lateLightmap;
	private boolean paused;
	private boolean failed;
	private long lastFrameNanos;
	private float simTime;
	private int frameCounter;
	private int nextRegionId;
	private int lastSteps;
	private float lastDt;
	private float smoothedFrameMs;
	private boolean localPlayerFirst;
	private int builtResolution = WaterSettings.resolution;
	private int builtSandResolution = WaterSettings.sandResolution;
	private int builtLavaResolution = WaterSettings.lavaResolution;
	private int builtSlimeResolution = WaterSettings.slimeResolution;
	private int builtLeafResolution = WaterSettings.leafResolution;
	private int lastSandSteps;
	private boolean layerOutput;
	private ShaderCompat.@Nullable PackShadow packShadow;
	static long packShadowView;
	private float lastSandDt;

	public void submitPackMaterials(SubmitNodeCollector collector, Vec3 camera) {
		if (this.simLevel != null && PackMaterials.active()) {
			PackMaterials.submit(collector, camera, this.simLevel, this.regions);
		}
		ClientLevel lvl = Minecraft.getInstance().level;
		if (lvl != null) {
			PackFoam.submit(collector, camera, lvl, this.regions, this.oceanSurfaces);
		}
	}

	public boolean hasShadowCasters() {
		for (WaterRegion r : this.regions) {
			if (r.shadow != null || r.packMesh != null) {
				return true;
			}
		}
		return false;
	}

	public void submitShadowCasters(SubmitNodeCollector collector, Vec3 camera) {
		if (ShadowCasters.wanted()) {
			if (this.simLevel != null) {
				ShadowCasters.submit(collector, camera, this.simLevel, this.regions);
			}
		}
	}

	public static WaterWorld get() {
		return INSTANCE;
	}

	public static boolean isRealistic(FluidState state) {
		return RealisticWaterFluid.isRealistic(state);
	}

	private static int waterAmount(BlockState state) {
		if (RealisticWaterBlock.isFrozen(state)) {
			return RealisticWaterBlock.eighths(state);
		}
		FluidState fluid = state.getFluidState();
		return RealisticWaterFluid.isRealistic(fluid) && !RealisticWaterBlock.isOcean(state) ? fluid.getAmount() : 0;
	}

	private static int lavaAmount(BlockState state) {
		FluidState fluid = state.getFluidState();
		return RealisticLavaFluid.isRealistic(fluid) ? fluid.getAmount() : 0;
	}

	private static int slimeAmount(BlockState state) {
		FluidState fluid = state.getFluidState();
		return RealisticSlimeFluid.isRealistic(fluid) ? fluid.getAmount() : 0;
	}

	private static int amountOf(BlockState state, Material material) {
		return switch (material) {
			case SAND -> RealisticSandBlock.layers(state);
			case LAVA -> lavaAmount(state);
			case SLIME -> slimeAmount(state);
			case LEAVES -> RealisticLeavesBlock.layers(state);
			default -> waterAmount(state);
		};
	}

	private static @Nullable Material springMaterial(BlockState state) {
		if (!RealisticWaterBlock.isInfinite(state)) {
			return null;
		}
		if (state.getBlock() == ModContent.REALISTIC_LAVA_BLOCK) {
			return Material.LAVA;
		}
		if (state.getBlock() == ModContent.REALISTIC_WATER_BLOCK) {
			return Material.WATER;
		}
		return null;
	}

	private Echoes echoes(Material material) {
		return switch (material) {
			case SAND -> this.sandEchoes;
			case LAVA -> this.lavaEchoes;
			case SLIME -> this.slimeEchoes;
			case LEAVES -> this.leafEchoes;
			default -> this.waterEchoes;
		};
	}

	public boolean togglePause() {
		this.paused = !this.paused;
		return this.paused;
	}

	private SolidCache solids(Material material) {
		return this.solids(material, resolutionFor(material));
	}

	private SolidCache solidsFor(WaterRegion r) {
		if (r.background == null) {
			return this.solids(r.material, r.res);
		}
		boolean granular = r.material.isGranular();
		int res = r.res;
		boolean water = r.material == Material.WATER;
		return r.background.solids.computeIfAbsent(res * 4 + (granular ? 1 : 0) + (water ? 2 : 0), k -> new SolidCache(res, granular, water));
	}

	private SolidCache solids(Material material, int res) {
		boolean granular = material.isGranular();
		boolean water = material == Material.WATER;
		return this.solidCaches.computeIfAbsent(res * 4 + (granular ? 1 : 0) + (water ? 2 : 0), k -> new SolidCache(res, granular, water));
	}

	static int resolutionFor(Material material) {
		if (material == Material.SAND) {
			return Math.max(1, WaterSettings.sandResolution);
		}
		if (material == Material.LAVA) {
			return Math.max(1, WaterSettings.lavaResolution);
		}
		if (material == Material.SLIME) {
			return Math.max(1, WaterSettings.slimeResolution);
		}
		if (material == Material.LEAVES) {
			return Math.max(1, WaterSettings.leafResolution);
		}
		if (material.isGas()) {
			return Math.max(1, WaterSettings.steamResolution);
		}
		return Math.max(1, WaterSettings.resolution);
	}

	private static boolean isHost() {
		return Minecraft.getInstance().getSingleplayerServer() != null;
	}

	static boolean isHostPublic() {
		return isHost();
	}

	float oceanAmplitude(int x, int y, int z) {
		return this.oceanSurfaces.restTop(x, z) == y ? this.oceanSurfaces.amplitude(x, z) : 0.0F;
	}

	WaterRegion addOceanRegion(OceanZone zone) {
		WaterRegion r = new WaterRegion(this.nextRegionId++, Math.max(1, WaterSettings.oceanResolution), Material.WATER);
		r.ocean = zone;
		this.regions.add(r);
		return r;
	}

	void removeOceanRegion(WaterRegion r) {
		this.regions.remove(r);
		this.latestFills.remove(r);
		r.close();
	}

	public void oceanToggled(boolean on) {
		for (WaterRegion r : List.copyOf(this.oceanSim.zones)) {
			this.removeOceanRegion(r);
		}
		this.oceanSim.clear();
		this.oceanSurfaces.clear();
		Minecraft mc = Minecraft.getInstance();
		if (on && mc.level != null) {
			this.oceanSurfaces.scanAll(mc.level, mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos,
				mc.options.renderDistance().get() + 1);
		}
	}

	private static boolean sameRestView(BlockState a, BlockState b) {
		if (OceanWaves.isRestWater(a) && OceanWaves.isRestWater(b)) {
			return a.getFluidState().getOwnHeight() == b.getFluidState().getOwnHeight() || RealisticWaterBlock.isOcean(a) || RealisticWaterBlock.isOcean(b);
		}
		return OceanWaves.isRestAir(a) && OceanWaves.isRestAir(b) || a == b;
	}


	private @Nullable WaterRegion regionNear(BlockPos pos, int margin, Material material) {
		for (WaterRegion r : this.regions) {
			if (r.ocean == null && r.material == material && r.res == resolutionFor(material) && r.claims(pos, margin)) {
				return r;
			}
		}
		return null;
	}

	private WaterRegion regionFor(BlockPos pos, Material material) {
		WaterRegion r = this.regionNear(pos, JOIN_DISTANCE, material);
		if (r == null) {
			r = new WaterRegion(this.nextRegionId++, resolutionFor(material), material);
			this.regions.add(r);
		}
		return r;
	}

	private boolean warnedNoVulkan;

	private boolean vulkanReady() {
		if (Vk.tryDevice() != null) {
			return true;
		}
		Minecraft mc = Minecraft.getInstance();
		if (!this.warnedNoVulkan && mc.player != null) {
			this.warnedNoVulkan = true;
			mc.player.sendSystemMessage(Component.literal(
				"Realistic Water needs the Vulkan graphics API (Options > Video Settings > Graphics API > Vulkan, then restart). "
					+ "Minecraft switches back to OpenGL by itself after a crash during startup."));
		}
		return false;
	}

	public void addWater(BlockPos pos, double y0, double y1) {
		this.addMaterial(pos, y0, y1, Material.WATER);
	}

	public void addSand(BlockPos pos, double y0, double y1) {
		this.addMaterial(pos, y0, y1, Material.SAND);
	}

	public void addLava(BlockPos pos, double y0, double y1) {
		this.addMaterial(pos, y0, y1, Material.LAVA);
	}

	public void addSlime(BlockPos pos, double y0, double y1) {
		this.addMaterial(pos, y0, y1, Material.SLIME);
	}

	public void addSteam(double x, double y, double z, float eighths) {
		if (eighths <= 0.0F || !this.vulkanReady()) {
			return;
		}
		double side = Math.cbrt(eighths / 8.0 * Math.max(WaterSettings.steamExpansion, 0.1F));
		double h = side * 0.5;
		BlockPos at = BlockPos.containing(x, y, z);
		WaterRegion r = this.regionFor(at, Material.STEAM);
		r.addEmitter(new WaterRegion.Emitter(x - h, y, z - h, x + h, y + side, z + h, 0.0F, 0.5F * r.res, 0.0F));
		r.wake(this.frameCounter);
	}

	/** True once the Vulkan device exists, so fire can draw itself on the GPU. */
	public boolean gpuReady() {
		return Vk.tryDevice() != null && !this.failed;
	}

	/**
	 * Puffs a small cloud of gas particles (smoke, flame or soul flame) out of a box. Velocities are in blocks per second and
	 * {@code step} is the spacing of the particles inside the box, so smaller means more, finer particles.
	 */
	public void addGasPuff(Material material, double x, double y, double z, double halfWidth, double height, float vx, float vy, float vz, float step) {
		this.addGasPuff(material, x, y, z, halfWidth, halfWidth, height, vx, vy, vz, step);
	}

	/** Same, from a box that can be wide along one axis and thin along the other: a sheet of flame lying against a wall. */
	public void addGasPuff(Material material, double x, double y, double z, double halfX, double halfZ, double height, float vx, float vy, float vz,
		float step) {
		if (!material.isGas() || !this.vulkanReady()) {
			return;
		}
		WaterRegion r = this.regionFor(BlockPos.containing(x, y, z), material);
		r.addEmitter(new WaterRegion.Emitter(x - halfX, y, z - halfZ, x + halfX, y + height, z + halfZ, vx, vy, vz, step));
		r.wake(this.frameCounter);
	}

	public void addLeaves(BlockPos pos, double y0, double y1) {
		this.addMaterial(pos, y0, y1, Material.LEAVES);
	}

	public static boolean isHostClient() {
		return isHost();
	}

	public void spawnLeaf(double x, double y, double z, float vx, float vy, float vz, int kind) {
		if (!this.vulkanReady()) {
			return;
		}
		WaterRegion r = this.regionFor(BlockPos.containing(x, y, z), Material.LEAVES);
		r.addEmitter(new WaterRegion.Emitter(x - 0.01, y - 0.01, z - 0.01, x + 0.01, y + 0.01, z + 0.01, vx, vy, vz, kind));
		r.wake(this.frameCounter);
	}

	public boolean leavesSimulated(BlockPos pos) {
		for (WaterRegion r : this.regions) {
			if (r.material.isLeaves() && r.background == null && r.windowContains(pos, 0)) {
				return true;
			}
		}
		return false;
	}

	public boolean expireLeaves(BlockPos pos, int kind, int count) {
		for (WaterRegion r : this.regions) {
			if (r.material.isLeaves() && r.background == null && r.windowContains(pos, 0)) {
				r.expireRequests.add(new int[]{pos.getX(), pos.getY(), pos.getZ(), kind, count});
				r.wake(this.frameCounter);
				return true;
			}
		}
		return false;
	}

	public record LeafTally(long frame, int[] window, List<int[]> leaves, boolean complete) {
	}

	public List<LeafTally> takeLeafTallies() {
		List<LeafTally> out = new ArrayList<>();
		for (WaterRegion r : this.regions) {
			WaterRegion.Tally t = r.lastTally;
			if (t != null) {
				r.lastTally = null;
				out.add(new LeafTally(t.frame(), t.window(), t.leaves(), t.complete()));
			}
		}
		return out;
	}

	private void addMaterial(BlockPos pos, double y0, double y1, Material material) {
		this.addMaterial(pos, y0, y1, material, 0.0F);
	}

	private void addIce(BlockPos pos, double y0, double y1) {
		this.addMaterial(pos, y0, y1, Material.WATER, -LATENT_FREEZE - 6.0F);
	}

	private void addMaterial(BlockPos pos, double y0, double y1, Material material, float heat) {
		if (y1 <= y0 || !this.vulkanReady()) {
			return;
		}
		WaterRegion r = this.regionFor(pos, material);
		r.addEmitter(new WaterRegion.Emitter(pos.getX(), pos.getY() + y0, pos.getZ(), pos.getX() + 1, pos.getY() + y1, pos.getZ() + 1, 0, 0, 0, heat));
		r.owned.add(pos.asLong());
	}

	public void addEmitter(WaterRegion.Emitter e, Material material) {
		if (!this.vulkanReady()) {
			return;
		}
		BlockPos center = BlockPos.containing((e.minX() + e.maxX()) / 2, (e.minY() + e.maxY()) / 2, (e.minZ() + e.maxZ()) / 2);
		this.regionFor(center, material).addEmitter(e);
	}

	private void removeMaterial(BlockPos pos, double y0, double y1, Material material) {
		for (WaterRegion r : this.regions) {
			if (r.material == material && (r.windowContains(pos, 1) || r.claims(pos, 1))) {
				r.removals.add(new WaterRegion.Removal(pos.getX(), pos.getY() + y0, pos.getZ(), pos.getX() + 1, pos.getY() + y1, pos.getZ() + 1));
				if (y0 <= 0.0) {
					r.owned.remove(pos.asLong());
				}
			}
		}
	}

	private List<WaterRegion.PortalSink> portalSinks(ClientLevel level, WaterRegion r, LongOpenHashSet portalBlocks) {
		if (portalBlocks.isEmpty() || !r.hasWindow) {
			return List.of();
		}
		LongOpenHashSet inside = new LongOpenHashSet();
		for (LongIterator it = portalBlocks.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			int x = BlockPos.getX(key) - r.ox;
			int y = BlockPos.getY(key) - r.oy;
			int z = BlockPos.getZ(key) - r.oz;
			if (x >= 0 && y >= 0 && z >= 0 && x < r.sx && y < r.sy && z < r.sz) {
				inside.add(key);
			}
		}
		if (inside.isEmpty()) {
			return List.of();
		}
		List<BlockPos> starts = new ArrayList<>(inside.size());
		for (LongIterator it = inside.iterator(); it.hasNext(); ) {
			starts.add(BlockPos.of(it.nextLong()));
		}
		starts.sort(java.util.Comparator.<BlockPos>comparingInt(p -> p.getY()).thenComparingInt(p -> p.getZ()).thenComparingInt(p -> p.getX()));
		LongOpenHashSet used = new LongOpenHashSet();
		List<WaterRegion.PortalSink> out = new ArrayList<>();
		for (BlockPos start : starts) {
			if (used.contains(start.asLong())) {
				continue;
			}
			if (out.size() >= WaterRegion.MAX_PORTAL_BOXES) {
				break;
			}
			Block kind = level.getBlockState(start).getBlock();
			int x0 = start.getX();
			int y0 = start.getY();
			int z0 = start.getZ();
			int x1 = x0;
			int y1 = y0;
			int z1 = z0;
			while (portalMember(level, inside, used, kind, x1 + 1, y0, y0, z0, z0)) {
				x1++;
			}
			while (portalRow(level, inside, used, kind, x0, x1, y1 + 1, y1 + 1, z0, z0)) {
				y1++;
			}
			while (portalRow(level, inside, used, kind, x0, x1, y0, y1, z1 + 1, z1 + 1)) {
				z1++;
			}
			for (int z = z0; z <= z1; z++) {
				for (int y = y0; y <= y1; y++) {
					for (int x = x0; x <= x1; x++) {
						used.add(BlockPos.asLong(x, y, z));
					}
				}
			}
			out.add(new WaterRegion.PortalSink(new int[]{x0, y0, z0, x1 + 1, y1 + 1, z1 + 1}, start.asLong()));
		}
		return out;
	}

	private static boolean portalRow(ClientLevel level, LongOpenHashSet inside, LongOpenHashSet used, Block kind, int x0, int x1, int y0, int y1, int z0,
		int z1) {
		for (int x = x0; x <= x1; x++) {
			if (!portalMember(level, inside, used, kind, x, y0, y1, z0, z1)) {
				return false;
			}
		}
		return true;
	}

	private static boolean portalMember(ClientLevel level, LongOpenHashSet inside, LongOpenHashSet used, Block kind, int x, int y0, int y1, int z0,
		int z1) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = z0; z <= z1; z++) {
			for (int y = y0; y <= y1; y++) {
				long key = BlockPos.asLong(x, y, z);
				if (!inside.contains(key) || used.contains(key) || level.getBlockState(pos.set(x, y, z)).getBlock() != kind) {
					return false;
				}
			}
		}
		return true;
	}

	private void sendPortalTransit(WaterRegion r) {
		if (r.portalTaken.isEmpty()) {
			return;
		}
		if (isHost() && ClientPlayNetworking.canSend(PortalTransitPayload.TYPE)) {
			double perEighth = r.material.isLeaves() ? Math.max(1, WaterSettings.leavesPerBlock) / 8.0 : (double) r.res * r.res * r.res;
			long[] portals = new long[r.portalTaken.size()];
			float[] eighths = new float[portals.length];
			int i = 0;
			for (Long2IntMap.Entry e : r.portalTaken.long2IntEntrySet()) {
				portals[i] = e.getLongKey();
				eighths[i] = (float) (e.getIntValue() / perEighth);
				i++;
			}
			Identifier dimension = r.background != null ? r.background.dimension
				: this.simLevel != null ? this.simLevel.dimension().identifier() : null;
			if (dimension != null) {
				ClientPlayNetworking.send(new PortalTransitPayload(dimension, r.material.syncId(), portals, eighths));
			}
		}
		r.portalTaken.clear();
	}

	private void addSource(BlockPos pos, Material material) {
		BlockPos p = pos.immutable();
		if (this.sources.containsKey(p) || !this.vulkanReady()) {
			return;
		}
		if (this.sources.size() >= MAX_SOURCES) {
			BlockPos oldest = this.sources.keySet().iterator().next();
			this.sources.remove(oldest);
		}
		this.sources.put(p, material);
		this.regionFor(p, material).claimBlock(p);
	}

	private void feedFromVanilla(ClientLevel level, BlockPos pos, BlockState state) {
		if (!WaterSettings.vanillaWaterExchange || !isHost() || state.getBlock() != Blocks.WATER
			|| state.getFluidState().getType() != Fluids.FLOWING_WATER || this.vanillaFeeds.contains(pos.asLong())) {
			return;
		}
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		boolean touches = false;
		for (Direction d : Direction.values()) {
			if (d != Direction.UP && waterAmount(level.getBlockState(n.setWithOffset(pos, d))) > 0) {
				touches = true;
				break;
			}
		}
		if (!touches) {
			return;
		}
		int amount = state.getFluidState().getAmount();
		this.vanillaFeeds.add(pos.asLong());
		this.addWater(pos.immutable(), 0.0, amount >= 8 ? 1.0 : amount / 8.0);
	}

	private void sendVanillaFeeds() {
		if (this.vanillaFeeds.isEmpty()) {
			return;
		}
		if (ClientPlayNetworking.canSend(VanillaFeedPayload.TYPE)) {
			LongArrayList all = new LongArrayList(this.vanillaFeeds);
			for (int start = 0; start < all.size(); start += VanillaFeedPayload.MAX_ENTRIES) {
				int n = Math.min(VanillaFeedPayload.MAX_ENTRIES, all.size() - start);
				ClientPlayNetworking.send(new VanillaFeedPayload(all.subList(start, start + n).toLongArray()));
			}
		}
		this.vanillaFeeds.clear();
	}

	public void removeAll(int radiusChunks, Material material) {
		if (ClientPlayNetworking.canSend(RemoveAllWaterPayload.TYPE)) {
			ClientPlayNetworking.send(new RemoveAllWaterPayload(radiusChunks, material.syncId()));
		}
		for (WaterRegion r : List.copyOf(this.regions)) {
			if (r.material == material && r.ocean == null) {
				this.regions.remove(r);
				r.close();
			}
		}
		this.echoes(material).clear();
		this.sources.entrySet().removeIf(e -> e.getValue() == material);
	}

	/** Drops every fire flame, smoke and spark particle region right away (local only; nothing is synced for these). */
	public void removeFire() {
		for (WaterRegion r : List.copyOf(this.regions)) {
			if (r.material.isFireGas() && r.ocean == null) {
				this.regions.remove(r);
				r.close();
			}
		}
		for (Material m : new Material[]{Material.SMOKE, Material.FLAME, Material.SOUL_FLAME, Material.SPARK}) {
			this.echoes(m).clear();
		}
		this.sources.entrySet().removeIf(e -> e.getValue().isFireGas());
	}

	public void reload(ClientLevel level) {
		this.closeRegions();
		this.chunkScanQueue.clear();
		int radius = Minecraft.getInstance().options.renderDistance().get();
		ChunkPos center = ChunkPos.containing(Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.blockPos);
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				this.chunkScanQueue.add(new ChunkPos(center.x() + dx, center.z() + dz));
			}
		}
	}

	private void closeRegions() {
		for (WaterRegion r : this.regions) {
			r.close();
		}
		this.regions.clear();
		this.waterEchoes.clear();
		this.sandEchoes.clear();
		this.lavaEchoes.clear();
		this.slimeEchoes.clear();
		this.leafEchoes.clear();
		this.pendingFills.clear();
		this.latestFills.clear();
		this.recentMixes.clear();
		this.sounds.clear();
	}


	public void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
		if (oldState != newState) {
			RealisticGrass.get().onBlockChanged(level, pos, oldState, newState);
			me.skaffy.client.trees.RealisticTrees.get().onBlockChanged(level, pos, oldState, newState);
			me.skaffy.client.jelly.JellyWorld.onBlockChanged(level, pos, newState);
			me.skaffy.client.ice.IceWorld.onBlockChanged(level, pos, oldState, newState);
		}
		if (oldState == newState || !this.ownsEvent(level)) {
			return;
		}
		if (OceanMode.active() && !sameRestView(oldState, newState)) {
			this.oceanSurfaces.blockChanged(pos);
		}
		Material oldSpring = springMaterial(oldState);
		Material newSpring = springMaterial(newState);
		long key = pos.asLong();
		if (newSpring != null) {
			this.addSource(pos, newSpring);
		} else if (oldSpring != null) {
			this.sources.remove(pos);
		}
		int waterBefore = waterAmount(oldState);
		int waterAfter = waterAmount(newState);
		if (waterBefore != waterAfter) {
			if (this.waterEchoes.consume(key, waterAfter)) {
			} else if (waterAfter > waterBefore && RealisticWaterBlock.isFrozen(newState)) {
				this.addIce(pos.immutable(), waterBefore / 8.0, waterAfter >= 8 ? 1.0 : waterAfter / 8.0);
			} else if (waterAfter > waterBefore) {
				this.addWater(pos.immutable(), waterBefore / 8.0, waterAfter >= 8 ? 1.0 : waterAfter / 8.0);
			} else {
				this.removeMaterial(pos.immutable(), waterAfter / 8.0, 1.0, Material.WATER);
			}
		}
		int lavaBefore = lavaAmount(oldState);
		int lavaAfter = lavaAmount(newState);
		if (lavaBefore != lavaAfter) {
			if (this.lavaEchoes.consume(key, lavaAfter)) {
			} else if (lavaAfter > lavaBefore) {
				this.addLava(pos.immutable(), lavaBefore / 8.0, lavaAfter >= 8 ? 1.0 : lavaAfter / 8.0);
			} else {
				this.removeMaterial(pos.immutable(), lavaAfter / 8.0, 1.0, Material.LAVA);
			}
		}
		int slimeBefore = slimeAmount(oldState);
		int slimeAfter = slimeAmount(newState);
		if (slimeBefore != slimeAfter) {
			if (this.slimeEchoes.consume(key, slimeAfter)) {
			} else if (slimeAfter > slimeBefore) {
				this.addSlime(pos.immutable(), slimeBefore / 8.0, slimeAfter >= 8 ? 1.0 : slimeAfter / 8.0);
			} else {
				this.removeMaterial(pos.immutable(), slimeAfter / 8.0, 1.0, Material.SLIME);
			}
		}

		if (waterBefore == 0 && waterAfter == 0) {
			this.feedFromVanilla(level, pos, newState);
		} else if (waterBefore == 0 && WaterSettings.vanillaWaterExchange && isHost()) {
			BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
			for (Direction d : Direction.values()) {
				if (d != Direction.DOWN) {
					n.setWithOffset(pos, d);
					this.feedFromVanilla(level, n, level.getBlockState(n));
				}
			}
		}

		int sandBefore = RealisticSandBlock.layers(oldState);
		int sandAfter = RealisticSandBlock.layers(newState);
		if (sandBefore != sandAfter && !this.sandEchoes.consume(key, sandAfter)) {
			if (sandAfter > sandBefore) {
				this.addSand(pos.immutable(), sandBefore / 8.0, sandAfter / 8.0);
			} else if (newState.isAir() && !this.blastedRecently(pos)) {
				this.removeMaterial(pos.immutable(), 0.0, 1.0, Material.SAND);
			}
		}
		if (newState.getBlock() instanceof WaveMakerBlock) {
			this.waveMakers.add(key);
		} else if (oldState.getBlock() instanceof WaveMakerBlock) {
			this.waveMakers.remove(key);
		}
		if (newState.is(ModContent.WATER_DRAIN)) {
			this.waterDrains.add(key);
		} else if (oldState.is(ModContent.WATER_DRAIN)) {
			this.waterDrains.remove(key);
		}
		if (WaterRegion.thermalCode(oldState) != WaterRegion.thermalCode(newState)
			|| WaterRegion.conductsHeat(oldState) != WaterRegion.conductsHeat(newState)) {
			for (WaterRegion r : this.regions) {
				if (r.material == Material.WATER && r.windowContains(pos, 1)) {
					r.refreshLightAt(level, pos);
					// Metal nearby may now carry (or stop carrying) the heat.
					BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
					for (int dx = -3; dx <= 3; dx++) {
						for (int dy = -3; dy <= 3; dy++) {
							for (int dz = -3; dz <= 3; dz++) {
								if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 3 && WaterRegion.conductsHeat(level.getBlockState(near.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz)))) {
									r.refreshLightAt(level, near);
								}
							}
						}
					}
					r.wake(this.frameCounter);
				}
			}
		}
		if (newState.getBlock() instanceof Portal) {
			this.portalBlocks.add(key);
		} else if (oldState.getBlock() instanceof Portal) {
			this.portalBlocks.remove(key);
		}
		int leavesBefore = RealisticLeavesBlock.layers(oldState);
		int leavesAfter = RealisticLeavesBlock.layers(newState);
		if (leavesBefore != leavesAfter && !this.leafEchoes.consume(key, leavesAfter)) {
			if (leavesAfter > leavesBefore) {
				this.addLeaves(pos.immutable(), leavesBefore / 8.0, leavesAfter / 8.0);
			} else if (newState.isAir() && !this.blastedRecently(pos)) {
				this.removeMaterial(pos.immutable(), 0.0, 1.0, Material.LEAVES);
			}
		}

		if (oldState.getBlock() != newState.getBlock() || !oldState.getCollisionShape(level, pos).equals(newState.getCollisionShape(level, pos))) {
			for (Material m : Material.values()) {
				this.solids(m);
			}
			for (SolidCache cache : this.solidCaches.values()) {
				if (cache.update(level, pos)) {
					for (WaterRegion r : this.regions) {
						if (this.solids(r.material, r.res) == cache) {
							r.patchSolid(level, cache, pos);
						}
					}
				}
			}
		}
	}

	public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		if (!this.ownsEvent(level)) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		for (SolidCache cache : this.solidCaches.values()) {
			cache.invalidateChunk(pos.x(), pos.z());
		}
		for (WaterRegion r : this.regions) {
			if (r.hasWindow && r.overlapsChunk(pos.x(), pos.z())) {
				r.markSolidsDirty(pos.x(), pos.z());
			}
		}
		this.chunkScanQueue.add(pos);
		if (OceanMode.active()) {
			this.oceanSurfaces.chunkLoaded(pos.x(), pos.z());
		}
	}

	public void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		if (level != this.simLevel) {
			return;
		}
		this.oceanSurfaces.chunkUnloaded(chunk.getPos().x(), chunk.getPos().z());
		int drainChunkX = chunk.getPos().x();
		int drainChunkZ = chunk.getPos().z();
		this.waterDrains.removeIf((long key) -> SectionPos.blockToSectionCoord(BlockPos.getX(key)) == drainChunkX
			&& SectionPos.blockToSectionCoord(BlockPos.getZ(key)) == drainChunkZ);
		for (SolidCache cache : this.solidCaches.values()) {
			cache.invalidateChunk(chunk.getPos().x(), chunk.getPos().z());
		}
		if (!this.portalBlocks.isEmpty()) {
			int cx = chunk.getPos().x();
			int cz = chunk.getPos().z();
			this.portalBlocks.removeIf((long key) -> SectionPos.blockToSectionCoord(BlockPos.getX(key)) == cx
				&& SectionPos.blockToSectionCoord(BlockPos.getZ(key)) == cz);
		}
	}

	private boolean owned(long key, Material material) {
		for (WaterRegion r : this.regions) {
			if (r.material == material && r.owned.contains(key)) {
				return true;
			}
		}
		return false;
	}

	private void scanChunk(ClientLevel level, ChunkPos chunkPos) {
		if (!level.hasChunk(chunkPos.x(), chunkPos.z())) {
			return;
		}
		LevelChunk chunk = level.getChunk(chunkPos.x(), chunkPos.z());
		LevelChunkSection[] sections = chunk.getSections();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int s = 0; s < sections.length; s++) {
			LevelChunkSection section = sections[s];
			if (section.hasOnlyAir()
				|| !section.maybeHas(state -> state.getBlock() == ModContent.REALISTIC_WATER_BLOCK
					|| state.getBlock() == ModContent.REALISTIC_LAVA_BLOCK
					|| state.getBlock() == ModContent.REALISTIC_SLIME_BLOCK
					|| state.getBlock() == ModContent.REALISTIC_SAND
					|| state.getBlock() == ModContent.REALISTIC_LEAVES
					|| state.getBlock() == ModContent.WAVE_MAKER
					|| state.getBlock() == ModContent.WATER_DRAIN
					|| state.getBlock() instanceof Portal)) {
				continue;
			}
			int baseY = (level.getMinSectionY() + s) << 4;
			for (int i = 0; i < 4096; i++) {
				BlockState state = section.getBlockState(i & 15, (i >> 4) & 15, i >> 8);
				if (state.getBlock() instanceof Portal) {
					this.portalBlocks.add(BlockPos.asLong((chunkPos.x() << 4) + (i & 15), baseY + ((i >> 4) & 15), (chunkPos.z() << 4) + (i >> 8)));
					continue;
				}
				int water = waterAmount(state);
				int lava = lavaAmount(state);
				int slime = slimeAmount(state);
				int sand = RealisticSandBlock.layers(state);
				int leaves = RealisticLeavesBlock.layers(state);
				if (state.getBlock() == ModContent.WAVE_MAKER) {
					this.waveMakers.add(BlockPos.asLong((chunkPos.x() << 4) + (i & 15), baseY + ((i >> 4) & 15), (chunkPos.z() << 4) + (i >> 8)));
					continue;
				}
				if (state.is(ModContent.WATER_DRAIN)) {
					this.waterDrains.add(BlockPos.asLong((chunkPos.x() << 4) + (i & 15), baseY + ((i >> 4) & 15), (chunkPos.z() << 4) + (i >> 8)));
					continue;
				}
				if (water == 0 && lava == 0 && slime == 0 && sand == 0 && leaves == 0) {
					continue;
				}
				pos.set((chunkPos.x() << 4) + (i & 15), baseY + ((i >> 4) & 15), (chunkPos.z() << 4) + (i >> 8));
				Material spring = springMaterial(state);
				if (spring != null) {
					this.addSource(pos, spring);
					continue;
				}
				long key = pos.asLong();
				if (water > 0 && !this.owned(key, Material.WATER)) {
					if (RealisticWaterBlock.isFrozen(state)) {
						this.addIce(pos.immutable(), 0.0, water >= 8 ? 1.0 : water / 8.0);
					} else {
						this.addWater(pos.immutable(), 0.0, water >= 8 ? 1.0 : water / 8.0);
					}
				}
				if (lava > 0 && !this.owned(key, Material.LAVA)) {
					this.addLava(pos.immutable(), 0.0, lava >= 8 ? 1.0 : lava / 8.0);
				}
				if (slime > 0 && !this.owned(key, Material.SLIME)) {
					this.addSlime(pos.immutable(), 0.0, slime >= 8 ? 1.0 : slime / 8.0);
				}
				if (sand > 0 && !this.owned(key, Material.SAND)) {
					this.addSand(pos.immutable(), 0.0, sand / 8.0);
				}
				if (leaves > 0 && !this.owned(key, Material.LEAVES)) {
					int perBlock = Math.max(1, WaterSettings.leavesPerBlock);
					int total = leaves * perBlock / 8;
					BlockPos at = pos.immutable();
					int tagged = me.skaffy.client.trees.FallenLeaves.get().restore(level, at, total);
					if (tagged < total) {
						this.addLeaves(at, 0.0, (total - tagged) / (double) perBlock);
					} else if (tagged > 0) {
						this.regionFor(at, Material.LEAVES).owned.add(key);
					}
				}
			}
		}
	}

	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		this.followLevel(level);
		if (level == null) {
			return;
		}
		if (WaterSettings.resolution != this.builtResolution
			|| WaterSettings.sandResolution != this.builtSandResolution
			|| WaterSettings.lavaResolution != this.builtLavaResolution
			|| WaterSettings.slimeResolution != this.builtSlimeResolution
			|| WaterSettings.leafResolution != this.builtLeafResolution) {
			this.builtResolution = WaterSettings.resolution;
			this.builtSandResolution = WaterSettings.sandResolution;
			this.builtLavaResolution = WaterSettings.lavaResolution;
			this.builtSlimeResolution = WaterSettings.slimeResolution;
			this.builtLeafResolution = WaterSettings.leafResolution;
			this.reload(level);
		}
		for (int i = 0; i < 4 && !this.chunkScanQueue.isEmpty(); i++) {
			this.scanChunk(level, this.chunkScanQueue.poll());
		}
		this.pumpCacheFrame = -1L;
		if (OceanMode.active()) {
			this.oceanSurfaces.tick(level, mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos);
			this.oceanSim.tick(mc, level);
		}
		this.waveMakerSounds.tick(mc, this.pumps(level));
		this.boilSounds.tick(mc);
		this.waterEchoes.expire(this.frameCounter);
		this.sandEchoes.expire(this.frameCounter);
		this.lavaEchoes.expire(this.frameCounter);
		this.slimeEchoes.expire(this.frameCounter);
		this.leafEchoes.expire(this.frameCounter);
		Vec3 listener = mc.player != null ? mc.player.getEyePosition() : null;
		for (Object[] f : this.pendingFills) {
			WaterRegion r = (WaterRegion) f[0];
			WaterRegion.Fill fill = (WaterRegion.Fill) f[1];
			if (r.background != null) {
				if (isHost() && this.background.contains(r)) {
					this.syncBackground(r, fill);
				}
				continue;
			}
			if (!this.regions.contains(r)) {
				continue;
			}
			if (r.ocean != null) {
				continue;
			}
			if (listener != null) {
				this.sounds.onFill(r.material, fill, listener);
			}
			if (isHost()) {
				this.syncBlocks(level, r, fill);
				if (r.material == Material.WATER) {
					this.collectScalding(fill);
				}
			}
		}
		boolean mixFill = false;
		for (Object[] f : this.pendingFills) {
			WaterRegion r = (WaterRegion) f[0];
			if (this.regions.contains(r) && (r.material == Material.WATER || r.material == Material.LAVA)) {
				this.latestFills.put(r, (WaterRegion.Fill) f[1]);
				mixFill = true;
			}
		}
		this.pendingFills.clear();
		if (mixFill && isHost() && WaterSettings.lavaWaterMixing) {
			this.mixFluids(level);
		}
		this.sendVanillaFeeds();
		this.sounds.tick(level);
		this.sendBodyFlows();
		if (level.getGameTime() % 10L == 0L) {
			this.sendBoiling();
		}
		if (this.rain != null && mc.player != null) {
			this.rain.tick(level, mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos);
		}
		if (WaterSettings.realisticRain && isHost() && level.getGameTime() % 20L == 0L && level.isRaining()) {
			this.feedRain(level);
		}
	}

	private void feedRain(ClientLevel level) {
		RandomSource random = level.getRandom();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (WaterRegion r : this.regions) {
			if (r.material != Material.WATER || r.boxWorld == null || r.ocean != null) {
				continue;
			}
			int[] b = r.boxWorld;
			int x0 = Math.floorDiv(b[0], r.res);
			int z0 = Math.floorDiv(b[2], r.res);
			int dx = Math.max(1, Math.ceilDiv(b[3], r.res) - x0);
			int dz = Math.max(1, Math.ceilDiv(b[5], r.res) - z0);
			float share = WaterSettings.rainFeed / 60.0F * dx * dz / RAIN_FEED_SAMPLES;
			double layer = 1.0 / (2.0 * r.res);
			for (int k = 0; k < RAIN_FEED_SAMPLES; k++) {
				int x = x0 + random.nextInt(dx);
				int z = z0 + random.nextInt(dz);
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
				pos.set(x, top - 1, z);
				FluidState fluid = level.getFluidState(pos);
				if (!RealisticWaterFluid.isRealistic(fluid) || !level.isRainingAt(pos.set(x, top, z))) {
					continue;
				}
				r.rainCredit += share;
				if (r.rainCredit >= layer) {
					r.rainCredit -= layer;
					double y = top - 1 + fluid.getHeight(level, pos.set(x, top - 1, z));
					r.addEmitter(new WaterRegion.Emitter(x, y, z, x + 1, y + layer, z + 1, 0.0F, -2.0F * r.res, 0.0F));
				}
			}
		}
	}

	public void renderRain(@Nullable FrameTarget target) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (target == null || level == null || this.failed || !WaterSettings.realisticRain || Vk.tryDevice() == null) {
			return;
		}
		if (this.rain == null) {
			this.rain = new RainSystem();
		}
		if (!this.rain.wanted(level)) {
			return;
		}
		try {
			long lightmap = mc.gameRenderer.lightmap() instanceof VulkanGpuTextureView lightmapView ? lightmapView.vkImageView() : target.depthView();
			VkCommandBuffer cb = Vk.encoder().allocateAndBeginTransientCommandBuffer();
			Vk.beginRecording(cb);
			this.rain.record(cb, target, mc.gameRenderer.gameRenderState().levelRenderState, level, lightmap);
			Vk.endRecording();
			Vk.check(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer");
			Vk.encoder().execute(cb);
			for (double[] puff : this.rain.takeSteam()) {
				this.addSteam(puff[0], puff[1] + 0.05, puff[2], (float) puff[3]);
			}
		} catch (RuntimeException e) {
			Vk.endRecording();
			WaterSettings.realisticRain = false;
			RealisticWater.LOGGER.error("Realistic rain failed and was turned off", e);
		}
	}


	private static int targetAmount(float fill, int current) {
		int target;
		if (fill >= 0.8F) {
			target = 8;
		} else if (fill >= 0.12F) {
			target = Mth.clamp(Math.round(fill * 8.0F), 1, 7);
		} else {
			target = 0;
		}
		if (current > 0 && target == 0 && fill > 0.05F) {
			return Math.min(current, 1);
		}
		if (current == 0 && target > 0 && fill < 0.2F) {
			return 0;
		}
		if (current > 0 && target > 0 && current < 8 && target < 8 && Math.abs(target - current) <= 1) {
			return current;
		}
		return target;
	}

	private static int targetLayers(float fill, float speed, int current) {
		float raw = fill * RealisticSandBlock.MAX_LAYERS;
		if (speed > 1.5F && raw > current) {
			return current;
		}
		int target = raw < 0.5F ? 0 : Mth.clamp(Math.round(raw), 1, RealisticSandBlock.MAX_LAYERS);
		if (current > 0 && target == 0 && raw > 0.25F) {
			return current;
		}
		if (current > 0 && target > 0 && Math.abs(raw - current) < 0.75F) {
			return current;
		}
		return target;
	}

	private static final int FROZEN_SHARE = 124;
	private static final int FROZEN_KEEP_SHARE = 110;

	private void syncBlocks(ClientLevel level, WaterRegion r, WaterRegion.Fill f) {
		Material material = r.material;
		boolean sand = material.usesLayers();
		Echoes echoes = this.echoes(material);
		LongArrayList positions = new LongArrayList();
		ByteArrayList amounts = new ByteArrayList();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int i = 0;
		for (int z = 0; z < f.dz(); z++) {
			for (int y = 0; y < f.dy(); y++) {
				for (int x = 0; x < f.dx(); x++, i++) {
					pos.set(f.x() + x, f.y() + y, f.z() + z);
					if (level.isOutsideBuildHeight(pos)) {
						continue;
					}
					long key = pos.asLong();
					BlockState state = level.getBlockState(pos);
					int current = amountOf(state, material);
					if ((current == 0 && !state.isAir()) || RealisticWaterBlock.isInfinite(state)) {
						continue;
					}
					if (echoes.expected.containsKey(key)) {
						current = echoes.expected.get(key);
					}
					int target = sand ? targetLayers(f.fullness(i), f.blocksPerSecond(i), current) : targetAmount(f.fullness(i), current);
					boolean wasFrozen = material == Material.WATER && RealisticWaterBlock.isFrozen(state);
					boolean frozen = material == Material.WATER && target > 0 && WaterSettings.waterFreezing
						&& f.iceShare(i) >= (wasFrozen ? FROZEN_KEEP_SHARE : FROZEN_SHARE);
					if (target != current || frozen != wasFrozen && target > 0) {
						positions.add(key);
						amounts.add((byte) (target | (frozen ? WaterSyncPayload.FROZEN_BIT : 0)));
						echoes.expect(key, target, this.frameCounter);
					}
					if (target > 0) {
						r.owned.add(key);
					} else {
						r.owned.remove(key);
					}
				}
			}
		}
		LongArrayList gone = new LongArrayList();
		for (LongIterator it = r.owned.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			if (!inFill(f, key)) {
				gone.add(key);
			}
		}
		for (long key : gone) {
			r.owned.remove(key);
			this.queueClear(level, key, material, positions, amounts);
		}
		this.send(material, positions, amounts);
	}

	public OceanWaves.Waves calmWaves(ClientLevel level, float partialTick) {
		return OceanWaves.at(level.getGameTime(), partialTick, OceanSim.strength(level));
	}

	public void seaFarCells(int x0, int z0, int size, int cell, int seaTop, int[] out) {
		float[] amp = new float[size * size];
		for (int cz = 0; cz < size; cz++) {
			for (int cx = 0; cx < size; cx++) {
				amp[cx + cz * size] = this.oceanSurfaces.cellAmplitude(x0 + cx * cell, z0 + cz * cell, cell, seaTop);
			}
		}
		java.util.Arrays.fill(out, 0);
		for (int cz = 0; cz < size; cz++) {
			for (int cx = 0; cx < size; cx++) {
				float a = OceanWaves.AMP_MAX;
				for (int dz = -1; dz <= 1 && a > 0.0F; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						int nx = cx + dx;
						int nz = cz + dz;
						a = Math.min(a, nx < 0 || nz < 0 || nx >= size || nz >= size ? 0.0F : amp[nx + nz * size]);
					}
				}
				int q = Mth.clamp((int) Math.floor(a / OceanWaves.AMP_MAX * 15.0F), 0, 15);
				int i = cx + cz * size;
				out[i >> 3] |= q << ((i & 7) * 4);
			}
		}
	}

	public void seaColumns(int x0, int z0, int size, int camY, int[] out) {
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int top = this.oceanSurfaces.restTop(x0 + dx, z0 + dz);
				int word = 0;
				if (OceanSurfaces.hasTop(top) && Math.abs(top - camY) < 255) {
					int amp = Mth.clamp(Math.round(this.oceanSurfaces.amplitude(x0 + dx, z0 + dz) / OceanWaves.AMP_MAX * 31.0F), 0, 31);
					word = (top - camY + 256) | amp << 9 | 512 << 14;
				}
				out[dx + dz * size] = word;
			}
		}
		float band = Math.max(WaterSettings.oceanEdgeBand, 1.0e-3F);
		for (WaterRegion r : this.oceanSim.zones) {
			float[] dist = r.seaDisturbance;
			OceanZone zone = r.ocean;
			if (dist == null || zone == null) {
				continue;
			}
			int res = r.res;
			int blocksW = r.seaDistW / res;
			int blocksD = r.seaDistD / res;
			for (int bz = 0; bz < blocksD; bz++) {
				for (int bx = 0; bx < blocksW; bx++) {
					int gx = r.seaDistX0 + bx - x0;
					int gz = r.seaDistZ0 + bz - z0;
					if (gx < 0 || gz < 0 || gx >= size || gz >= size) {
						continue;
					}
					int at = gx + gz * size;
					int word = out[at];
					if (word == 0 || Math.abs((word & 511) - 256 + camY - zone.seaY) > 2) {
						continue;
					}
					float sum = 0.0F;
					int n = 0;
					for (int cz = 0; cz < res; cz++) {
						for (int cx = 0; cx < res; cx++) {
							float h = dist[bx * res + cx + r.seaDistW * (bz * res + cz)];
							if (h > -1000.0F) {
								sum += h;
								n++;
							}
						}
					}
					if (n == 0) {
						continue;
					}
					float edge = Math.min(Math.min(bx + 0.5F, blocksW - bx - 0.5F), Math.min(bz + 0.5F, blocksD - bz - 0.5F));
					float t = Mth.clamp(edge / band, 0.0F, 1.0F);
					float h = sum / n * (t * t * (3.0F - 2.0F * t));
					int q = Mth.clamp(Math.round(h * 64.0F), -512, 511) + 512;
					out[at] = (word & ~(1023 << 14)) | q << 14;
				}
			}
		}
	}

	public boolean seaDrawsFog() {
		return this.seaDrawsFog;
	}

	private boolean cameraUnderSea(ClientLevel level, Vec3 cam) {
		if (this.oceanSim.cameraInZone(cam)) {
			return false;
		}
		int x = Mth.floor(cam.x);
		int y = Mth.floor(cam.y);
		int z = Mth.floor(cam.z);
		int top = this.oceanSurfaces.restTop(x, z);
		if (!OceanSurfaces.hasTop(top) || y > top + 3) {
			return false;
		}
		BlockState here = level.getBlockState(new BlockPos(x, y, z));
		if (y < top) {
			return OceanWaves.isRestWater(here);
		}
		float partial = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.worldPartialTicks;
		OceanWaves.Waves waves = OceanWaves.at(level.getGameTime(), partial, OceanSim.strength(level));
		double surface = top + OceanWaves.REST_HEIGHT + this.oceanSurfaces.amplitude(x, z) * waves.height(cam.x, cam.z);
		return cam.y < surface && (OceanWaves.isRestWater(here) || OceanWaves.isRestAir(here));
	}

	private static boolean inFill(WaterRegion.Fill f, long key) {
		int x = BlockPos.getX(key) - f.x();
		int y = BlockPos.getY(key) - f.y();
		int z = BlockPos.getZ(key) - f.z();
		return x >= 0 && y >= 0 && z >= 0 && x < f.dx() && y < f.dy() && z < f.dz();
	}

	private static boolean nearMixPartner(WaterRegion r, List<WaterRegion> active) {
		if (!WaterSettings.lavaWaterMixing || (r.material != Material.WATER && r.material != Material.LAVA) || r.boxWorld == null) {
			return false;
		}
		Material partner = r.material == Material.WATER ? Material.LAVA : Material.WATER;
		for (WaterRegion o : active) {
			if (o.material == partner && o.boxWorld != null && blocksTouch(r, o)) {
				return true;
			}
		}
		return false;
	}

	private static boolean blocksTouch(WaterRegion a, WaterRegion b) {
		for (int k = 0; k < 3; k++) {
			double aMin = (double) a.boxWorld[k] / a.res - MIX_REACH;
			double aMax = (double) a.boxWorld[k + 3] / a.res + MIX_REACH;
			if (aMax < (double) b.boxWorld[k] / b.res || (double) b.boxWorld[k + 3] / b.res < aMin) {
				return false;
			}
		}
		return true;
	}

	private static final int[][] NEIGHBORS = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	private static float fillAt(WaterRegion.Fill f, int x, int y, int z) {
		int lx = x - f.x();
		int ly = y - f.y();
		int lz = z - f.z();
		if (lx < 0 || ly < 0 || lz < 0 || lx >= f.dx() || ly >= f.dy() || lz >= f.dz()) {
			return 0.0F;
		}
		return f.fullness((lz * f.dy() + ly) * f.dx() + lx);
	}

	private static float fillAt(List<WaterRegion.Fill> fills, int x, int y, int z) {
		float best = 0.0F;
		for (WaterRegion.Fill f : fills) {
			best = Math.max(best, fillAt(f, x, y, z));
		}
		return best;
	}

	private void mixFluids(ClientLevel level) {
		this.latestFills.keySet().removeIf(r -> !this.regions.contains(r));
		this.recentMixes.long2IntEntrySet().removeIf(e -> this.frameCounter - e.getIntValue() > MIX_MEMORY);
		List<WaterRegion.Fill> lava = new ArrayList<>();
		List<WaterRegion.Fill> water = new ArrayList<>();
		for (Map.Entry<WaterRegion, WaterRegion.Fill> e : this.latestFills.entrySet()) {
			(e.getKey().material == Material.LAVA ? lava : water).add(e.getValue());
		}
		float lavaMin = WaterSettings.lavaMixLavaMin;
		float waterMin = WaterSettings.lavaMixWaterMin;
		LongOpenHashSet contacts = new LongOpenHashSet();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (WaterRegion.Fill f : water) {
			int i = 0;
			for (int z = 0; z < f.dz(); z++) {
				for (int y = 0; y < f.dy(); y++) {
					for (int x = 0; x < f.dx(); x++, i++) {
						if (f.fullness(i) < waterMin) {
							continue;
						}
						int wx = f.x() + x;
						int wy = f.y() + y;
						int wz = f.z() + z;
						boolean edge = isEdge(f, x, y, z, waterMin);
						for (int[] d : NEIGHBORS) {
							int px = wx + d[0];
							int py = wy + d[1];
							int pz = wz + d[2];
							boolean lavaHere = fillAt(lava, px, py, pz) >= lavaMin
								|| (edge && level.getFluidState(pos.set(px, py, pz)).is(FluidTags.LAVA));
							if (lavaHere) {
								contacts.add(BlockPos.asLong(px, py, pz));
							}
							if (!edge) {
								break;
							}
						}
					}
				}
			}
		}
		for (WaterRegion.Fill f : lava) {
			int i = 0;
			for (int z = 0; z < f.dz(); z++) {
				for (int y = 0; y < f.dy(); y++) {
					for (int x = 0; x < f.dx(); x++, i++) {
						if (f.fullness(i) < lavaMin) {
							continue;
						}
						int lx = f.x() + x;
						int ly = f.y() + y;
						int lz = f.z() + z;
						boolean edge = isEdge(f, x, y, z, lavaMin);
						for (int[] d : NEIGHBORS) {
							if (level.getFluidState(pos.set(lx + d[0], ly + d[1], lz + d[2])).is(FluidTags.WATER)) {
								contacts.add(BlockPos.asLong(lx, ly, lz));
								break;
							}
							if (!edge) {
								break;
							}
						}
					}
				}
			}
		}
		LongArrayList fresh = new LongArrayList();
		for (long key : contacts) {
			if (this.recentMixes.containsKey(key)) {
				continue;
			}
			this.recentMixes.put(key, this.frameCounter);
			BlockPos p = BlockPos.of(key);
			BlockState state = level.getBlockState(p);
			if (!FluidMixing.canSolidifyContact(state)) {
				continue;
			}
			fresh.add(key);
			this.removeMaterial(p, 0.0, 1.0, Material.LAVA);
			this.removeMaterial(p, 0.0, 1.0, Material.WATER);
			level.setBlock(p, FluidMixing.resultFor(state).defaultBlockState(), Block.UPDATE_ALL_IMMEDIATE);
		}
		if (fresh.isEmpty() || !ClientPlayNetworking.canSend(FluidMixPayload.TYPE)) {
			return;
		}
		for (int start = 0; start < fresh.size(); start += FluidMixPayload.MAX_ENTRIES) {
			int n = Math.min(FluidMixPayload.MAX_ENTRIES, fresh.size() - start);
			ClientPlayNetworking.send(new FluidMixPayload(fresh.subList(start, start + n).toLongArray()));
		}
	}

	private static boolean isEdge(WaterRegion.Fill f, int x, int y, int z, float min) {
		for (int k = 1; k < NEIGHBORS.length; k++) {
			int[] d = NEIGHBORS[k];
			if (fillAt(f, f.x() + x + d[0], f.y() + y + d[1], f.z() + z + d[2]) < min) {
				return true;
			}
		}
		return false;
	}

	private void queueClear(ClientLevel level, long key, Material material, LongArrayList positions, ByteArrayList amounts) {
		BlockPos pos = BlockPos.of(key);
		if (amountOf(level.getBlockState(pos), material) > 0 && !RealisticWaterBlock.isInfinite(level.getBlockState(pos))) {
			positions.add(key);
			amounts.add((byte) 0);
			this.echoes(material).expect(key, 0, this.frameCounter);
		}
	}

	private void send(Material material, LongArrayList positions, ByteArrayList amounts) {
		if (positions.isEmpty()) {
			return;
		}
		CustomPacketPayload.Type<?> type = switch (material) {
			case SAND -> SandSyncPayload.TYPE;
			case LAVA -> LavaSyncPayload.TYPE;
			case SLIME -> SlimeSyncPayload.TYPE;
			case LEAVES -> LeavesSyncPayload.TYPE;
			default -> WaterSyncPayload.TYPE;
		};
		if (!ClientPlayNetworking.canSend(type)) {
			return;
		}
		int max = switch (material) {
			case SAND -> SandSyncPayload.MAX_ENTRIES;
			case LAVA -> LavaSyncPayload.MAX_ENTRIES;
			case SLIME -> SlimeSyncPayload.MAX_ENTRIES;
			case LEAVES -> LeavesSyncPayload.MAX_ENTRIES;
			default -> WaterSyncPayload.MAX_ENTRIES;
		};
		for (int start = 0; start < positions.size(); start += max) {
			int n = Math.min(max, positions.size() - start);
			long[] p = new long[n];
			byte[] a = new byte[n];
			for (int k = 0; k < n; k++) {
				p[k] = positions.getLong(start + k);
				a[k] = amounts.getByte(start + k);
			}
			CustomPacketPayload payload = switch (material) {
				case SAND -> new SandSyncPayload(p, a);
				case LAVA -> new LavaSyncPayload(p, a);
				case SLIME -> new SlimeSyncPayload(p, a);
				case LEAVES -> new LeavesSyncPayload(p, a);
				default -> new WaterSyncPayload(p, a);
			};
			ClientPlayNetworking.send(payload);
		}
	}


	public void onExplosion(Vec3 center, float radius) {
		if (Minecraft.getInstance().level != null) {
			me.skaffy.client.jelly.JellyWorld.blast(Minecraft.getInstance().level, center, radius);
		}
		double reach = radius * Math.max(WaterSettings.tntBlastReach, 0.0F);
		if (OceanMode.active() && radius > 0.0F) {
			this.oceanSim.explosion(center, radius);
			if (WaterSettings.tntReactions) {
				this.oceanBlasts.add(new double[]{center.x, center.y, center.z, radius, reach, System.nanoTime() + OCEAN_BLAST_WAIT_NANOS});
			}
		}
		if (!WaterSettings.tntReactions || this.regions.isEmpty() || radius <= 0.0F) {
			return;
		}
		this.blasts.add(new double[]{center.x, center.y, center.z, radius, reach});
		this.recentBlasts.add(new double[]{center.x, center.y, center.z, reach + 1.0, System.nanoTime() + BLAST_MEMORY_NANOS});
	}

	private static final long BLAST_MEMORY_NANOS = 2_000_000_000L;
	private static final long OCEAN_BLAST_WAIT_NANOS = 1_000_000_000L;
	private final List<double[]> oceanBlasts = new ArrayList<>();
	private final List<double[]> oceanBlastsTaken = new ArrayList<>();

	private void settleOceanBlasts() {
		this.oceanBlasts.removeAll(this.oceanBlastsTaken);
		this.oceanBlastsTaken.clear();
		long now = System.nanoTime();
		this.oceanBlasts.removeIf(b -> b[5] < now);
	}

	private boolean blastedRecently(BlockPos pos) {
		long now = System.nanoTime();
		this.recentBlasts.removeIf(b -> b[4] < now);
		for (double[] b : this.recentBlasts) {
			if (Vec3.atCenterOf(pos).distanceToSqr(b[0], b[1], b[2]) < b[3] * b[3]) {
				return true;
			}
		}
		return false;
	}

	private List<float[]> blastsFor(WaterRegion r) {
		List<float[]> out = new ArrayList<>();
		int[] box = r.boxWorld;
		List<double[]> blasts = r.ocean != null ? this.oceanBlasts : this.blasts;
		if (box == null || blasts.isEmpty()) {
			return out;
		}
		double h = 1.0 / r.res;
		for (double[] b : blasts) {
			double reach = b[4];
			double dx = Math.max(Math.max(box[0] * h - b[0], b[0] - box[3] * h), 0.0);
			double dy = Math.max(Math.max(box[1] * h - b[1], b[1] - box[4] * h), 0.0);
			double dz = Math.max(Math.max(box[2] * h - b[2], b[2] - box[5] * h), 0.0);
			if (reach <= 0.0 || dx * dx + dy * dy + dz * dz >= reach * reach) {
				continue;
			}
			if (r.ocean != null) {
				if (!r.ocean.contains((int) Math.floor(b[0]), (int) Math.floor(b[2])) || r.lastFluidCount <= 0) {
					continue;
				}
				this.oceanBlastsTaken.add(b);
			}
			float speed = WaterSettings.tntBlastSpeed * (float) (b[3] / 4.0) * r.res;
			out.add(new float[]{
				(float) ((b[0] - r.ox) * r.res), (float) ((b[1] - r.oy) * r.res), (float) ((b[2] - r.oz) * r.res), (float) (reach * r.res),
				speed, WaterSettings.tntUpwardBias, WaterSettings.tntScatter
			});
		}
		return out;
	}

	public void renderFrame(@Nullable FrameTarget target) {
		Minecraft mc = Minecraft.getInstance();
		this.followLevel(mc.level);
		if (this.regions.isEmpty() && this.background.isEmpty() && !OceanMode.active() && !RealisticGrass.get().wanted()
			&& !me.skaffy.client.trees.RealisticTrees.get().wanted() && !me.skaffy.client.jelly.JellyWorld.wanted()
			&& !me.skaffy.client.ice.IceWorld.wanted() || this.failed || mc.level == null
			|| Vk.tryDevice() == null) {
			this.lastFrameNanos = 0L;
			this.blasts.clear();
			this.oceanBlasts.clear();
			this.oceanBlastsTaken.clear();
			return;
		}
		try {
			this.renderFrameUnsafe(mc, mc.level, target);
		} catch (RuntimeException e) {
			Vk.endRecording();
			this.failed = true;
			RealisticWater.LOGGER.error("Realistic Water frame failed; disabling the simulation", e);
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal("Realistic Water failed: " + e.getMessage()));
			}
		} finally {
			this.blasts.clear();
			this.settleOceanBlasts();
		}
	}

	private final Long2FloatOpenHashMap pendingSteam = new Long2FloatOpenHashMap();
	private final Long2IntOpenHashMap boilingBlocks = new Long2IntOpenHashMap();
	private final Long2IntOpenHashMap scaldingDegrees = new Long2IntOpenHashMap();
	private static final int SCALD_FROM = 60;
	private static final float SCALD_FULLNESS = 0.05F;
	private static final int STEAM_FLUSH_FRAMES = 5;
	private static final int BOILING_MEMORY_FRAMES = 90;
	private static final float WISP_EIGHTHS = 0.01F;

	private void collectHeat(ClientLevel level, WaterRegion r) {
		if (r.boiled.isEmpty() && r.wisps.isEmpty() && r.simmering.isEmpty()) {
			return;
		}
		float perCube = 1.0F / (r.res * r.res * r.res);
		for (Long2IntMap.Entry e : r.boiled.long2IntEntrySet()) {
			this.boilSounds.add(e.getLongKey(), e.getIntValue() * perCube, 0.0F);
		}
		for (Long2IntMap.Entry e : r.simmering.long2IntEntrySet()) {
			this.boilSounds.add(e.getLongKey(), 0.0F, e.getIntValue() * perCube);
		}
		r.simmering.clear();
		float perParticle = WaterSettings.boilSteam / (r.res * r.res * r.res);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Long2IntMap.Entry e : r.boiled.long2IntEntrySet()) {
			long top = this.waterTop(level, e.getLongKey(), pos);
			this.pendingSteam.addTo(top, e.getIntValue() * perParticle);
		}
		for (Long2IntMap.Entry e : r.wisps.long2IntEntrySet()) {
			long top = this.waterTop(level, e.getLongKey(), pos);
			this.pendingSteam.addTo(top, e.getIntValue() * WISP_EIGHTHS * WaterSettings.boilSteam);
		}
		r.boiled.clear();
		r.wisps.clear();
	}

	private long waterTop(ClientLevel level, long key, BlockPos.MutableBlockPos pos) {
		pos.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
		long top = key;
		for (int k = 0; k < 64 && RealisticWaterFluid.isRealistic(level.getFluidState(pos)); k++) {
			top = pos.asLong();
			pos.move(Direction.UP);
		}
		return top;
	}

	private void collectScalding(WaterRegion.Fill f) {
		int i = 0;
		for (int z = 0; z < f.dz(); z++) {
			for (int y = 0; y < f.dy(); y++) {
				for (int x = 0; x < f.dx(); x++, i++) {
					int degrees = f.degrees(i);
					if (degrees >= SCALD_FROM && f.fullness(i) >= SCALD_FULLNESS) {
						long key = BlockPos.asLong(f.x() + x, f.y() + y, f.z() + z);
						this.boilingBlocks.put(key, this.frameCounter);
						this.scaldingDegrees.put(key, degrees);
					}
				}
			}
		}
	}

	private void flushSteam(ClientLevel level) {
		if (this.pendingSteam.isEmpty() || this.frameCounter % STEAM_FLUSH_FRAMES != 0) {
			return;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (Long2FloatMap.Entry e : this.pendingSteam.long2FloatEntrySet()) {
			float eighths = e.getFloatValue();
			if (eighths < 0.002F) {
				continue;
			}
			pos.set(e.getLongKey());
			FluidState fluid = level.getFluidState(pos);
			double y = pos.getY() + (RealisticWaterFluid.isRealistic(fluid) ? fluid.getHeight(level, pos) : 0.0);
			this.addSteam(pos.getX() + 0.5, y, pos.getZ() + 0.5, eighths);
		}
		this.pendingSteam.clear();
	}

	private void sendBoiling() {
		if (this.boilingBlocks.isEmpty()) {
			return;
		}
		this.boilingBlocks.long2IntEntrySet().removeIf(e -> this.frameCounter - e.getIntValue() > BOILING_MEMORY_FRAMES);
		this.scaldingDegrees.keySet().removeIf(key -> !this.boilingBlocks.containsKey(key));
		if (this.boilingBlocks.isEmpty() || WaterSettings.boilingDamage <= 0.0F || !isHost() || !ClientPlayNetworking.canSend(BoilingWaterPayload.TYPE)) {
			return;
		}
		long[] all = this.boilingBlocks.keySet().toLongArray();
		for (int start = 0; start < all.length; start += BoilingWaterPayload.MAX_ENTRIES) {
			int n = Math.min(BoilingWaterPayload.MAX_ENTRIES, all.length - start);
			long[] positions = java.util.Arrays.copyOfRange(all, start, start + n);
			byte[] degrees = new byte[n];
			for (int k = 0; k < n; k++) {
				degrees[k] = (byte) Math.min(this.scaldingDegrees.get(positions[k]), 255);
			}
			ClientPlayNetworking.send(new BoilingWaterPayload(positions, degrees, WaterSettings.boilingDamage));
		}
	}
	
	private static final double[][] FLOW_SAMPLES = {{1.5, 0.0, 0.0}, {-1.5, 0.0, 0.0}, {0.0, 0.0, 1.5}, {0.0, 0.0, -1.5}, {0.0, -1.5, 0.0}};

	private record FlowProbe(float[] points, int[] owners, int count) {
	}

	private static @Nullable FlowProbe flowProbe(ClientLevel level, WaterRegion r) {
		if (!r.hasWindow) {
			return null;
		}
		AABB window = new AABB(r.ox, r.oy, r.oz, r.ox + r.sx, r.oy + r.sy, r.oz + r.sz);
		float[] points = new float[WaterRegion.MAX_FLOW_POINTS * 3];
		int[] owners = new int[WaterRegion.MAX_FLOW_POINTS];
		int n = 0;
		Vector3d c = new Vector3d();
		for (RealisticBlockEntity e : RealisticBlockEntity.near(level, window)) {
			for (RigidBody.Cell cell : e.body.cells) {
				e.body.toWorld(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, c);
				for (double[] d : FLOW_SAMPLES) {
					if (n >= WaterRegion.MAX_FLOW_POINTS || e.body.containsWorld(c.x + d[0], c.y + d[1], c.z + d[2])
						|| e.body.containsWorld(c.x + d[0] * 0.6, c.y + d[1] * 0.6, c.z + d[2] * 0.6)) {
						continue;
					}
					points[n * 3] = (float) ((c.x + d[0] - r.ox) * r.res);
					points[n * 3 + 1] = (float) ((c.y + d[1] - r.oy) * r.res);
					points[n * 3 + 2] = (float) ((c.z + d[2] - r.oz) * r.res);
					owners[n++] = e.getId();
				}
			}
		}
		return n > 0 ? new FlowProbe(points, owners, n) : null;
	}

	private FlowPayload withCalmSea(ClientLevel level, Entity body, FlowPayload flow) {
		if (!OceanMode.active() || !this.oceanSim.cameraInZone(body.position())) {
			return flow;
		}
		int x = Mth.floor(body.getX());
		int z = Mth.floor(body.getZ());
		float amp = this.oceanSurfaces.amplitude(x, z);
		if (amp <= 0.0F) {
			return flow;
		}
		OceanWaves.Waves waves = OceanWaves.at(level.getGameTime(), 0.0F, OceanSim.strength(level));
		double[] v = waves.velocity(body.getX(), body.getZ(), 0.0);
		float surface = Float.isNaN(flow.surfaceY()) ? Float.NaN : flow.surfaceY() + (float) (amp * waves.height(body.getX(), body.getZ()));
		return new FlowPayload(flow.entityId(), flow.vx() + (float) (amp * v[0]), flow.vy() + (float) (amp * v[1]), flow.vz() + (float) (amp * v[2]),
			surface);
	}

	private void sendBodyFlows() {
		if (this.bodyFlows.isEmpty()) {
			return;
		}
		ClientLevel level = Minecraft.getInstance().level;
		boolean send = ClientPlayNetworking.canSend(FlowPayload.TYPE);
		for (Map.Entry<Integer, double[]> e : this.bodyFlows.entrySet()) {
			double[] a = e.getValue();
			if (a[3] > 0.0 || a[5] > 0.0) {
				double n = Math.max(a[3], 1.0);
				boolean surfaced = a[5] > 0.0 && a[5] >= 0.5 * a[6];
				FlowPayload flow = new FlowPayload(e.getKey(), (float) (a[0] / n), (float) (a[1] / n), (float) (a[2] / n),
					surfaced ? (float) (a[4] / a[5]) : Float.NaN);
				if (level != null && level.getEntity(flow.entityId()) instanceof RealisticBlockEntity floating) {
					flow = this.withCalmSea(level, floating, flow);
				}
				if (level != null && level.getEntity(flow.entityId()) instanceof RealisticBlockEntity body && body.isSimulated()) {
					RealisticPhysics.reportFlow(body, flow.vx(), flow.vy(), flow.vz(), flow.surfaceY());
				}
				if (send) {
					ClientPlayNetworking.send(flow);
				}
			}
		}
		this.bodyFlows.clear();
	}

	private void playIgnitions(ClientLevel level, WaterRegion r) {
		BlockPos at = r.ignitionBlock;
		int n = r.ignitions;
		r.ignitions = 0;
		r.ignitionBlock = null;
		if (n <= 0 || at == null || !WaterSettings.leafSounds) {
			return;
		}
		RandomSource random = level.getRandom();
		float volume = Math.min(0.25F + 0.04F * n, 1.0F) * WaterSettings.flowSoundVolume;
		level.playLocalSound(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, SoundEvents.GENERIC_BURN, SoundSource.BLOCKS, volume,
			1.8F + random.nextFloat() * 0.4F, false);
		for (int k = 0; k < Math.min(2 + n / 4, 12); k++) {
			level.addParticle(k % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE, at.getX() + random.nextDouble(), at.getY() + 0.6 + random.nextDouble() * 0.5,
				at.getZ() + random.nextDouble(), 0.0, 0.04, 0.0);
		}
	}

	private List<BlockPos> sourcesFor(WaterRegion r) {
		List<BlockPos> out = new ArrayList<>();
		if (!r.material.isFluidSim() || r.ocean != null) {
			return out;
		}
		for (Map.Entry<BlockPos, Material> e : (r.background != null ? r.background.sources : this.sources).entrySet()) {
			if (e.getValue() != r.material) {
				continue;
			}
			BlockPos p = e.getKey();
			if (r.claims(p, 2)) {
				out.add(p);
			}
		}
		return out;
	}

	private void renderFrameUnsafe(Minecraft mc, ClientLevel level, @Nullable FrameTarget target) {
		long now = System.nanoTime();
		Float clipDt = ClipRecorder.get().fixedDt();
		float frameDt = clipDt != null ? clipDt
			: this.lastFrameNanos == 0L ? 1.0F / 60.0F : (float) Math.min((now - this.lastFrameNanos) / 1.0e9, 0.1);
		this.lastFrameNanos = now;
		this.smoothedFrameMs = this.smoothedFrameMs * 0.9F + frameDt * 1000.0F * 0.1F;
		LevelRenderState levelState = mc.gameRenderer.gameRenderState().levelRenderState;
		Vec3 cam = levelState.cameraRenderState.pos;
		double reach = (mc.options.renderDistance().get() + 2) * 16.0;

		boolean ocean = OceanMode.active();
		if (ocean) {
			this.oceanSim.update(level, frameDt, cam);
		}
		List<WaterRegion> active = new ArrayList<>();
		List<WaterRegion> merged = new ArrayList<>();
		List<WaterRegion> drained = new ArrayList<>();
		float fastest = 0.0F;
		float fastestSand = 0.0F;
		float fastestLeaf = 0.0F;
		float fastestSteam = 0.0F;
		List<WaterRegion> all = new ArrayList<>(this.regions);
		all.addAll(this.background);
		for (WaterRegion r : all) {
			WaterRegion.Fill fill = r.readBack();
			this.sendPortalTransit(r);
			if (r.background == null) {
				this.playIgnitions(level, r);
			}
			r.readFlow(this.bodyFlows);
			if (r.background == null) {
				this.collectHeat(level, r);
			} else {
				r.boiled.clear();
				r.wisps.clear();
				r.simmering.clear();
			}
			if (fill != null) {
				this.pendingFills.add(new Object[]{r, fill});
			}
			int[] box = r.ocean != null ? r.ocean.cellBox(r.res) : r.computeBoxWorld(this.sourcesFor(r), this.frameCounter);
			r.boxWorld = box;
			if (box == null) {
				if (++r.idleFrames > 90) {
					drained.add(r);
				}
				continue;
			}
			r.idleFrames = 0;
			double cx = (box[0] + box[3]) * 0.5 / r.res;
			double cz = (box[2] + box[5]) * 0.5 / r.res;
			if (r.background == null && (Math.abs(cx - cam.x) > reach || Math.abs(cz - cam.z) > reach)) {
				continue;
			}
			active.add(r);
			if (r.material.isGranular()) {
				fastestSand = Math.max(fastestSand, r.lastMaxSpeed / r.res);
			} else if (r.material.isLeaves()) {
				fastestLeaf = Math.max(fastestLeaf, r.lastMaxSpeed / r.res);
			} else if (r.material.isGas()) {
				fastestSteam = Math.max(fastestSteam, r.lastMaxSpeed / r.res);
			} else {
				fastest = Math.max(fastest, r.lastMaxSpeed / r.res);
			}
		}

		this.flushSteam(level);
		for (int i = 0; i < active.size(); i++) {
			WaterRegion a = active.get(i);
			for (int j = active.size() - 1; j > i; j--) {
				WaterRegion b = active.get(j);
				if (a.res == b.res && a.material == b.material && a.background == b.background && a.ocean == null && b.ocean == null
					&& overlaps(a.boxWorld, b.boxWorld)) {
					a.boxWorld = WaterRegion.unionBox(a.boxWorld, b.boxWorld);
					a.queueAbsorb(b);
					active.remove(j);
					merged.add(b);
				}
			}
		}
		while (active.size() > MAX_REGIONS) {
			active.removeLast();
		}

		int steps = 0;
		float stepDt = frameDt;
		if (!this.paused && !mc.isPaused()) {
			float speed = fastest + WaterSettings.gravity * 0.15F;
			int res = Math.max(1, WaterSettings.resolution);
			int wanted = Math.max((int) Math.ceil(frameDt * speed * res / WaterSettings.cfl), (int) Math.ceil(frameDt / WaterSettings.maxDt));
			steps = Mth.clamp(wanted, 1, clipDt != null ? WaterSettings.renderMaxStepsPerFrame : WaterSettings.maxStepsPerFrame);
			stepDt = Math.min(frameDt / steps, WaterSettings.maxDt);
		}
		int sandSteps = 0;
		float sandDt = frameDt;
		if (!this.paused && !mc.isPaused()) {
			int sandRes = resolutionFor(Material.SAND);
			float limit = Math.min(WaterSettings.sandCfl / (sandRes * sandWaveSpeed()), 1.0F / (sandRes * Math.max(fastestSand, 1e-3F)));
			int wanted = (int) Math.ceil(frameDt / limit);
			sandSteps = Mth.clamp(wanted, 1, clipDt != null ? Integer.MAX_VALUE : Math.max(1, WaterSettings.sandMaxStepsPerFrame));
			sandDt = Math.min(frameDt / sandSteps, limit);
		}
		int leafSteps = 0;
		float leafDt = frameDt;
		if (!this.paused && !mc.isPaused()) {
			float limit = 0.5F / (resolutionFor(Material.LEAVES) * Math.max(fastestLeaf, 2.0F));
			leafSteps = Mth.clamp((int) Math.ceil(frameDt / limit), 1, clipDt != null ? Integer.MAX_VALUE : Math.max(1, WaterSettings.leafMaxStepsPerFrame));
			leafDt = Math.min(frameDt / leafSteps, limit);
		}
		int steamSteps = 0;
		float steamDt = frameDt;
		if (!this.paused && !mc.isPaused()) {
			float limit = 0.5F / (resolutionFor(Material.STEAM) * Math.max(fastestSteam, 2.0F));
			steamSteps = Mth.clamp((int) Math.ceil(frameDt / limit), 1, clipDt != null ? Integer.MAX_VALUE : Math.max(1, WaterSettings.leafMaxStepsPerFrame));
			steamDt = Math.min(frameDt / steamSteps, limit);
		}
		this.lastSteps = steps;
		this.lastDt = stepDt;
		this.lastSandSteps = sandSteps;
		this.lastSandDt = sandDt;
		this.simTime += steps * stepDt;

		boolean ownSea = ocean && OceanMode.drawsSea();
		this.cameraUnderSea = ownSea && this.cameraUnderSea(level, cam);
		this.seaDrawsFog = ownSea && (this.cameraUnderSea || this.oceanSim.cameraInZone(cam)
			|| OceanSurfaces.hasTop(this.oceanSurfaces.restTop(Mth.floor(cam.x), Mth.floor(cam.z)))
				&& this.oceanSurfaces.restTop(Mth.floor(cam.x), Mth.floor(cam.z)) <= Mth.floor(cam.y));
		boolean seaLook = WaterSettings.waterSeaLook && active.stream().anyMatch(r -> r.material == Material.WATER && r.ocean == null);
		boolean surfaces = ownSea && this.oceanSurfaces.upload();
		boolean drawOcean = target != null && (ocean && (ownSea ? surfaces || this.cameraUnderSea || !this.oceanSim.zones.isEmpty()
			: surfaces || !this.oceanSim.zones.isEmpty()) || seaLook);
		boolean grassNow = target != null && RealisticGrass.get().wanted();
		boolean treesNow = target != null && me.skaffy.client.trees.RealisticTrees.get().wanted();
		boolean jellyNow = target != null && me.skaffy.client.jelly.JellyWorld.wanted();
		boolean iceNow = target != null && me.skaffy.client.ice.IceWorld.wanted();
		if (active.isEmpty() && !drawOcean && !grassNow && !treesNow && !jellyNow && !iceNow || target == null) {
			this.retire(level, merged, drained);
			Vk.endFrame();
			this.frameCounter++;
			return;
		}
		if (this.renderer == null) {
			this.renderer = new WaterRenderer();
		}
		if (this.sandRenderer == null) {
			this.sandRenderer = new SandRenderer();
		}
		if (this.crystalRenderer == null) {
			this.crystalRenderer = new IceCrystalRenderer();
		}
		if (this.leafRenderer == null) {
			this.leafRenderer = new LeafRenderer();
		}
		if (this.steamRenderer == null) {
			this.steamRenderer = new SteamRenderer();
		}
		if (this.uboRing == null) {
			this.uboRing = VkBuf.upload((long) UBO_SLOT * SLOTS_PER_FRAME * Vk.FRAME_RING, VkBuf.UNIFORM);
			this.frameView = VkBuf.device(16, 0);
		}
		int width = target.width();
		int height = target.height();
		int colorFormat = target.colorFormat();
		this.layerOutput = target.layer();
		this.packShadow = WaterSettings.packShadows ? ShaderCompat.packShadowMap() : null;
		packShadowView = this.packShadow != null ? this.packShadow.view() : 0L;
		long lightmap = mc.gameRenderer.lightmap() instanceof VulkanGpuTextureView lightmapView ? lightmapView.vkImageView() : target.depthView();
		this.renderer.prepare(width, height, colorFormat);
		long depthImage = target.depthImage();
		long depthView = target.depthView();
		if (target.layer()) {
			if (this.layerDepth == null || this.layerDepth.width != width || this.layerDepth.height != height) {
				if (this.layerDepth != null) {
					Vk.destroyLater(this.layerDepth);
				}
				this.layerDepth = new VkTex(VkTex.D32, width, height, VkTex.COPY_DST | VkTex.COPY_SRC | VkTex.DEPTH | VkTex.SAMPLED);
			}
			depthImage = this.layerDepth.image;
			depthView = this.layerDepth.view;
		}

		VkCommandBuffer cb = Vk.encoder().allocateAndBeginTransientCommandBuffer();
		Vk.beginRecording(cb);
		Vk.barrier(cb);
		if (target.layer()) {
			VkTex.copy(cb, target.depthImage(), depthImage, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, width, height);
			Vk.barrier(cb);
		}
		this.frameView.fill(cb, 0);
		if (this.cameraUnderSea) {
			this.frameView.fill(cb, 0, 4, Float.floatToRawIntBits(1.0F));
		}
		Vk.barrier(cb);
		boolean readFill = !this.paused;
		List<WaterRenderer.Body> waterBodies = new ArrayList<>();
		List<WaterRenderer.Body> sandBodies = new ArrayList<>();
		List<WaterRenderer.Body> leafBodies = new ArrayList<>();
		List<WaterRenderer.Body> steamBodies = new ArrayList<>();
		boolean windy = level.isRaining() && WaterSettings.leafWindStrength > 0.0F && WaterSettings.leafWindLift > 0.0F;
		List<Runnable> recordings = new ArrayList<>(active.size());
		boolean castShadows = ShadowCasters.wanted();
		boolean packDraw = PackMaterials.active();
		RainSystem.Kicks kicks = this.rain != null && WaterSettings.realisticRain && WaterSettings.rainKick > 0.0F ? this.rain.kicks() : null;
		List<WaterRenderer.Body> packBodies = new ArrayList<>();
		for (int i = 0; i < active.size(); i++) {
			WaterRegion r = active.get(i);
			BackgroundWorld bg = r.background;
			ClientLevel lvl = bg != null ? bg.level : level;
			boolean hasGrid = r.planWindow(lvl, this.solidsFor(r), r.boxWorld);
			String memoryWarning = r.takeMemoryWarning();
			if (memoryWarning != null && mc.player != null) {
				mc.player.sendSystemMessage(Component.literal(memoryWarning));
			}
			if (!hasGrid) {
				continue;
			}
			r.updateLight(lvl);
			boolean sand = r.material.isGranular();
			List<float[]> regionBlasts = bg != null ? List.of() : this.blastsFor(r);
			if (bg == null && (entityNear(level, r) || !regionBlasts.isEmpty() || (windy && r.material.isLeaves()) || this.paddleNear(level, r)
				|| (this.freezeForceFrames > 0 && r.material == Material.WATER))) {
				r.wake(this.frameCounter);
			}
			boolean leaves = r.material.isLeaves();
			boolean gas = r.material.isGas();
			r.rainKicks = kicks != null && bg == null && r.material == Material.WATER && rainedOn(level, r) ? kicks : null;
			if (r.rainKicks != null) {
				r.wake(this.frameCounter);
			}
			int regionSteps = r.dormant(!this.sourcesFor(r).isEmpty()) ? 0 : sand ? sandSteps : leaves ? leafSteps : gas ? steamSteps : steps;
			r.trackCalm(frameDt, regionSteps);
			int interval = nearMixPartner(r, active) ? MIX_INTERVAL : SYNC_INTERVAL;
			if (readFill && r.material.syncs() && r.ocean == null && r.initialized() && (this.frameCounter + r.id) % interval == 0) {
				r.fillRequested = true;
			}
			if (leaves && bg == null && isHost() && r.initialized() && (this.frameCounter + r.id) % SYNC_INTERVAL == 0) {
				r.tallyRequested = true;
			}
			r.foamRequested = r.ocean != null && bg == null && PackFoam.active() && WaterSettings.oceanWhitewater;
			boolean packLeaves = packDraw && leaves && bg == null;
			r.leafCopyForced = packLeaves;
			if (packLeaves && r.initialized()) {
				r.shadowRequested = true;
			} else if (castShadows && bg == null && ShadowCasters.casts(r.material) && !(packDraw && PackMaterials.drawnByPack(r.material))
				&& r.initialized() && (r.shadow == null || regionSteps > 0)
				&& (this.frameCounter + r.id) % SHADOW_INTERVAL == 0) {
				r.shadowRequested = true;
			} else if (!castShadows && !packLeaves) {
				r.shadow = null;
			}
			if (!packDraw) {
				r.packMesh = null;
			}
			int slot = Vk.ringSlot() * SLOTS_PER_FRAME + i;
			List<float[]> removals = r.takeRemovals();
			r.portalSinks = gas ? List.of() : this.portalSinks(lvl, r, bg != null ? bg.portals : this.portalBlocks);
			int numSources = this.writeGlobals(mc, lvl, r, slot, sand ? sandDt : leaves ? leafDt : gas ? steamDt : stepDt, width, height, removals);
			Desc ubo = Desc.ubo(this.uboRing, (long) slot * UBO_SLOT, UBO_SIZE);
			FlowProbe probe = r.material == Material.WATER && bg == null && isHost() ? flowProbe(level, r) : null;
			int frame = this.frameCounter;
			VkBuf view = this.frameView;
			int removalCount = removals.size();
			recordings.add(() -> {
				r.record(cb, ubo, regionSteps, numSources, frame, view, removalCount, regionBlasts);
				if (probe != null) {
					r.recordFlowProbe(cb, ubo, probe.points(), probe.owners(), probe.count());
				}
			});
			WaterRenderer.Body body = new WaterRenderer.Body(r, ubo);
			if (bg != null) {
			} else if (packDraw && PackMaterials.drawnByPack(r.material)) {
				packBodies.add(body);
			} else {
				(sand ? sandBodies : leaves ? leafBodies : gas ? steamBodies : waterBodies).add(body);
			}
		}
		Vk.interleave(cb, recordings);
		Vk.barrier(cb);
		for (WaterRenderer.Body body : packBodies) {
			if (!body.region().material.isLeaves()) {
				body.region().recordPackMesh(cb, body.ubo());
			}
		}
		Vk.barrier(cb);
		this.lateSand.clear();
		this.lateLeaves.clear();
		this.lateSteam.clear();
		this.lateGrassUbo = null;
		this.lateTreeUbo = null;
		Desc grassUbo = null;
		Desc treeUbo = null;
		if (grassNow || treesNow) {
			int grassSlot = Vk.ringSlot() * SLOTS_PER_FRAME + GRASS_SLOT;
			this.writeOceanGlobals(mc, level, grassSlot, width, height);
			this.writePackShadow(grassSlot);
			Desc cameraUbo = Desc.ubo(this.uboRing, (long) grassSlot * UBO_SLOT, UBO_SIZE);
			if (grassNow) {
				grassUbo = cameraUbo;
				if (this.grassRenderer == null) {
					this.grassRenderer = new GrassRenderer();
					this.grassFlow = new GrassFlow();
				}
			}
			if (treesNow) {
				treeUbo = cameraUbo;
				if (this.treeRenderer == null) {
					this.treeRenderer = new me.skaffy.client.trees.TreeRenderer();
				}
			}
		}
		this.lateJellyUbo = null;
		this.lateIceUbo = null;
		Desc jellyUbo = null;
		if (jellyNow || iceNow) {
			int jellySlot = Vk.ringSlot() * SLOTS_PER_FRAME + JELLY_SLOT;
			this.writeOceanGlobals(mc, level, jellySlot, width, height);
			Desc cameraSlot = Desc.ubo(this.uboRing, (long) jellySlot * UBO_SLOT, UBO_SIZE);
			jellyUbo = jellyNow ? cameraSlot : null;
			if (iceNow) {
				this.lateIceUbo = cameraSlot;
				this.lateLightmap = lightmap;
			}
		}
		if (target.layer()) {
			this.lateJellyUbo = jellyUbo;
			this.lateTreeUbo = treeUbo;
			this.lateGrassUbo = grassUbo;
			this.lateGrassDt = frameDt;
			this.lateGrassWater.clear();
			this.lateGrassWater.addAll(waterBodies);
			this.lateSand.addAll(sandBodies);
			this.lateLeaves.addAll(leafBodies);
			this.lateSteam.addAll(steamBodies);
			this.lateLightmap = lightmap;
		} else {
			if (treeUbo != null) {
				this.treeRenderer.record(cb, treeUbo, width, height, colorFormat, target.colorView(), depthView, lightmap, packShadowView, frameDt,
					this.rain != null && WaterSettings.realisticRain ? this.rain.leafHits(level) : null);
				Vk.barrier(cb);
			}
			if (grassUbo != null) {
				GrassFlow flow = this.grassFlow;
				this.grassRenderer.record(cb, grassUbo, width, height, colorFormat, target.colorView(), depthImage, depthView, lightmap, packShadowView,
					frameDt, buf -> flow.record(cb, buf, waterBodies));
				Vk.barrier(cb);
			}
			if (!sandBodies.isEmpty()) {
				this.sandRenderer.record(cb, sandBodies, width, height, colorFormat, target.colorView(), depthView, lightmap);
				Vk.barrier(cb);
			}
			if (!leafBodies.isEmpty()) {
				this.leafRenderer.record(cb, leafBodies, width, height, colorFormat, target.colorView(), depthView, lightmap);
				Vk.barrier(cb);
			}
			if (!waterBodies.isEmpty() && IceCrystalRenderer.wanted()) {
				this.crystalRenderer.record(cb, waterBodies, width, height, colorFormat, target.colorView(), depthView, lightmap);
				Vk.barrier(cb);
			}
			if (jellyUbo != null) {
				me.skaffy.client.jelly.JellyWorld.record(cb, jellyUbo, width, height, colorFormat, target.colorImage(), target.colorView(), depthImage,
					depthView, lightmap);
				Vk.barrier(cb);
			}
		}
		OceanRenderer.Draw oceanDraw = null;
		if (drawOcean) {
			if (this.oceanRenderer == null) {
				this.oceanRenderer = new OceanRenderer(this.renderer.thicknessFormat);
			}
			int slot = Vk.ringSlot() * SLOTS_PER_FRAME + MAX_REGIONS;
			this.writeOceanGlobals(mc, level, slot, width, height);
			List<WaterRegion> drawnZones = new ArrayList<>();
			List<Desc> zoneColumns = new ArrayList<>();
			if (ownSea) {
				for (WaterRegion r : this.drawnZones()) {
					drawnZones.add(r);
					zoneColumns.add(r.writeSeaColumns(level, this.oceanSurfaces));
				}
			}
			oceanDraw = new OceanRenderer.Draw(this.oceanRenderer, Desc.ubo(this.uboRing, (long) slot * UBO_SLOT, UBO_SIZE), this.oceanSurfaces.buffer(),
				ownSea ? this.oceanSurfaces.count() : 0, ownSea && (this.cameraUnderSea || this.oceanSim.cameraInZone(cam)) || seaLook,
				drawnZones, zoneColumns, Mth.floor(cam.x), Mth.floor(cam.y), Mth.floor(cam.z), this.oceanSurfaces.colors());
		}
		if (!waterBodies.isEmpty() || oceanDraw != null) {
			List<List<WaterRenderer.Body>> layers = new ArrayList<>();
			for (Material m : new Material[]{Material.LAVA, Material.SLIME, Material.WATER}) {
				List<WaterRenderer.Body> group = waterBodies.stream().filter(bd -> bd.region().material == m).toList();
				if (!group.isEmpty() || m == Material.WATER && oceanDraw != null) {
					layers.add(group);
				}
			}
			List<WaterRenderer.Body> other = waterBodies.stream()
				.filter(bd -> bd.region().material != Material.LAVA && bd.region().material != Material.SLIME && bd.region().material != Material.WATER)
				.toList();
			if (!other.isEmpty()) {
				layers.add(0, other);
			}
			for (int li = 0; li < layers.size(); li++) {
				List<WaterRenderer.Body> group = layers.get(li);
				boolean last = li == layers.size() - 1;
				OceanRenderer.Draw sea = last ? oceanDraw : null;
				if (group.isEmpty() && sea == null) {
					continue;
				}
				this.renderer.record(cb, group, sea, Desc.ssbo(this.frameView), target.colorImage(), target.colorView(), depthImage,
					depthView, lightmap, target.layer(), sea != null ? null : fluidArea(mc, group, width, height));
				if (!last) {
					Vk.barrier(cb);
				}
			}
		}
		Vk.barrier(cb);
		if (!target.layer() && !steamBodies.isEmpty()) {
			List<WaterRenderer.Body> drawNow = new ArrayList<>();
			for (WaterRenderer.Body body : steamBodies) {
				if (body.region().material.isFireGas()) {
					// Flames and smoke wait for the late pass so translucent mobs and blocks cannot draw over them.
					this.lateSteam.add(body);
				} else {
					drawNow.add(body);
				}
			}
			if (!this.lateSteam.isEmpty()) {
				this.lateLightmap = lightmap;
			}
			if (!drawNow.isEmpty()) {
				this.steamRenderer.record(cb, drawNow, width, height, colorFormat, target.colorView(), depthView, lightmap);
				Vk.barrier(cb);
			}
		}
		Vk.endRecording();
		Vk.check(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer");
		Vk.encoder().execute(cb);
		this.retire(level, merged, drained);
		Vk.endFrame();
		this.frameCounter++;
		if (this.freezeForceFrames > 0) {
			this.freezeForceFrames--;
		}
	}

	public String forcePhase(String phase) {
		if (!WaterSettings.waterTemperature || !WaterSettings.waterFreezing) {
			return "Turn on Water temperature and Freezing in the settings first";
		}
		switch (phase) {
			case "water" -> this.freezeForceValue = 4.0F;
			case "slush" -> this.freezeForceValue = -0.55F * LATENT_FREEZE;
			case "ice" -> this.freezeForceValue = -LATENT_FREEZE - 6.0F;
			default -> {
				return "Use water, slush or ice";
			}
		}
		this.freezeForceFrames = 3;
		return "Turning the realistic water around you into " + phase;
	}

	private static int @Nullable [] fluidArea(Minecraft mc, List<WaterRenderer.Body> bodies, int width, int height) {
		CameraRenderState camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		Vec3 cam = camera.pos;
		Matrix4f clip = new Matrix4f(LEVEL_PROJECTION).mul(camera.viewRotationMatrix);
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		float maxX = -Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		Vector4f corner = new Vector4f();
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			double pad = 1.0 + (double) WaterRegion.KILL_MARGIN / r.res;
			double x0 = r.ox - pad - cam.x;
			double y0 = r.oy - pad - cam.y;
			double z0 = r.oz - pad - cam.z;
			double x1 = r.ox + r.sx + pad - cam.x;
			double y1 = r.oy + r.sy + pad - cam.y;
			double z1 = r.oz + r.sz + pad - cam.z;
			if (x0 <= 1.0 && y0 <= 1.0 && z0 <= 1.0 && x1 >= -1.0 && y1 >= -1.0 && z1 >= -1.0) {
				return null;
			}
			for (int k = 0; k < 8; k++) {
				corner.set((float) ((k & 1) == 0 ? x0 : x1), (float) ((k & 2) == 0 ? y0 : y1), (float) ((k & 4) == 0 ? z0 : z1), 1.0F);
				clip.transform(corner);
				if (corner.w < 0.05F) {
					return null;
				}
				float nx = corner.x / corner.w;
				float ny = corner.y / corner.w;
				minX = Math.min(minX, nx);
				minY = Math.min(minY, ny);
				maxX = Math.max(maxX, nx);
				maxY = Math.max(maxY, ny);
			}
		}
		int px0 = Mth.clamp((int) Math.floor((minX * 0.5F + 0.5F) * width) - 4, 0, width);
		int py0 = Mth.clamp((int) Math.floor((minY * 0.5F + 0.5F) * height) - 4, 0, height);
		int px1 = Mth.clamp((int) Math.ceil((maxX * 0.5F + 0.5F) * width) + 4, 0, width);
		int py1 = Mth.clamp((int) Math.ceil((maxY * 0.5F + 0.5F) * height) + 4, 0, height);
		if (px1 - px0 < 4 || py1 - py0 < 4) {
			return new int[]{0, 0, Math.min(4, width), Math.min(4, height)};
		}
		return new int[]{px0, py0, px1 - px0, py1 - py0};
	}

	public void beginLevel() {
		this.lateSand.clear();
		this.lateLeaves.clear();
		this.lateGrassUbo = null;
		this.lateTreeUbo = null;
		this.lateJellyUbo = null;
		this.lateIceUbo = null;
	}

	public boolean drawingFailed() {
		return this.failed;
	}

	public boolean hasLate() {
		return !this.lateSand.isEmpty() || !this.lateLeaves.isEmpty() || !this.lateSteam.isEmpty() || this.lateGrassUbo != null || this.lateTreeUbo != null || this.lateJellyUbo != null
			|| this.lateIceUbo != null;
	}

	public void renderLate(FrameTarget target) {
		if (!this.hasLate() || this.failed || this.sandRenderer == null || this.leafRenderer == null || this.steamRenderer == null) {
			this.beginLevel();
			return;
		}
		try {
			VkCommandBuffer cb = Vk.encoder().allocateAndBeginTransientCommandBuffer();
			Vk.beginRecording(cb);
			Vk.barrier(cb);
			if (this.lateJellyUbo != null) {
				me.skaffy.client.jelly.JellyWorld.record(cb, this.lateJellyUbo, target.width(), target.height(), target.colorFormat(), target.colorImage(),
					target.colorView(), target.depthImage(), target.depthView(), this.lateLightmap);
				Vk.barrier(cb);
			}
			if (this.lateTreeUbo != null && this.treeRenderer != null) {
				ClientLevel lvl = Minecraft.getInstance().level;
				this.treeRenderer.record(cb, this.lateTreeUbo, target.width(), target.height(), target.colorFormat(), target.colorView(),
					target.depthView(), this.lateLightmap, packShadowView, this.lateGrassDt,
					this.rain != null && WaterSettings.realisticRain && lvl != null ? this.rain.leafHits(lvl) : null);
				Vk.barrier(cb);
			}
			if (this.lateGrassUbo != null && this.grassRenderer != null) {
				this.grassRenderer.record(cb, this.lateGrassUbo, target.width(), target.height(), target.colorFormat(), target.colorView(),
					target.depthImage(), target.depthView(), this.lateLightmap, packShadowView, this.lateGrassDt,
					buf -> this.grassFlow.record(cb, buf, this.lateGrassWater));
				Vk.barrier(cb);
			}
			if (!this.lateSand.isEmpty()) {
				this.sandRenderer.record(cb, this.lateSand, target.width(), target.height(), target.colorFormat(), target.colorView(),
					target.depthView(), this.lateLightmap);
				Vk.barrier(cb);
			}
			if (!this.lateLeaves.isEmpty()) {
				this.leafRenderer.record(cb, this.lateLeaves, target.width(), target.height(), target.colorFormat(), target.colorView(),
					target.depthView(), this.lateLightmap);
				Vk.barrier(cb);
			}
			if (!this.lateSteam.isEmpty()) {
				this.steamRenderer.record(cb, this.lateSteam, target.width(), target.height(), target.colorFormat(), target.colorView(),
					target.depthView(), this.lateLightmap);
				Vk.barrier(cb);
			}
			if (this.lateIceUbo != null) {
				me.skaffy.client.ice.IceWorld.record(cb, this.lateIceUbo, target.width(), target.height(), target.colorFormat(), target.colorImage(),
					target.colorView(), target.depthImage(), target.depthView(), this.lateLightmap);
				Vk.barrier(cb);
			}
			Vk.endRecording();
			Vk.check(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer");
			Vk.encoder().execute(cb);
		} catch (RuntimeException e) {
			Vk.endRecording();
			this.failed = true;
			RealisticWater.LOGGER.error("Realistic Water late draw failed; disabling the simulation", e);
		} finally {
			this.beginLevel();
		}
	}

	private void retire(ClientLevel level, List<WaterRegion> merged, List<WaterRegion> drained) {
		for (WaterRegion r : merged) {
			this.regions.remove(r);
			this.background.remove(r);
			r.close();
		}
		for (WaterRegion r : drained) {
			this.regions.remove(r);
			this.background.remove(r);
			if (r.background != null) {
				if (isHost()) {
					this.syncBackground(r, null);
				}
			} else if (isHost() && !this.paused) {
				LongArrayList positions = new LongArrayList();
				ByteArrayList amounts = new ByteArrayList();
				for (long key : r.owned) {
					this.queueClear(level, key, r.material, positions, amounts);
				}
				this.send(r.material, positions, amounts);
			}
			r.close();
		}
	}

	private static boolean entityNear(ClientLevel level, WaterRegion r) {
		int[] b = r.boxWorld;
		if (b == null) {
			return false;
		}
		double h = 1.0 / r.res;
		AABB box = new AABB(b[0] * h, b[1] * h, b[2] * h, b[3] * h, b[4] * h, b[5] * h).inflate(0.5);
		return !level.getEntities((Entity) null, box, e -> !e.isSpectator()).isEmpty() || !RealisticBlockEntity.near(level, box).isEmpty()
			|| !movingPistons(level, box.inflate(1.0)).isEmpty();
	}

	private static boolean rainedOn(ClientLevel level, WaterRegion r) {
		int[] b = r.boxWorld;
		if (b == null || !level.isRaining()) {
			return false;
		}
		int x = Math.floorDiv(b[0] + b[3], 2 * r.res);
		int z = Math.floorDiv(b[2] + b[5], 2 * r.res);
		return level.isRainingAt(new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z));
	}

	private static boolean overlaps(int[] a, int[] b) {
		return a[0] < b[3] && b[0] < a[3] && a[1] < b[4] && b[1] < a[4] && a[2] < b[5] && b[2] < a[5];
	}

	private int writeGlobals(Minecraft mc, ClientLevel level, WaterRegion r, int slot, float dt, int width, int height, List<float[]> removals) {
		ByteBuffer b = this.uboRing.mapped();
		int o = slot * UBO_SLOT;
		LevelRenderState levelState = mc.gameRenderer.gameRenderState().levelRenderState;
		CameraRenderState camera = levelState.cameraRenderState;
		SkyRenderState sky = levelState.skyRenderState;
		int res = r.res;
		float h = 1.0F / res;
		int[] box = r.box;
		boolean sand = r.material.isGranular();
		boolean lava = r.material == Material.LAVA;
		boolean slime = r.material == Material.SLIME;
		boolean leaves = r.material.isLeaves();

		putInts(b, o, r.nx, r.ny, r.nz, r.numCells);
		putInts(b, o + 16, r.numU, r.numV, r.numW, r.numFaces);
		int entityCount = this.writeEntities(mc, r, b, o + 576, levelState.worldPartialTicks);
		putInts(b, o + 32, r.fluidCapacity, r.wwCapacity, entityCount, this.frameCounter);
		int sourceCount = sand ? 0 : this.writeSources(r, b, o + 2112);
		boolean whitewater = r.ocean != null ? WaterSettings.oceanWhitewater : WaterSettings.whitewater;
		putInts(b, o + 48, r.material.usesWhitewater() && whitewater ? 1 : 0, sourceCount, !sand && this.localPlayerFirst ? 1 : 0,
			WaterSettings.wwCellLimit);
		putFloats(b, o + 64, 0.0F, -WaterSettings.gravity * res, 0.0F, dt);
		float flipRatio = r.ocean != null ? WaterSettings.oceanFlipRatio : lava ? WaterSettings.lavaFlipRatio : slime ? WaterSettings.slimeFlipRatio
			: WaterSettings.flipRatio;
		float flip = (float) Math.pow(flipRatio, dt * 60.0F);
		putFloats(b, o + 80, flip, PARTICLES_PER_CELL, slime ? WaterSettings.slimeVolumeCorrection : WaterSettings.volumeCorrection, 0.0F);
		float maxSpeed = (lava ? WaterSettings.lavaMaxSpeed : slime ? WaterSettings.slimeMaxSpeed : leaves ? WaterSettings.leafMaxSpeed : WaterSettings.maxSpeed) * res;
		putFloats(b, o + 96, maxSpeed, h, this.simTime, 0.0F);
		putFloats(b, o + 112, WaterSettings.wwTrappedAirRate, WaterSettings.wwWaveCrestRate, WaterSettings.wwEnergyMin, WaterSettings.wwEnergyMax);
		putFloats(b, o + 128, WaterSettings.wwTrappedAirMin, WaterSettings.wwTrappedAirMax, WaterSettings.wwWaveCrestMin, WaterSettings.wwWaveCrestMax);
		putFloats(b, o + 144, WaterSettings.wwFoamLifeMin, WaterSettings.wwFoamLifeMax, WaterSettings.wwBubbleBuoyancy, WaterSettings.wwBubbleDrag);
		putFloats(b, o + 160, WaterSettings.wwSprayDrag, WaterSettings.wwSprayThreshold, WaterSettings.wwBubbleThreshold, WaterSettings.wwMaxPerParticle);

		boolean sea = r.ocean != null;
		float glint = this.writeLook(b, o, camera, sky, width, height, lava, slime, sand, leaves, h, sea);
		Vec3 cam = camera.pos;
		putFloats(b, o + 432, (float) (r.ox - cam.x), (float) (r.oy - cam.y), (float) (r.oz - cam.z), h);
		putFloats(b, o + 3136, (float) (cam.x - r.ox) * res, (float) (cam.y - r.oy) * res, (float) (cam.z - r.oz) * res, res);
		float underwaterFog = sea ? WaterSettings.oceanUnderwaterFog : lava ? WaterSettings.lavaUnderwaterFog : WaterSettings.underwaterFog;
		putFloats(b, o + 3152, underwaterFog, wrap(cam.x), wrap(cam.y), wrap(cam.z));

		SurfaceMesher mesher = r.mesher;
		int sub = mesher.subdivision;
		putInts(b, o + 3168, mesher.fx, mesher.fy, mesher.fz, sub);
		putFloats(b, o + 3184, sand ? WaterSettings.sandSurfaceIso : WaterSettings.surfaceIso, 8.0F / (sub * sub * sub), WaterSettings.nearCull,
			WaterSettings.dropletThreshold);
		putInts(b, o + 3200, box[0], box[1], box[2], WaterRegion.boxCells(box));
		putInts(b, o + 3216, box[3], box[4], box[5], WaterRegion.boxFaces(box));
		int[] fine = mesher.fineBox(box);
		putInts(b, o + 3232, fine[0], fine[1], fine[2], SurfaceMesher.voxels(fine));
		putInts(b, o + 3248, fine[3], fine[4], fine[5], SurfaceMesher.cubes(fine));
		putInts(b, o + 3264, removals.size(), 0, 0, 0);
		for (int k = 0; k < removals.size(); k++) {
			float[] rm = removals.get(k);
			putFloats(b, o + 3280 + k * 32, rm[0], rm[1], rm[2], 0.0F);
			putFloats(b, o + 3296 + k * 32, rm[3], rm[4], rm[5], 0.0F);
		}
		boolean lavaFog = camera.fogType == FogType.LAVA;
		putFloats(b, o + 3792, (float) (level.getMinY() - r.oy) * res - 8.0F, WaterRegion.KILL_MARGIN,
			lavaFog ? camera.fogData.environmentalStart : 0.0F, lavaFog ? Math.max(camera.fogData.environmentalEnd, 0.01F) : 0.0F);
		float detailStrength = sea ? WaterSettings.oceanDetailStrength : lava ? WaterSettings.lavaDetailStrength : slime ? 0.0F : WaterSettings.detailStrength;
		float roughness = sea ? WaterSettings.oceanRoughness : lava ? WaterSettings.lavaRoughness : WaterSettings.roughness;
		float specular = sea ? WaterSettings.oceanSpecular * glint : lava ? WaterSettings.lavaSpecular : slime ? 0.0F : WaterSettings.specular * glint;
		putFloats(b, o + 3808, detailStrength, WaterSettings.detailScale, WaterSettings.detailSpeed, roughness);
		putFloats(b, o + 3824, specular, WaterSettings.wetDarkening, WaterSettings.filmShading, WaterSettings.foamBubbleSize);

		if (sand) {
			double phi = Math.toRadians(WaterSettings.sandFrictionAngle);
			float alpha = (float) (Math.sqrt(2.0 / 3.0) * 2.0 * Math.sin(phi) / (3.0 - Math.sin(phi)));
			float e = PARTICLES_PER_CELL * WaterSettings.sandStiffness * res * res;
			float mu = e / (2.0F * (1.0F + SAND_POISSON));
			float lambda = e * SAND_POISSON / ((1.0F + SAND_POISSON) * (1.0F - 2.0F * SAND_POISSON));
			putFloats(b, o + 3840, r.material.shaderId(), alpha, WaterSettings.sandCohesion, 1.0F);
			putFloats(b, o + 3856, mu, lambda, Math.max(1, WaterSettings.sandGrainsPerParticle), WaterSettings.sandGrainSize);
			float impactViscosity = WaterSettings.sandImpactDamping * 0.25F / Math.max(dt, 1e-5F);
			putFloats(b, o + 3872, WaterSettings.sandWallFriction, 1.0F / PARTICLES_PER_CELL, impactViscosity, WaterSettings.sandImpactRate);
		} else {
			float filmFriction = lava ? WaterSettings.lavaFilmFriction : slime ? WaterSettings.slimeFilmFriction : WaterSettings.filmFriction;
			float strandedTime = lava ? WaterSettings.lavaStrandedTime : slime ? WaterSettings.slimeStrandedTime : WaterSettings.strandedTime;
			int strandedNeighbors = lava ? WaterSettings.lavaStrandedNeighbors : slime ? WaterSettings.slimeStrandedNeighbors : WaterSettings.strandedNeighbors;
			float filmDepth = (lava ? WaterSettings.lavaFilmDepth : slime ? WaterSettings.slimeFilmDepth : WaterSettings.filmDepth) * res;
			float adhesion = slime ? WaterSettings.slimeAdhesion : 0.0F;
			float entityStick = slime ? Mth.clamp(WaterSettings.slimeEntityStick, 0.0F, 1.0F) : 0.0F;
			putFloats(b, o + 3840, r.material.shaderId(), adhesion, entityStick, 1.0F);
			putFloats(b, o + 3856, filmFriction, strandedTime, strandedNeighbors, filmDepth);
			if (slime) {
				putFloats(b, o + 3872, WaterSettings.slimeRestSpeed * res, WaterSettings.slimeRestTime, WaterSettings.slimeWakeSpeed * res, 0.0F);
			} else {
				putFloats(b, o + 3872, 0.0F, 0.0F, 0.0F, 0.0F);
			}
		}
		if (slime) {
			boolean inside = RealisticSlimeFluid.isRealistic(level.getFluidState(BlockPos.containing(cam)));
			putFloats(b, o + 3888, inside ? 1.0F : 0.0F, WaterSettings.slimeBrightness, WaterSettings.slimeInsideOpacity, WaterSettings.slimeOpacity);
		} else {
			putFloats(b, o + 3888, WaterSettings.sandGrainMinPixels, WaterSettings.sandBrightness, WaterSettings.sandGrainSpread, 0.0F);
		}
		float impactLogJ = (float) Math.log(Math.max(1.0F - WaterSettings.sandImpactCompression, 0.01F));
		putFloats(b, o + 3904, WaterSettings.sandRestSpeed * res, WaterSettings.sandRestTime, WaterSettings.sandWakeAccel * WaterSettings.gravity * res * dt,
			impactLogJ);
		if (slime && !WaterSettings.slimeOldTexture) {
			b.putFloat(o + 3884, 1.0F);
			putFloats(b, o + 528, Math.max(WaterSettings.slimeGelAbsorbR, 0.0F), Math.max(WaterSettings.slimeGelAbsorbG, 0.0F),
				Math.max(WaterSettings.slimeGelAbsorbB, 0.0F), 0.0F);
			putFloats(b, o + 544, Math.max(WaterSettings.slimeGelScatterR, 0.0F), Math.max(WaterSettings.slimeGelScatterG, 0.0F),
				Math.max(WaterSettings.slimeGelScatterB, 0.0F), Math.max(WaterSettings.slimeGelDensity, 0.0F));
			b.putFloat(o + 520, Math.max(WaterSettings.slimeGelRefraction, 0.0F));
			putFloats(b, o + 3904, Math.max(WaterSettings.slimeGelHaze, 0.0F), Math.max(WaterSettings.slimeGelGlow, 0.0F),
				Math.max(WaterSettings.slimeGelBubbles, 0.0F), Math.max(WaterSettings.slimeGelGloss, 0.0F));
		}
		if (leaves) {
			this.writeLeafGlobals(level, b, o, res);
		}
		if (r.material == Material.SPARK) {
			putFloats(b, o + 3840, Material.SPARK.shaderId(), Math.max(WaterSettings.fireSparkLife, 0.1F), 0.9F, 0.25F);
			putFloats(b, o + 3856, 1.6F, 0.5F, 0.0F, 0.0F);
			putFloats(b, o + 3872, Math.max(WaterSettings.fireSparkSize, 1e-3F), 1.0F, WaterSettings.fireSparkBrightness, 0.0F);
		} else if (r.material == Material.SMOKE) {
			putFloats(b, o + 3840, Material.SMOKE.shaderId(), Math.max(WaterSettings.fireSmokeLife, 0.1F), WaterSettings.fireSmokeRise,
				WaterSettings.steamDrag * 0.6F);
			putFloats(b, o + 3856, WaterSettings.steamSwirl * 1.3F, 0.25F, WaterSettings.steamEntityPush, 0.0F);
			putFloats(b, o + 3872, Math.max(WaterSettings.fireSmokeSize, 1e-3F), Mth.clamp(WaterSettings.fireSmokeOpacity, 0.0F, 1.0F),
				WaterSettings.fireSmokeDark, WaterSettings.fireSmokeLight);
		} else if (r.material == Material.FLAME || r.material == Material.SOUL_FLAME) {
			putFloats(b, o + 3840, r.material.shaderId(), Math.max(WaterSettings.fireFlameLife, 0.05F), WaterSettings.fireFlameRise, 1.6F);
			putFloats(b, o + 3856, WaterSettings.steamSwirl * 0.9F, 0.15F, 0.0F, 0.0F);
			putFloats(b, o + 3872, Math.max(WaterSettings.fireFlameSize, 1e-3F), Mth.clamp(WaterSettings.fireFlameOpacity, 0.0F, 1.0F),
				WaterSettings.fireFlameBrightness, 0.0F);
		} else if (r.material.isGas()) {
			putFloats(b, o + 3840, Material.STEAM.shaderId(), Math.max(WaterSettings.steamLifetime, 0.1F), WaterSettings.steamBuoyancy,
				WaterSettings.steamDrag);
			putFloats(b, o + 3856, WaterSettings.steamSwirl, WaterSettings.steamBurst, WaterSettings.steamEntityPush, 0.0F);
			putFloats(b, o + 3872, Math.max(WaterSettings.steamParticleSize, 1e-3F), Mth.clamp(WaterSettings.steamOpacity, 0.0F, 1.0F),
				WaterSettings.steamDarkShade, WaterSettings.steamLightShade);
		}
		float shadowStrength = WaterSettings.packShadows && ShaderCompat.packRunning() ? Mth.clamp(WaterSettings.packShadowStrength, 0.0F, 1.0F) : 0.0F;
		float opacity = lava || slime || sand || leaves ? 0.0F : ShaderCompat.packRunning() ? Mth.clamp(WaterSettings.packWaterOpacity, 0.0F, 1.0F) : 0.0F;
		putFloats(b, o + 3920, this.layerOutput ? 1.0F : 0.0F, WaterSettings.underwaterMirror ? 1.0F : 0.0F, shadowStrength, opacity);
		ShaderCompat.PackShadow map = shadowStrength > 0.0F ? this.packShadow : null;
		if (map != null) {
			map.modelView().get(o + 3936, b);
			map.projection().get(o + 4000, b);
			float warp = 1.0F - 25.6F * map.projection().m00();
			float lift = 0.05F + 1.5F / Math.max(1, WaterSettings.packShadowDetail);
			putFloats(b, o + 4064, 1.0F, warp, lift, 1.0F / Math.max(1, map.resolution()));
		} else {
			putFloats(b, o + 4064, 0.0F, 0.0F, 0.0F, 0.0F);
		}
		float timeScale = Math.max(WaterSettings.waterHeatTimeScale, 0.0F);
		putFloats(b, o + 4080, WaterSettings.waterTemperature ? 1.0F : 0.0F, biomeTemperature(level, r),
			Math.max(WaterSettings.waterMixing, 0.0F) * res * res * timeScale, timeScale * res / WATER_HEAT_CAPACITY);
		putFloats(b, o + 4096, Math.max(WaterSettings.airHeatTransfer, 0.0F), Math.max(WaterSettings.iceHeatTransfer, 0.0F),
			WaterSettings.boilChurn * res, WaterSettings.steamWispTemperature);
		putFloats(b, o + 4112, WaterSettings.magmaWaterTemperature, Math.max(WaterSettings.magmaHeatReach, 0.0F),
			WaterSettings.fireWaterTemperature, WaterSettings.furnaceWaterTemperature);
		putFloats(b, o + 4368, 1.0F / Math.max(WaterSettings.magmaHeatTime, 0.01F), 1.0F / Math.max(WaterSettings.lavaBoilTime, 0.001F),
			1.0F / Math.max(WaterSettings.fireHeatTime, 0.01F), 1.0F / Math.max(WaterSettings.furnaceHeatTime, 0.01F));
		putFloats(b, o + 4384, 0.0F, 0.0F, 0.0F, 0.0F);
		putFloats(b, o + 4400, this.freezeForceFrames > 0 ? 1.0F : 0.0F, this.freezeForceValue, WaterSettings.waterFreezing ? LATENT_FREEZE : 0.0F,
			Math.max(WaterSettings.slushDrag, 0.0F));
		putFloats(b, o + 4416, Mth.clamp(WaterSettings.iceCloudiness, 0.0F, 1.0F), Mth.clamp(WaterSettings.iceBubbles, 0.0F, 1.0F),
			Mth.clamp(WaterSettings.iceCracks, 0.0F, 1.0F), Math.max(WaterSettings.slushGrainSize, 0.05F));
		putFloats(b, o + 4432, Mth.clamp(WaterSettings.slushSurface, 0.0F, 1.0F), Math.max(WaterSettings.slushCrystalSize, 0.0F),
			Mth.clamp(WaterSettings.slushCrystalOpacity, 0.0F, 1.0F), 0.0F);
		putFloats(b, o + 4448, WaterSettings.hotPlateWaterTemperature, 1.0F / Math.max(WaterSettings.hotPlateHeatTime, 0.01F),
			Math.max(WaterSettings.hotPlateHeatReach, 0.0F), 0.0F);
		putFloats(b, o + 4128, Math.max(WaterSettings.boilBubbleSize, 0.0F), WaterSettings.boilBubbleRise, Math.max(WaterSettings.boilBubbleRate, 0.0F),
			WaterSettings.boilClingTemperature);
		writeSea(b, o, level, levelState.worldPartialTicks, r.ox, r.oz, r);
		this.uboRing.flush(o, UBO_SIZE);
		return sourceCount;
	}

	private float writeLook(ByteBuffer b, int o, CameraRenderState camera, SkyRenderState sky, int width, int height, boolean lava, boolean slime,
		boolean sand, boolean leaves, float h, boolean sea) {
		Matrix4f proj = new Matrix4f(camera.projectionMatrix);
		Matrix4f bob = new Matrix4f(proj).invert().mul(LEVEL_PROJECTION);
		Matrix4f view = bob.mul(camera.viewRotationMatrix);
		proj.get(o + 176, b);
		view.get(o + 240, b);
		new Matrix4f(proj).invert().get(o + 304, b);
		new Matrix4f(view).invert().get(o + 368, b);

		putFloats(b, o + 448, width, height, 1.0F / width, 1.0F / height);

		Vector3f sunDir = new Matrix3f().rotateY((float) Math.toRadians(-90.0)).rotateX(sky.sunAngle).transform(new Vector3f(0.0F, 1.0F, 0.0F)).normalize();
		Vector4f sunWorld = new Vector4f(sunDir, 0.0F);
		Vector4f sunView = view.transform(new Vector4f(sunWorld));
		float sunIntensity = Mth.clamp(sunWorld.y * 4.0F, 0.0F, 1.0F) * Mth.clamp(sky.rainBrightness, 0.0F, 1.0F);
		putFloats(b, o + 464, sunView.x, sunView.y, sunView.z, sunIntensity);

		Vector3f skyColor = sky.skyColor != null ? new Vector3f(sky.skyColor) : new Vector3f(0.5F, 0.7F, 1.0F);
		Vector4f fog = camera.fogData.color;
		float ambient = Mth.clamp(0.15F + 1.2F * (0.3F * skyColor.x + 0.59F * skyColor.y + 0.11F * skyColor.z), 0.12F, 1.0F);
		putFloats(b, o + 480, skyColor.x, skyColor.y, skyColor.z, ambient);
		putFloats(b, o + 496, fog.x, fog.y, fog.z, 1.0F);
		float refraction = sea ? WaterSettings.oceanRefraction : lava ? WaterSettings.lavaRefraction : slime ? 0.0F : WaterSettings.refraction;
		float surfaceSmooth = sand ? WaterSettings.sandSurfaceSmoothing : lava ? WaterSettings.lavaSurfaceSmoothing
			: slime ? WaterSettings.slimeSurfaceSmoothing : WaterSettings.surfaceSmoothing;
		putFloats(b, o + 512, WaterSettings.particleRadius * h, sea ? WaterSettings.oceanThicknessScale : WaterSettings.thicknessScale, refraction,
			surfaceSmooth);
		boolean packWater = !lava && !slime && !sand && !leaves && ShaderCompat.packRunning();
		float tint = packWater ? 1.0F + Math.max(WaterSettings.packWaterTint, 0.0F) : 1.0F;
		float glow = packWater ? 1.0F + Math.max(WaterSettings.packWaterVisibility, 0.0F) : 1.0F;
		float absorbR = sea ? WaterSettings.oceanAbsorbR : lava ? WaterSettings.lavaAbsorbR : WaterSettings.absorbR;
		float absorbG = sea ? WaterSettings.oceanAbsorbG : lava ? WaterSettings.lavaAbsorbG : WaterSettings.absorbG;
		float absorbB = sea ? WaterSettings.oceanAbsorbB : lava ? WaterSettings.lavaAbsorbB : WaterSettings.absorbB;
		float absorbLuma = 0.2126F * absorbR + 0.7152F * absorbG + 0.0722F * absorbB;
		absorbR = Math.max(absorbLuma + (absorbR - absorbLuma) * tint, 0.0F);
		absorbG = Math.max(absorbLuma + (absorbG - absorbLuma) * tint, 0.0F);
		absorbB = Math.max(absorbLuma + (absorbB - absorbLuma) * tint, 0.0F);
		putFloats(b, o + 528, absorbR, absorbG, absorbB, 0.0F);
		float scatterR = sea ? WaterSettings.oceanScatterR : lava ? WaterSettings.lavaScatterR : WaterSettings.scatterR;
		float scatterG = sea ? WaterSettings.oceanScatterG : lava ? WaterSettings.lavaScatterG : WaterSettings.scatterG;
		float scatterB = sea ? WaterSettings.oceanScatterB : lava ? WaterSettings.lavaScatterB : WaterSettings.scatterB;
		float scatterLuma = 0.2126F * scatterR + 0.7152F * scatterG + 0.0722F * scatterB;
		scatterR = Math.max(scatterLuma + (scatterR - scatterLuma) * tint, 0.0F) * glow;
		scatterG = Math.max(scatterLuma + (scatterG - scatterLuma) * tint, 0.0F) * glow;
		scatterB = Math.max(scatterLuma + (scatterB - scatterLuma) * tint, 0.0F) * glow;
		float scatterDensity = (sea ? WaterSettings.oceanScatterDensity : lava ? WaterSettings.lavaScatterDensity : WaterSettings.scatterDensity) * glow;
		putFloats(b, o + 544, scatterR, scatterG, scatterB, scatterDensity);
		putFloats(b, o + 560, WaterSettings.wwRadius, WaterSettings.foamCoverage, WaterSettings.bubbleAlpha, WaterSettings.sprayAlpha);
		putFloats(b, o + 4144, camera.fogData.environmentalStart, camera.fogData.environmentalEnd, camera.fogData.renderDistanceStart,
			camera.fogData.renderDistanceEnd);
		return packWater ? 1.05F : 1.0F;
	}

	private static void writeSea(ByteBuffer b, int o, ClientLevel level, float partialTick, double originX, double originZ, @Nullable WaterRegion r) {
		OceanWaves.Waves waves = OceanWaves.at(level.getGameTime(), partialTick, r != null && r.ocean != null ? 0.0F : OceanSim.strength(level));
		float[] phases = waves.phases(originX, originZ);
		putFloats(b, o + 4208, phases[0], phases[1], phases[2], phases[3]);
		putFloats(b, o + 4224, phases[4], phases[5], phases[6], phases[7]);
		for (int i = 0; i < OceanWaves.WAVES; i++) {
			putFloats(b, o + 4240 + i * 16, (float) waves.kx()[i], (float) waves.kz()[i], (float) waves.amp()[i], (float) waves.omega()[i]);
		}
		OceanZone zone = r != null ? r.ocean : null;
		if (zone == null) {
			boolean seaLook = r != null && r.material == Material.WATER && WaterSettings.waterSeaLook;
			putFloats(b, o + 4160, seaLook ? 0.25F : 0.0F, 0.0F, 0.0F, 0.0F);
			putFloats(b, o + 4176, 0.0F, 0.0F, 0.0F, 0.0F);
			putFloats(b, o + 4192, 0.0F, 0.0F, 0.0F, 0.0F);
			return;
		}
		int res = r.res;
		putFloats(b, o + 4160, 1.0F, (zone.x0 - r.ox) * res, (zone.z0 - r.oz) * res, Math.max(WaterSettings.oceanEdgeBand, 0.0F) * res);
		putFloats(b, o + 4176, (zone.floorY - r.oy) * res, (zone.x1 - r.ox) * res, (zone.z1 - r.oz) * res, Math.max(WaterSettings.oceanEdgePull, 0.0F));
		putFloats(b, o + 4192, OceanSim.strength(level), Math.max(WaterSettings.oceanEdgeRefill, 0.0F), Math.max(WaterSettings.oceanEdgeDrain, 0.0F),
			Math.max(WaterSettings.oceanCalming, 0.0F));
	}

	private void writePackShadow(int slot) {
		ByteBuffer b = this.uboRing.mapped();
		int o = slot * UBO_SLOT;
		float shadowStrength = WaterSettings.packShadows && ShaderCompat.packRunning() ? Mth.clamp(WaterSettings.packShadowStrength, 0.0F, 1.0F) : 0.0F;
		ShaderCompat.PackShadow map = shadowStrength > 0.0F ? this.packShadow : null;
		b.putFloat(o + 3928, shadowStrength);
		if (map != null) {
			map.modelView().get(o + 3936, b);
			map.projection().get(o + 4000, b);
			float warp = 1.0F - 25.6F * map.projection().m00();
			float lift = 0.05F + 1.5F / Math.max(1, WaterSettings.packShadowDetail);
			putFloats(b, o + 4064, 1.0F, warp, lift, 1.0F / Math.max(1, map.resolution()));
		} else {
			putFloats(b, o + 4064, 0.0F, 0.0F, 0.0F, 0.0F);
		}
		this.uboRing.flush(o, UBO_SIZE);
	}

	private List<WaterRegion> drawnZones() {
		if (!OceanMode.drawsSea()) {
			return List.of();
		}
		List<WaterRegion> out = new ArrayList<>();
		for (WaterRegion r : this.oceanSim.zones) {
			if (r.seaDrawable()) {
				out.add(r);
			}
		}
		return out;
	}

	private void writeOceanGlobals(Minecraft mc, ClientLevel level, int slot, int width, int height) {
		ByteBuffer b = this.uboRing.mapped();
		int o = slot * UBO_SLOT;
		LevelRenderState levelState = mc.gameRenderer.gameRenderState().levelRenderState;
		CameraRenderState camera = levelState.cameraRenderState;
		Vec3 cam = camera.pos;
		int bx = Mth.floor(cam.x);
		int by = Mth.floor(cam.y);
		int bz = Mth.floor(cam.z);
		putInts(b, o, bx, by, bz, 0);
		putInts(b, o + 16, 0, 0, 0, 0);
		int zones = 0;
		for (WaterRegion r : this.drawnZones()) {
			if (zones * 2 + 1 >= MAX_ENTITIES * 3) {
				break;
			}
			OceanZone z = r.ocean;
			putFloats(b, o + 576 + zones * 32, z.x0 - bx, z.floorY - by, z.z0 - bz, z.seaY - by);
			putFloats(b, o + 592 + zones * 32, z.x1 - bx, z.topY + 1 - by, z.z1 - bz, 0.0F);
			zones++;
		}
		putInts(b, o + 32, 0, 0, zones, this.frameCounter);
		putInts(b, o + 48, 0, 0, 0, 0);
		putFloats(b, o + 64, 0.0F, 0.0F, 0.0F, 0.0F);
		putFloats(b, o + 80, 0.0F, PARTICLES_PER_CELL, 0.0F, 0.0F);
		putFloats(b, o + 96, 0.0F, 1.0F, this.simTime, 0.0F);
		float glint = this.writeLook(b, o, camera, levelState.skyRenderState, width, height, false, false, false, false, 1.0F, true);
		putFloats(b, o + 432, (float) (bx - cam.x), (float) (by - cam.y), (float) (bz - cam.z), 1.0F);
		putFloats(b, o + 3136, (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz), 1.0F);
		putFloats(b, o + 3152, WaterSettings.oceanUnderwaterFog, wrap(cam.x), wrap(cam.y), wrap(cam.z));
		putFloats(b, o + 3184, WaterSettings.surfaceIso, 1.0F, WaterSettings.nearCull, WaterSettings.dropletThreshold);
		putFloats(b, o + 3792, -1.0e9F, WaterRegion.KILL_MARGIN, 0.0F, 0.0F);
		putFloats(b, o + 3808, WaterSettings.oceanDetailStrength, WaterSettings.detailScale, WaterSettings.detailSpeed, WaterSettings.oceanRoughness);
		putFloats(b, o + 3824, WaterSettings.oceanSpecular * glint, 0.0F, 0.0F, WaterSettings.foamBubbleSize);
		putFloats(b, o + 3840, Material.WATER.shaderId(), 0.0F, 0.0F, 1.0F);
		putFloats(b, o + 3888, 0.0F, 0.0F, 0.0F, 0.0F);
		float shadowStrength = WaterSettings.packShadows && ShaderCompat.packRunning() ? Mth.clamp(WaterSettings.packShadowStrength, 0.0F, 1.0F) : 0.0F;
		putFloats(b, o + 3920, this.layerOutput ? 1.0F : 0.0F, WaterSettings.underwaterMirror ? 1.0F : 0.0F, shadowStrength,
			Mth.clamp(WaterSettings.oceanSurfaceOpacity, 0.0F, 1.0F));
		putFloats(b, o + 4064, 0.0F, 0.0F, 0.0F, 0.0F);
		putFloats(b, o + 4080, 0.0F, 0.0F, 0.0F, 0.0F);
		putFloats(b, o + 4384, Math.max(WaterSettings.oceanWhitecaps, 0.0F), 0.0F,
			Math.max(WaterSettings.oceanWhitecapStart, 0.05F), (float) (this.simTime % 1000.0));
		putFloats(b, o + 4400, 0.0F, 0.0F, 0.0F, 0.0F);
		putFloats(b, o + 4416, 0.0F, 0.0F, 0.0F, 0.0F);
		putFloats(b, o + 4432, 0.0F, 0.0F, 0.0F, 0.0F);
		putFloats(b, o + 4448, 0.0F, 1.0F, 0.0F, 0.0F);
		writeSea(b, o, level, levelState.worldPartialTicks, bx, bz, null);
		this.uboRing.flush(o, UBO_SIZE);
	}

	public @Nullable Desc writeHandGlobals(Matrix4f handProjection, Matrix4f viewRotation, int width, int height) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || this.failed || Vk.tryDevice() == null) {
			return null;
		}
		if (this.uboRing == null) {
			this.uboRing = VkBuf.upload((long) UBO_SLOT * SLOTS_PER_FRAME * Vk.FRAME_RING, VkBuf.UNIFORM);
			this.frameView = VkBuf.device(16, 0);
		}
		int slot = Vk.ringSlot() * SLOTS_PER_FRAME + HAND_SLOT;
		this.writeOceanGlobals(mc, level, slot, width, height);
		ByteBuffer b = this.uboRing.mapped();
		int o = slot * UBO_SLOT;
		handProjection.get(o + 176, b);
		viewRotation.get(o + 240, b);
		new Matrix4f(handProjection).invert().get(o + 304, b);
		new Matrix4f(viewRotation).invert().get(o + 368, b);
		putInts(b, o, 0, 0, 0, 0);
		putFloats(b, o + 432, 0.0F, 0.0F, 0.0F, 1.0F);
		putFloats(b, o + 4144, 1.0e9F, 2.0e9F, 1.0e9F, 2.0e9F);
		this.uboRing.flush(o, UBO_SIZE);
		return Desc.ubo(this.uboRing, (long) slot * UBO_SLOT, UBO_SIZE);
	}

	private static final float WATER_HEAT_CAPACITY = 4.186e6F;

	private static float biomeTemperature(ClientLevel level, WaterRegion r) {
		BlockPos centre = new BlockPos(r.ox + r.sx / 2, r.oy + r.sy / 2, r.oz + r.sz / 2);
		Holder<Biome> biome = level.getBiome(centre);
		if (biome.is(Biomes.WARM_OCEAN)) {
			return 27.0F;
		}
		if (biome.is(Biomes.LUKEWARM_OCEAN) || biome.is(Biomes.DEEP_LUKEWARM_OCEAN)) {
			return 22.0F;
		}
		if (biome.is(Biomes.OCEAN) || biome.is(Biomes.DEEP_OCEAN)) {
			return 17.0F;
		}
		if (biome.is(Biomes.COLD_OCEAN) || biome.is(Biomes.DEEP_COLD_OCEAN)) {
			return 9.0F;
		}
		if (biome.is(Biomes.FROZEN_OCEAN) || biome.is(Biomes.DEEP_FROZEN_OCEAN) || biome.is(Biomes.FROZEN_RIVER)) {
			return 1.0F;
		}
		float base = biome.value().getBaseTemperature();
		return Mth.clamp(4.0F + 20.0F * base, 1.0F, 32.0F);
	}

	private void writeLeafGlobals(ClientLevel level, ByteBuffer b, int o, int res) {
		b.putFloat(o + 92, WaterSettings.leafAirDrag);
		putFloats(b, o + 3840, Material.LEAVES.shaderId(), Math.max(1, WaterSettings.leafCellCapacity), WaterSettings.leafFallSpeed * res,
			WaterSettings.leafSinkSpeed * res);
		putFloats(b, o + 3856, WaterSettings.leafFlutter, WaterSettings.leafTumbleRate, WaterSettings.leafGroundFriction, WaterSettings.leafRestTime);
		putFloats(b, o + 3872, WaterSettings.leafRestSpeed * res, WaterSettings.leafWakeSpeed * res, WaterSettings.leafKick,
			1.0F / Math.max(WaterSettings.leafBurnTime, 0.01F));
		putFloats(b, o + 3888, Math.max(1, WaterSettings.leafRepose), WaterSettings.leafBrightness, WaterSettings.leafSize, WaterSettings.leafShuffleSpeed * res);
		float rain = level.getRainLevel(1.0F);
		float thunder = level.getThunderLevel(1.0F);
		float strength = WaterSettings.leafWindStrength * rain * (1.0F + thunder) * res;
		double turn = level.getGameTime() / 2400.0;
		float heading = (float) (0.6 + 1.1 * Math.sin(turn) + 0.4 * Math.sin(turn * 2.7 + 1.0));
		putFloats(b, o + 3904, Mth.cos(heading) * strength, Mth.sin(heading) * strength, rain * (1.0F + thunder) * 0.5F, WaterSettings.leafWindLift);
	}

	private static float wrap(double v) {
		return (float) (v - Math.floor(v / WORLD_WRAP) * WORLD_WRAP);
	}

	private int writeSources(WaterRegion r, ByteBuffer b, int o) {
		int n = 0;
		for (BlockPos p : this.sourcesFor(r)) {
			if (n >= MAX_SOURCES || !r.windowContains(p, 0)) {
				continue;
			}
			putFloats(b, o + n * 16, (p.getX() - r.ox) * r.res, (p.getY() - r.oy) * r.res, (p.getZ() - r.oz) * r.res, WaterSettings.sourceFill);
			n++;
		}
		return n;
	}

	private int writeEntities(Minecraft mc, WaterRegion r, ByteBuffer b, int o, float partialTick) {
		if (r.background != null) {
			this.localPlayerFirst = false;
			return 0;
		}
		AABB window = new AABB(r.ox, r.oy, r.oz, r.ox + r.sx, r.oy + r.sy, r.oz + r.sz).inflate(1.0);
		List<Entity> entities = new ArrayList<>(mc.level.getEntities((Entity) null, window));
		for (RealisticBlockEntity body : RealisticBlockEntity.near(mc.level, window)) {
			if (!entities.contains(body)) {
				entities.add(body);
			}
		}
		this.localPlayerFirst = mc.player != null && !mc.player.isSpectator() && entities.remove(mc.player);
		if (this.localPlayerFirst) {
			entities.addFirst(mc.player);
		}
		int n = 0;
		boolean pumps = false;
		float res = r.res;
		for (Entity e : entities) {
			if (!pumps && !(this.localPlayerFirst && e == mc.player)) {
				n = this.writeWaveMakers(mc.level, r, b, o, n);
				pumps = true;
			}
			if (n >= MAX_ENTITIES) {
				break;
			}
			if (e.isSpectator()) {
				continue;
			}
			if (e instanceof RealisticBlockEntity body) {
				n = this.writeBodyCells(body, r, b, o, n, partialTick);
				continue;
			}
			if (e instanceof Minecart cart) {
				n = this.writeMinecart(cart, r, b, o, n, partialTick);
				continue;
			}
			if (e instanceof Boat || e instanceof ChestBoat) {
				n = this.writeVanillaBoat(e, r, b, o, n, partialTick);
				continue;
			}
			double ix = Mth.lerp(partialTick, e.xo, e.getX());
			double iy = Mth.lerp(partialTick, e.yo, e.getY());
			double iz = Mth.lerp(partialTick, e.zo, e.getZ());
			AABB box = e.getBoundingBox().move(ix - e.getX(), iy - e.getY(), iz - e.getZ());
			int base = o + n * 48;
			putFloats(b, base, (float) (box.minX - r.ox) * res, (float) (box.minY - r.oy) * res, (float) (box.minZ - r.oz) * res, 0.0F);
			putFloats(b, base + 16, (float) (box.maxX - r.ox) * res, (float) (box.maxY - r.oy) * res, (float) (box.maxZ - r.oz) * res, 0.0F);
			putFloats(b, base + 32, (float) (e.getX() - e.xo) * 20.0F * res, (float) (e.getY() - e.yo) * 20.0F * res, (float) (e.getZ() - e.zo) * 20.0F * res, 0.0F);
			n++;
		}
		if (!pumps) {
			n = this.writeWaveMakers(mc.level, r, b, o, n);
		}
		for (PistonMovingBlockEntity piston : movingPistons(mc.level, window)) {
			if (n >= MAX_ENTITIES) {
				break;
			}
			BlockPos p = piston.getBlockPos();
			AABB box = new AABB(p).move(piston.getXOff(partialTick), piston.getYOff(partialTick), piston.getZOff(partialTick));
			Direction d = piston.getMovementDirection();
			float speed = piston.getProgress(partialTick) < 1.0F ? 10.0F * res : 0.0F;
			int base = o + n * 48;
			putFloats(b, base, (float) (box.minX - r.ox) * res, (float) (box.minY - r.oy) * res, (float) (box.minZ - r.oz) * res, 0.0F);
			putFloats(b, base + 16, (float) (box.maxX - r.ox) * res, (float) (box.maxY - r.oy) * res, (float) (box.maxZ - r.oz) * res, 0.0F);
			putFloats(b, base + 32, d.getStepX() * speed, d.getStepY() * speed, d.getStepZ() * speed, 0.0F);
			n++;
		}
		return n;
	}

	private boolean paddleNear(ClientLevel level, WaterRegion r) {
		if (!r.hasWindow || r.material != Material.WATER) {
			return false;
		}
		for (long key : this.waterDrains) {
			BlockPos pos = BlockPos.of(key);
			if (level.hasChunkAt(pos) && WaterDrainBlock.isOpen(level.getBlockState(pos)) && r.windowContains(pos, 1)) {
				return true;
			}
		}
		for (Pump pump : this.pumps(level)) {
			if (r.windowContains(BlockPos.containing(pump.center()), 1)) {
				return true;
			}
		}
		return false;
	}

	private static final float VESSEL_MIN_WALL_CELLS = 1.25F;

	private int writeVessel(WaterRegion r, ByteBuffer b, int o, int n, double cx, double cy, double cz, Quaternionf rotation,
		double hx, double hy, double hz, double wall, double floor, double vx, double vy, double vz, double sx, double sy, double sz) {
		if (n + 2 > MAX_ENTITIES) {
			return n;
		}
		float res = r.res;
		Quaternionf q = new Quaternionf(rotation).normalize();
		if (q.w < 0.0F) {
			q.set(-q.x, -q.y, -q.z, -q.w);
		}
		double minWall = VESSEL_MIN_WALL_CELLS / res;
		double w = Math.min(Math.max(wall, minWall), Math.min(hx, hz) * 0.45);
		double f = Math.min(Math.max(floor, minWall), hy * 1.5);
		int base = o + n * 48;
		putFloats(b, base, (float) (cx - r.ox) * res, (float) (cy - r.oy) * res, (float) (cz - r.oz) * res, q.x);
		putFloats(b, base + 16, (float) vx * res, (float) vy * res, (float) vz * res, q.y);
		putFloats(b, base + 32, q.z, (float) hx * res, (float) hy * res, 4.0F);
		putFloats(b, base + 48, (float) hz * res, (float) w * res, (float) f * res, 0.0F);
		putFloats(b, base + 64, (float) sx, (float) sy, (float) sz, 0.0F);
		putFloats(b, base + 80, 0.0F, 0.0F, 0.0F, 5.0F);
		return n + 2;
	}

	private int writeMinecart(Minecart cart, WaterRegion r, ByteBuffer b, int o, int n, float partialTick) {
		double x = Mth.lerp(partialTick, cart.xo, cart.getX());
		double y = Mth.lerp(partialTick, cart.yo, cart.getY());
		double z = Mth.lerp(partialTick, cart.zo, cart.getZ());
		float yaw = Mth.rotLerp(partialTick, cart.yRotO, cart.getYRot());
		float pitch = Mth.lerp(partialTick, cart.xRotO, cart.getXRot());
		Quaternionf q = new Quaternionf();
		Vector3d centre = new Vector3d(x, y, z);
		if (cart.getBehavior() instanceof NewMinecartBehavior) {
			q.rotationY((float) Math.toRadians(yaw)).rotateZ((float) Math.toRadians(-pitch));
			org.joml.Vector3f up = q.transform(new org.joml.Vector3f(0.0F, 0.375F, 0.0F));
			centre.add(up.x, up.y, up.z);
		} else {
			q.rotationY((float) Math.toRadians(180.0F - yaw)).rotateZ((float) Math.toRadians(-pitch));
			centre.add(0.0, 0.375, 0.0);
		}
		return this.writeVessel(r, b, o, n, centre.x, centre.y, centre.z, q, 0.625, 0.3125, 0.5, 0.125, 0.125,
			(cart.getX() - cart.xo) * 20.0, (cart.getY() - cart.yo) * 20.0, (cart.getZ() - cart.zo) * 20.0, 0.0, 0.0, 0.0);
	}

	private int writeVanillaBoat(Entity boat, WaterRegion r, ByteBuffer b, int o, int n, float partialTick) {
		double x = Mth.lerp(partialTick, boat.xo, boat.getX());
		double y = Mth.lerp(partialTick, boat.yo, boat.getY());
		double z = Mth.lerp(partialTick, boat.zo, boat.getZ());
		float yaw = Mth.rotLerp(partialTick, boat.yRotO, boat.getYRot());
		double half = BoatHull.RIM * 0.5;
		Quaternionf q = new Quaternionf().rotationY((float) Math.toRadians(-yaw));
		double spin = -Math.toRadians(Mth.wrapDegrees(boat.getYRot() - boat.yRotO)) * 20.0;
		return this.writeVessel(r, b, o, n, x, y + half, z, q, BoatHull.HALF_WIDTH, half, BoatHull.HALF_LENGTH, BoatHull.WALL,
			BoatHull.floorTop(false), (boat.getX() - boat.xo) * 20.0, (boat.getY() - boat.yo) * 20.0, (boat.getZ() - boat.zo) * 20.0,
			0.0, spin, 0.0);
	}

	private static final Map<BlockState, double[]> VESSEL_SHAPES = new IdentityHashMap<>();
	private static final double[] NOT_A_VESSEL = new double[0];

	static double @Nullable [] vesselShape(BlockState state) {
		double[] cached = VESSEL_SHAPES.get(state);
		if (cached == null) {
			cached = findVesselShape(state);
			VESSEL_SHAPES.put(state, cached);
		}
		return cached.length == 0 ? null : cached;
	}

	private static double[] findVesselShape(BlockState state) {
		VoxelShape shape;
		try {
			shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		} catch (RuntimeException ex) {
			return NOT_A_VESSEL;
		}
		if (shape.isEmpty()) {
			return NOT_A_VESSEL;
		}
		AABB bounds = shape.bounds();
		if (bounds.minX > 0.01 || bounds.minZ > 0.01 || bounds.maxX < 0.99 || bounds.maxZ < 0.99 || bounds.maxY < 0.99 || bounds.minY > 0.01) {
			return NOT_A_VESSEL;
		}
		List<AABB> boxes = shape.toAabbs();
		if (solidAt(boxes, 0.5, 0.97, 0.5)) {
			return NOT_A_VESSEL;
		}
		double floor = -1.0;
		for (double y = 0.97; y >= 0.0; y -= 1.0 / 64.0) {
			if (solidAt(boxes, 0.5, y, 0.5)) {
				floor = y + 1.0 / 64.0;
				break;
			}
		}
		if (floor <= 0.0 || floor > 0.8) {
			return NOT_A_VESSEL;
		}
		double probeY = (floor + 1.0) * 0.5;
		double wall = 0.0;
		double[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (double[] side : sides) {
			double t = 0.0;
			while (t < 0.45 && solidAt(boxes, 0.5 - side[0] * (0.5 - t - 0.005), probeY, 0.5 - side[1] * (0.5 - t - 0.005))) {
				t += 1.0 / 64.0;
			}
			if (t <= 0.0 || t >= 0.45 || !solidAt(boxes, 0.5 - side[0] * 0.49, 0.97, 0.5 - side[1] * 0.49)) {
				return NOT_A_VESSEL;
			}
			wall = Math.max(wall, t);
		}
		return new double[]{wall, floor};
	}

	private static boolean solidAt(List<AABB> boxes, double x, double y, double z) {
		for (AABB box : boxes) {
			if (x >= box.minX && x < box.maxX && y >= box.minY && y < box.maxY && z >= box.minZ && z < box.maxZ) {
				return true;
			}
		}
		return false;
	}

	private static final float[] PUMP_SPEEDS = {2.0F, 4.0F, 14.0F};
	private static final double BODY_PUMP_RANGE = 96.0;

	record Pump(Object key, Vec3 center, Vec3 dir, int power, int speed, Vec3 intake, Vec3 outlet) {
	}

	static float pumpSpeed(Pump pump) {
		return PUMP_SPEEDS[pump.speed()] * WaterSettings.waveMakerSpeed;
	}

	private final List<Pump> pumpCache = new ArrayList<>();
	private long pumpCacheFrame = -1L;

	List<Pump> pumps(ClientLevel level) {
		if (this.pumpCacheFrame == this.frameCounter) {
			return this.pumpCache;
		}
		this.pumpCacheFrame = this.frameCounter;
		this.pumpCache.clear();
		LongArrayList gone = new LongArrayList();
		for (long key : this.waveMakers) {
			BlockPos p = BlockPos.of(key);
			BlockState state = level.getBlockState(p);
			if (!(state.getBlock() instanceof WaveMakerBlock)) {
				gone.add(key);
				continue;
			}
			int power = state.getValue(WaveMakerBlock.POWER);
			if (power > 0) {
				Direction facing = state.getValue(WaveMakerBlock.FACING);
				Vec3 dir = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
				Vec3 center = Vec3.atCenterOf(p);
				this.pumpCache.add(new Pump(key, center, dir, power, state.getValue(WaveMakerBlock.SPEED), center.subtract(dir), center.add(dir)));
			}
		}
		for (long key : gone) {
			this.waveMakers.remove(key);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			Vector3d c = new Vector3d();
			Vector3d d = new Vector3d();
			for (RealisticBlockEntity e : RealisticBlockEntity.near(level, mc.player.getBoundingBox().inflate(BODY_PUMP_RANGE))) {
				for (RigidBody.Cell cell : e.body.cells) {
					if (!(cell.payload instanceof BlockState state) || !(state.getBlock() instanceof WaveMakerBlock) || state.getValue(WaveMakerBlock.POWER) <= 0) {
						continue;
					}
					Direction facing = state.getValue(WaveMakerBlock.FACING);
					e.body.toWorld(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, c);
					e.body.dirToWorld(facing.getStepX(), facing.getStepY(), facing.getStepZ(), d);
					int back = 1;
					while (back < 4 && e.body.hasCell(cell.x - facing.getStepX() * back, cell.y - facing.getStepY() * back, cell.z - facing.getStepZ() * back)) {
						back++;
					}
					int front = 1;
					while (front < 4 && e.body.hasCell(cell.x + facing.getStepX() * front, cell.y + facing.getStepY() * front, cell.z + facing.getStepZ() * front)) {
						front++;
					}
					Vec3 center = new Vec3(c.x, c.y, c.z);
					Vec3 dir = new Vec3(d.x, d.y, d.z);
					this.pumpCache.add(new Pump(List.of(e.getId(), cell.x, cell.y, cell.z), center, dir, state.getValue(WaveMakerBlock.POWER),
						state.getValue(WaveMakerBlock.SPEED), center.subtract(dir.scale(back)), center.add(dir.scale(front))));
				}
			}
		}
		return this.pumpCache;
	}

	private int writeWaveMakers(ClientLevel level, WaterRegion r, ByteBuffer b, int o, int n) {
		if (r.material != Material.WATER) {
			return n;
		}
		float res = r.res;
		for (long key : this.waterDrains) {
			if (n >= MAX_ENTITIES) {
				break;
			}
			BlockPos pos = BlockPos.of(key);
			if (!level.hasChunkAt(pos) || !WaterDrainBlock.isOpen(level.getBlockState(pos)) || !r.windowContains(pos, 1)) {
				continue;
			}
			int base = o + n * 48;
			putFloats(b, base, (pos.getX() - r.ox) * res, (pos.getY() + 1.0F - r.oy) * res, (pos.getZ() - r.oz) * res, 0.0F);
			putFloats(b, base + 16, (pos.getX() + 1.0F - r.ox) * res, (pos.getY() + 1.25F - r.oy) * res, (pos.getZ() + 1.0F - r.oz) * res, 0.0F);
			putFloats(b, base + 32, 0.0F, 0.0F, 0.0F, 3.0F);
			n++;
		}
		for (Pump pump : this.pumps(level)) {
			if (n >= MAX_ENTITIES) {
				break;
			}
			if (!r.windowContains(BlockPos.containing(pump.center()), 1)) {
				continue;
			}
			Vec3 intake = pump.intake();
			Vec3 jump = pump.outlet().subtract(intake);
			float rate = WaterSettings.waveMakerRate * pump.power() / 15.0F;
			float speed = pumpSpeed(pump) * res;
			int base = o + n * 48;
			putFloats(b, base, (float) (intake.x - 0.5 - r.ox) * res, (float) (intake.y - 0.5 - r.oy) * res, (float) (intake.z - 0.5 - r.oz) * res, rate);
			putFloats(b, base + 16, (float) (intake.x + 0.5 - r.ox) * res, (float) (intake.y + 0.5 - r.oy) * res, (float) (intake.z + 0.5 - r.oz) * res, speed);
			putFloats(b, base + 32, (float) jump.x * res, (float) jump.y * res, (float) jump.z * res, 2.0F);
			n++;
		}
		return n;
	}

	private int writeBodyCells(RealisticBlockEntity e, WaterRegion r, ByteBuffer b, int o, int n, float partialTick) {
		float res = r.res;
		Vec3 now = e.renderPosition(partialTick);
		Quaternionf q = e.renderRotation(partialTick);
		if (q.w < 0.0F) {
			q.set(-q.x, -q.y, -q.z, -q.w);
		}
		e.syncClientBody(now, q);
		RigidBody body = e.body;
		Vector3d spin = e.clientSpin();
		Vector3d vel = e.clientVelocity();
		double vx = vel.x;
		double vy = vel.y;
		double vz = vel.z;
		Vector3d c = new Vector3d();
		AABB window = new AABB(r.ox, r.oy, r.oz, r.ox + r.sx, r.oy + r.sy, r.oz + r.sz).inflate(1.0);
		if (e instanceof RealisticBoatEntity boat && !boat.isRaft()) {
			double half = BoatHull.RIM * 0.5;
			body.toWorld(0.0, half, 0.0, c);
			if (!window.inflate(2.0).contains(c.x, c.y, c.z)) {
				return n;
			}
			double rx = c.x - now.x;
			double ry = c.y - now.y;
			double rz = c.z - now.z;
			return this.writeVessel(r, b, o, n, c.x, c.y, c.z, q, BoatHull.HALF_WIDTH, half, BoatHull.HALF_LENGTH, BoatHull.WALL,
				BoatHull.floorTop(false), vx + spin.y * rz - spin.z * ry, vy + spin.z * rx - spin.x * rz, vz + spin.x * ry - spin.y * rx,
				spin.x, spin.y, spin.z);
		}
		for (RigidBody.Cell cell : body.cells) {
			if (n >= MAX_ENTITIES) {
				break;
			}
			body.toWorld(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, c);
			if (!window.contains(c.x, c.y, c.z)) {
				continue;
			}
			double[] vessel = cell.payload instanceof BlockState cellState ? vesselShape(cellState) : null;
			if (vessel != null) {
				double rx = c.x - now.x;
				double ry = c.y - now.y;
				double rz = c.z - now.z;
				n = this.writeVessel(r, b, o, n, c.x, c.y, c.z, q, 0.5, 0.5, 0.5, vessel[0], vessel[1],
					vx + spin.y * rz - spin.z * ry, vy + spin.z * rx - spin.x * rz, vz + spin.x * ry - spin.y * rx, spin.x, spin.y, spin.z);
				continue;
			}
			double[][] shape = cell.payload instanceof BlockState cellState ? me.skaffy.client.realistic.BodyShapes.boxes(cellState)
				: new double[][]{{0.0, 0.0, 0.0, 1.0, 1.0, 1.0}};
			for (double[] w : shape) {
				if (n >= MAX_ENTITIES) {
					break;
				}
				body.toWorld(cell.x + (w[0] + w[3]) * 0.5, cell.y + (w[1] + w[4]) * 0.5, cell.z + (w[2] + w[5]) * 0.5, c);
				double rx = c.x - now.x;
				double ry = c.y - now.y;
				double rz = c.z - now.z;
				float cvx = (float) (vx + spin.y * rz - spin.z * ry) * res;
				float cvy = (float) (vy + spin.z * rx - spin.x * rz) * res;
				float cvz = (float) (vz + spin.x * ry - spin.y * rx) * res;
				int base = o + n * 48;
				putFloats(b, base, (float) (c.x - r.ox) * res, (float) (c.y - r.oy) * res, (float) (c.z - r.oz) * res, q.x);
				putFloats(b, base + 16, cvx, cvy, cvz, q.y);
				putFloats(b, base + 32, q.z, (float) (w[3] - w[0]) * 0.5F * res, (float) (w[4] - w[1]) * 0.5F * res,
					-Math.max((float) (w[5] - w[2]) * 0.5F * res, 1.0E-3F));
				n++;
			}
		}
		return n;
	}

	private static List<PistonMovingBlockEntity> movingPistons(ClientLevel level, AABB area) {
		List<PistonMovingBlockEntity> out = new ArrayList<>();
		for (int cx = SectionPos.blockToSectionCoord(area.minX); cx <= SectionPos.blockToSectionCoord(area.maxX); cx++) {
			for (int cz = SectionPos.blockToSectionCoord(area.minZ); cz <= SectionPos.blockToSectionCoord(area.maxZ); cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					if (be instanceof PistonMovingBlockEntity piston && area.contains(Vec3.atCenterOf(piston.getBlockPos()))) {
						out.add(piston);
					}
				}
			}
		}
		return out;
	}

	private static void putInts(ByteBuffer b, int o, int x, int y, int z, int w) {
		b.putInt(o, x).putInt(o + 4, y).putInt(o + 8, z).putInt(o + 12, w);
	}

	private static void putFloats(ByteBuffer b, int o, float x, float y, float z, float w) {
		b.putFloat(o, x).putFloat(o + 4, y).putFloat(o + 8, z).putFloat(o + 12, w);
	}


	public String statsLine() {
		if (this.regions.isEmpty() && OceanMode.active()) {
			return String.format("Realistic ocean: %,d calm sea surfaces drawn, no zones simulated right now", this.oceanSurfaces.count());
		}
		if (this.regions.isEmpty()) {
			return "Nothing simulated right now (place a Realistic Water Bucket or Realistic Sand, or use /fill)";
		}
		int fluid = 0;
		int lavaFluid = 0;
		int sand = 0;
		int ww = 0;
		int bodies = 0;
		int lavaBodies = 0;
		int slimeFluid = 0;
		int slimeBodies = 0;
		int piles = 0;
		int leafCount = 0;
		int leafPiles = 0;
		long cells = 0;
		long windowCells = 0;
		for (WaterRegion r : this.regions) {
			if (r.material.isGranular()) {
				sand += r.lastFluidCount;
				piles++;
			} else if (r.material.isLeaves()) {
				leafCount += r.lastFluidCount;
				leafPiles++;
			} else if (r.material == Material.LAVA) {
				lavaFluid += r.lastFluidCount;
				lavaBodies++;
			} else if (r.material == Material.SLIME) {
				slimeFluid += r.lastFluidCount;
				slimeBodies++;
			} else {
				fluid += r.lastFluidCount;
				bodies++;
			}
			ww += r.lastWhitewaterCount;
			if (r.box != null && r.boxWorld != null) {
				cells += WaterRegion.boxCells(r.box);
			}
			windowCells += r.numCells;
		}
		String sea = OceanMode.active() ? String.format("ocean zones %d (%,d surfaces)  |  ", this.oceanSim.zones.size(), this.oceanSurfaces.count()) : "";
		return sea + String.format("%d water bodies  fluid %,d  whitewater %,d  |  %d lava bodies  lava fluid %,d  |  %d slime bodies  slime fluid %,d  |  "
				+ "%d sand bodies  sand particles %,d  |  %d leaf piles  leaves %,d  |  "
				+ "active %,d cells of %,d allocated  |  frame %.1f ms  water steps %d x %.1f ms  sand steps %d x %.2f ms  sources %d  %s",
			bodies, fluid, ww, lavaBodies, lavaFluid, slimeBodies, slimeFluid, piles, sand, leafPiles, leafCount, cells, windowCells, this.smoothedFrameMs, this.lastSteps, this.lastDt * 1000.0F,
			this.lastSandSteps, this.lastSandDt * 1000.0F, this.sources.size(), isHost() ? "(syncing blocks)" : "(not host: blocks follow the server)");
	}

	public void clear() {
		this.waveMakerSounds.clear();
		this.boilSounds.clear();
		this.closeRegions();
		for (WaterRegion r : this.background) {
			r.close();
		}
		this.background.clear();
		this.blasts.clear();
		this.oceanBlasts.clear();
		this.oceanBlastsTaken.clear();
		this.recentBlasts.clear();
		this.sources.clear();
		this.chunkScanQueue.clear();
		this.waveMakers.clear();
		this.waterDrains.clear();
		this.portalBlocks.clear();
		this.pumpCache.clear();
		this.pumpCacheFrame = -1L;
		this.bodyFlows.clear();
		this.pendingSteam.clear();
		this.boilingBlocks.clear();
		this.scaldingDegrees.clear();
		this.lateSand.clear();
		this.lateLeaves.clear();
		this.lateSteam.clear();
		this.oceanSim.clear();
		this.oceanSurfaces.clear();
		this.cameraUnderSea = false;
		this.seaDrawsFog = false;
		for (SolidCache cache : this.solidCaches.values()) {
			cache.clear();
		}
		this.lastFrameNanos = 0L;
		this.paused = false;
		if (this.rain != null) {
			this.rain.reset();
		}
		this.failed = false;
		Vk.flushDeferredNow();
	}

	private @Nullable ClientLevel simLevel;

	private boolean ownsEvent(ClientLevel level) {
		if (level != Minecraft.getInstance().level) {
			return level == this.simLevel;
		}
		this.followLevel(level);
		return true;
	}

	private void followLevel(@Nullable ClientLevel level) {
		if (level == this.simLevel) {
			return;
		}
		List<WaterRegion> kept = level == null ? List.of() : this.keepInBackground(level);
		this.background.removeAll(kept);
		this.regions.removeAll(kept);
		this.clear();
		this.background.addAll(kept);
		this.simLevel = level;
	}

	private List<WaterRegion> keepInBackground(ClientLevel next) {
		List<WaterRegion> kept = new ArrayList<>();
		Identifier arriving = next.dimension().identifier();
		for (WaterRegion r : this.background) {
			if (!r.background.dimension.equals(arriving)) {
				kept.add(r);
			}
		}
		ClientLevel leaving = this.simLevel;
		if (leaving == null || !isHost() || this.portalBlocks.isEmpty() || leaving.dimension().identifier().equals(arriving)) {
			return kept;
		}
		BackgroundWorld world = null;
		for (WaterRegion r : this.regions) {
			if (!r.material.syncs() || r.boxWorld == null || !r.initialized() || !this.nearPortal(r)) {
				continue;
			}
			if (world == null) {
				world = new BackgroundWorld(leaving, new HashMap<>(this.solidCaches), new LongOpenHashSet(this.portalBlocks), this.sources);
				this.solidCaches.clear();
			}
			r.background = world;
			kept.add(r);
		}
		return kept;
	}

	private boolean nearPortal(WaterRegion r) {
		int[] b = r.boxWorld;
		double h = 1.0 / r.res;
		int reach = BACKGROUND_PORTAL_REACH;
		for (LongIterator it = this.portalBlocks.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			int x = BlockPos.getX(key);
			int y = BlockPos.getY(key);
			int z = BlockPos.getZ(key);
			if (x >= b[0] * h - reach && x <= b[3] * h + reach && y >= b[1] * h - reach && y <= b[4] * h + reach && z >= b[2] * h - reach
				&& z <= b[5] * h + reach) {
				return true;
			}
		}
		return false;
	}

	private void syncBackground(WaterRegion r, WaterRegion.@Nullable Fill f) {
		BackgroundWorld bg = r.background;
		Material material = r.material;
		boolean layers = material.usesLayers();
		LongArrayList positions = new LongArrayList();
		ByteArrayList amounts = new ByteArrayList();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		if (f != null) {
			int i = 0;
			for (int z = 0; z < f.dz(); z++) {
				for (int y = 0; y < f.dy(); y++) {
					for (int x = 0; x < f.dx(); x++, i++) {
						pos.set(f.x() + x, f.y() + y, f.z() + z);
						long key = pos.asLong();
						BlockState state = bg.level.getBlockState(pos);
						int inWorld = amountOf(state, material);
						if ((inWorld == 0 && !state.isAir()) || RealisticWaterBlock.isInfinite(state)) {
							continue;
						}
						int current = bg.amount(key, inWorld, material);
						int target = layers ? targetLayers(f.fullness(i), f.blocksPerSecond(i), current) : targetAmount(f.fullness(i), current);
						if (target != current) {
							positions.add(key);
							amounts.add((byte) target);
							bg.wrote(key, target, material);
						}
						if (target > 0) {
							r.owned.add(key);
						} else {
							r.owned.remove(key);
						}
					}
				}
			}
		}
		LongArrayList gone = new LongArrayList();
		for (LongIterator it = r.owned.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			if (f == null || !inFill(f, key)) {
				gone.add(key);
			}
		}
		for (long key : gone) {
			r.owned.remove(key);
			BlockState state = bg.level.getBlockState(BlockPos.of(key));
			if (bg.amount(key, amountOf(state, material), material) > 0 && !RealisticWaterBlock.isInfinite(state)) {
				positions.add(key);
				amounts.add((byte) 0);
				bg.wrote(key, 0, material);
			}
		}
		int[] b = r.boxWorld != null ? r.boxWorld : new int[]{r.ox * r.res, 0, r.oz * r.res, (r.ox + r.sx) * r.res, 0, (r.oz + r.sz) * r.res};
		int minX = Math.floorDiv(b[0], r.res);
		int minZ = Math.floorDiv(b[2], r.res);
		int maxX = Math.ceilDiv(b[3], r.res);
		int maxZ = Math.ceilDiv(b[5], r.res);
		if (!ClientPlayNetworking.canSend(BackgroundSyncPayload.TYPE)) {
			return;
		}
		int max = BackgroundSyncPayload.MAX_ENTRIES;
		int start = 0;
		do {
			int n = Math.min(max, positions.size() - start);
			long[] p = new long[n];
			byte[] a = new byte[n];
			for (int k = 0; k < n; k++) {
				p[k] = positions.getLong(start + k);
				a[k] = amounts.getByte(start + k);
			}
			ClientPlayNetworking.send(new BackgroundSyncPayload(bg.dimension, material.syncId(), p, a, minX, minZ, maxX, maxZ));
			start += max;
		} while (start < positions.size());
	}

	public void shutdown() {
		ClipRecorder.get().shutdown();
		me.skaffy.client.jelly.JellyWorld.shutdown();
		me.skaffy.client.ice.IceWorld.shutdown();
		me.skaffy.client.trees.FallenLeaves.get().close();
		this.clear();
		if (this.rain != null) {
			Vk.waitIdle();
			this.rain.close();
			this.rain = null;
		}
		if (this.renderer != null || this.sandRenderer != null || this.leafRenderer != null || this.oceanRenderer != null) {
			Vk.waitIdle();
		}
		if (this.renderer != null) {
			this.renderer.close();
			this.renderer = null;
		}
		if (this.steamRenderer != null) {
			this.steamRenderer.close();
			this.steamRenderer = null;
		}
		if (this.oceanRenderer != null) {
			this.oceanRenderer.close();
			this.oceanRenderer = null;
		}
		if (this.leafRenderer != null) {
			this.leafRenderer.close();
			this.leafRenderer = null;
		}
		if (this.sandRenderer != null) {
			this.sandRenderer.close();
			this.sandRenderer = null;
		}
		if (this.crystalRenderer != null) {
			this.crystalRenderer.close();
			this.crystalRenderer = null;
		}
		if (this.grassRenderer != null) {
			this.grassRenderer.close();
			this.grassRenderer = null;
		}
		if (this.grassFlow != null) {
			this.grassFlow.close();
			this.grassFlow = null;
		}
		if (this.treeRenderer != null) {
			this.treeRenderer.close();
			this.treeRenderer = null;
		}
		if (this.uboRing != null) {
			this.uboRing.close();
			this.frameView.close();
			if (this.layerDepth != null) {
				this.layerDepth.close();
				this.layerDepth = null;
			}
			this.uboRing = null;
			this.frameView = null;
		}
		WaterRegion.closePrograms();
		Vk.flushDeferredNow();
	}
}
