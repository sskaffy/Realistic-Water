package me.skaffy.client.water;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.block.WaveMakerBlock;
import me.skaffy.fluid.RealisticLavaFluid;
import me.skaffy.fluid.RealisticSlimeFluid;
import me.skaffy.fluid.RealisticWaterFluid;
import me.skaffy.fluid.FluidMixing;
import me.skaffy.net.FlowPayload;
import me.skaffy.net.FluidMixPayload;
import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.RealisticBlockEntity;
import me.skaffy.realistic.RealisticPhysics;
import me.skaffy.net.LavaSyncPayload;
import me.skaffy.net.LeavesSyncPayload;
import me.skaffy.net.RemoveAllWaterPayload;
import me.skaffy.net.SandSyncPayload;
import me.skaffy.net.SlimeSyncPayload;
import me.skaffy.net.WaterSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.tags.FluidTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class WaterWorld {
	private static final WaterWorld INSTANCE = new WaterWorld();
	static final int UBO_SLOT = 8192;
	static final int UBO_SIZE = 3936;
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
	private static final int JOIN_DISTANCE = 16;
	private static final int SYNC_INTERVAL = 30;
	private static final int MIX_INTERVAL = 2;
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
	private final Map<WaterRegion, WaterRegion.Fill> latestFills = new IdentityHashMap<>();
	private final Long2IntOpenHashMap recentMixes = new Long2IntOpenHashMap();
	private final Map<Integer, double[]> bodyFlows = new HashMap<>();
	private final LongOpenHashSet waveMakers = new LongOpenHashSet();
	private final Map<Integer, SolidCache> solidCaches = new HashMap<>();
	private @Nullable WaterRenderer renderer;
	private @Nullable SandRenderer sandRenderer;
	private @Nullable LeafRenderer leafRenderer;
	private @Nullable VkBuf uboRing;
	private @Nullable VkBuf frameView;
	private @Nullable VkTex layerDepth;
	private final List<WaterRenderer.Body> lateSand = new ArrayList<>();
	private final List<WaterRenderer.Body> lateLeaves = new ArrayList<>();
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
	private float lastSandDt;

	public static WaterWorld get() {
		return INSTANCE;
	}

	public static boolean isRealistic(FluidState state) {
		return RealisticWaterFluid.isRealistic(state);
	}

	private static int waterAmount(BlockState state) {
		FluidState fluid = state.getFluidState();
		return RealisticWaterFluid.isRealistic(fluid) ? fluid.getAmount() : 0;
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

	private SolidCache solids(Material material, int res) {
		boolean granular = material.isGranular();
		return this.solidCaches.computeIfAbsent(res * 2 + (granular ? 1 : 0), k -> new SolidCache(res, granular));
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
		return Math.max(1, WaterSettings.resolution);
	}

	private static boolean isHost() {
		return Minecraft.getInstance().getSingleplayerServer() != null;
	}


	private @Nullable WaterRegion regionNear(BlockPos pos, int margin, Material material) {
		for (WaterRegion r : this.regions) {
			if (r.material == material && r.res == resolutionFor(material) && r.claims(pos, margin)) {
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

	public void addLeaves(BlockPos pos, double y0, double y1) {
		this.addMaterial(pos, y0, y1, Material.LEAVES);
	}

	private void addMaterial(BlockPos pos, double y0, double y1, Material material) {
		if (y1 <= y0 || !this.vulkanReady()) {
			return;
		}
		WaterRegion r = this.regionFor(pos, material);
		r.addEmitter(new WaterRegion.Emitter(pos.getX(), pos.getY() + y0, pos.getZ(), pos.getX() + 1, pos.getY() + y1, pos.getZ() + 1, 0, 0, 0));
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

	public void removeAll(int radiusChunks, Material material) {
		if (ClientPlayNetworking.canSend(RemoveAllWaterPayload.TYPE)) {
			ClientPlayNetworking.send(new RemoveAllWaterPayload(radiusChunks, material.syncId()));
		}
		for (WaterRegion r : List.copyOf(this.regions)) {
			if (r.material == material) {
				this.regions.remove(r);
				r.close();
			}
		}
		this.echoes(material).clear();
		this.sources.entrySet().removeIf(e -> e.getValue() == material);
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
		if (oldState == newState) {
			return;
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
		ChunkPos pos = chunk.getPos();
		for (SolidCache cache : this.solidCaches.values()) {
			cache.invalidateChunk(pos.x(), pos.z());
		}
		for (WaterRegion r : this.regions) {
			if (r.hasWindow && r.overlapsChunk(pos.x(), pos.z())) {
				r.markSolidsDirty(level, this.solids(r.material, r.res));
			}
		}
		this.chunkScanQueue.add(pos);
	}

	public void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		for (SolidCache cache : this.solidCaches.values()) {
			cache.invalidateChunk(chunk.getPos().x(), chunk.getPos().z());
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
					|| state.getBlock() == ModContent.WAVE_MAKER)) {
				continue;
			}
			int baseY = (level.getMinSectionY() + s) << 4;
			for (int i = 0; i < 4096; i++) {
				BlockState state = section.getBlockState(i & 15, (i >> 4) & 15, i >> 8);
				int water = waterAmount(state);
				int lava = lavaAmount(state);
				int slime = slimeAmount(state);
				int sand = RealisticSandBlock.layers(state);
				int leaves = RealisticLeavesBlock.layers(state);
				if (state.getBlock() == ModContent.WAVE_MAKER) {
					this.waveMakers.add(BlockPos.asLong((chunkPos.x() << 4) + (i & 15), baseY + ((i >> 4) & 15), (chunkPos.z() << 4) + (i >> 8)));
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
					this.addWater(pos.immutable(), 0.0, water >= 8 ? 1.0 : water / 8.0);
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
					this.addLeaves(pos.immutable(), 0.0, leaves / 8.0);
				}
			}
		}
	}

	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
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
		this.waveMakerSounds.tick(mc, this.pumps(level));
		this.waterEchoes.expire(this.frameCounter);
		this.sandEchoes.expire(this.frameCounter);
		this.lavaEchoes.expire(this.frameCounter);
		this.slimeEchoes.expire(this.frameCounter);
		this.leafEchoes.expire(this.frameCounter);
		Vec3 listener = mc.player != null ? mc.player.getEyePosition() : null;
		for (Object[] f : this.pendingFills) {
			WaterRegion r = (WaterRegion) f[0];
			WaterRegion.Fill fill = (WaterRegion.Fill) f[1];
			if (!this.regions.contains(r)) {
				continue;
			}
			if (listener != null) {
				this.sounds.onFill(r.material, fill, listener);
			}
			if (isHost()) {
				this.syncBlocks(level, r, fill);
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
		this.sounds.tick(level);
		this.sendBodyFlows();
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
			return current;
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

	private void syncBlocks(ClientLevel level, WaterRegion r, WaterRegion.Fill f) {
		Material material = r.material;
		boolean sand = material.usesLayers();
		Echoes echoes = this.echoes(material);
		LongArrayList positions = new LongArrayList();
		ByteArrayList amounts = new ByteArrayList();
		LongOpenHashSet seen = new LongOpenHashSet();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int i = 0;
		for (int z = 0; z < f.dz(); z++) {
			for (int y = 0; y < f.dy(); y++) {
				for (int x = 0; x < f.dx(); x++, i++) {
					pos.set(f.x() + x, f.y() + y, f.z() + z);
					long key = pos.asLong();
					seen.add(key);
					BlockState state = level.getBlockState(pos);
					int current = amountOf(state, material);
					if ((current == 0 && !state.isAir()) || RealisticWaterBlock.isInfinite(state)) {
						continue;
					}
					if (echoes.expected.containsKey(key)) {
						current = echoes.expected.get(key);
					}
					int target = sand ? targetLayers(f.fullness(i), f.blocksPerSecond(i), current) : targetAmount(f.fullness(i), current);
					if (target != current) {
						positions.add(key);
						amounts.add((byte) target);
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
		for (long key : r.owned) {
			if (!seen.contains(key)) {
				gone.add(key);
			}
		}
		for (long key : gone) {
			r.owned.remove(key);
			this.queueClear(level, key, material, positions, amounts);
		}
		this.send(material, positions, amounts);
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
		if (!WaterSettings.tntReactions || this.regions.isEmpty() || radius <= 0.0F) {
			return;
		}
		double reach = radius * Math.max(WaterSettings.tntBlastReach, 0.0F);
		this.blasts.add(new double[]{center.x, center.y, center.z, radius, reach});
		this.recentBlasts.add(new double[]{center.x, center.y, center.z, reach + 1.0, System.nanoTime() + BLAST_MEMORY_NANOS});
	}

	private static final long BLAST_MEMORY_NANOS = 2_000_000_000L;

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
		if (box == null || this.blasts.isEmpty()) {
			return out;
		}
		double h = 1.0 / r.res;
		for (double[] b : this.blasts) {
			double reach = b[4];
			double dx = Math.max(Math.max(box[0] * h - b[0], b[0] - box[3] * h), 0.0);
			double dy = Math.max(Math.max(box[1] * h - b[1], b[1] - box[4] * h), 0.0);
			double dz = Math.max(Math.max(box[2] * h - b[2], b[2] - box[5] * h), 0.0);
			if (reach <= 0.0 || dx * dx + dy * dy + dz * dz >= reach * reach) {
				continue;
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
		if (this.regions.isEmpty() || this.failed || mc.level == null || Vk.tryDevice() == null) {
			this.lastFrameNanos = 0L;
			this.blasts.clear();
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
		}
	}

	private static final double[][] FLOW_SAMPLES = {{0.8, 0.0, 0.0}, {-0.8, 0.0, 0.0}, {0.0, 0.0, 0.8}, {0.0, 0.0, -0.8}, {0.0, -0.8, 0.0}};

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
		for (RealisticBlockEntity e : level.getEntitiesOfClass(RealisticBlockEntity.class, window)) {
			for (RigidBody.Cell cell : e.body.cells) {
				e.body.toWorld(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, c);
				for (double[] d : FLOW_SAMPLES) {
					if (n >= WaterRegion.MAX_FLOW_POINTS || e.body.containsWorld(c.x + d[0], c.y + d[1], c.z + d[2])) {
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
				FlowPayload flow = new FlowPayload(e.getKey(), (float) (a[0] / n), (float) (a[1] / n), (float) (a[2] / n),
					a[5] > 0.0 ? (float) (a[4] / a[5]) : Float.NaN);
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
		if (!r.material.isFluidSim()) {
			return out;
		}
		for (Map.Entry<BlockPos, Material> e : this.sources.entrySet()) {
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
		if (WaterSettings.simDistance > 0.0F) {
			reach = Math.min(reach, WaterSettings.simDistance);
		}

		List<WaterRegion> active = new ArrayList<>();
		List<WaterRegion> merged = new ArrayList<>();
		List<WaterRegion> drained = new ArrayList<>();
		float fastest = 0.0F;
		float fastestSand = 0.0F;
		float fastestLeaf = 0.0F;
		for (WaterRegion r : this.regions) {
			WaterRegion.Fill fill = r.readBack();
			this.playIgnitions(level, r);
			r.readFlow(this.bodyFlows);
			if (fill != null) {
				this.pendingFills.add(new Object[]{r, fill});
			}
			int[] box = r.computeBoxWorld(this.sourcesFor(r), this.frameCounter);
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
			double dx = Math.max(0.0, Math.abs(cx - cam.x) - (box[3] - box[0]) * 0.5 / r.res);
			double dz = Math.max(0.0, Math.abs(cz - cam.z) - (box[5] - box[2]) * 0.5 / r.res);
			if (dx > reach || dz > reach) {
				continue;
			}
			r.camDist = Math.sqrt(dx * dx + dz * dz);
			active.add(r);
			if (r.material.isGranular()) {
				fastestSand = Math.max(fastestSand, r.lastMaxSpeed / r.res);
			} else if (r.material.isLeaves()) {
				fastestLeaf = Math.max(fastestLeaf, r.lastMaxSpeed / r.res);
			} else {
				fastest = Math.max(fastest, r.lastMaxSpeed / r.res);
			}
		}

		for (int i = 0; i < active.size(); i++) {
			WaterRegion a = active.get(i);
			for (int j = active.size() - 1; j > i; j--) {
				WaterRegion b = active.get(j);
				if (a.res == b.res && a.material == b.material && overlaps(a.boxWorld, b.boxWorld)) {
					a.boxWorld = WaterRegion.unionBox(a.boxWorld, b.boxWorld);
					a.queueAbsorb(b);
					active.remove(j);
					merged.add(b);
				}
			}
		}
		if (active.size() > MAX_REGIONS) {
			active.sort(java.util.Comparator.comparingDouble(reg -> reg.camDist));
			while (active.size() > MAX_REGIONS) {
				active.removeLast();
			}
		}
		for (WaterRegion r : active) {
			boolean far = WaterSettings.farMeshDistance > 0.0F && r.camDist > WaterSettings.farMeshDistance;
			r.meshInterval = far ? Math.max(1, WaterSettings.farMeshInterval) : 1;
		}

		int steps = 0;
		float stepDt = frameDt;
		if (!this.paused && !mc.isPaused()) {
			float speed = fastest + WaterSettings.gravity * 0.15F;
			int res = Math.max(1, WaterSettings.resolution);
			int wanted = Math.max((int) Math.ceil(frameDt * speed * res / WaterSettings.cfl), (int) Math.ceil(frameDt / WaterSettings.maxDt));
			if (clipDt == null) {
				WaterSettings.adapt(this.smoothedFrameMs);
			} else {
				WaterSettings.quality = 1.0F;
			}
			steps = Mth.clamp(wanted, 1, clipDt != null ? WaterSettings.renderMaxStepsPerFrame : WaterSettings.effectiveMaxSteps());
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
		this.lastSteps = steps;
		this.lastDt = stepDt;
		this.lastSandSteps = sandSteps;
		this.lastSandDt = sandDt;
		this.simTime += steps * stepDt;

		if (active.isEmpty() || target == null) {
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
		if (this.leafRenderer == null) {
			this.leafRenderer = new LeafRenderer();
		}
		if (this.uboRing == null) {
			this.uboRing = VkBuf.upload((long) UBO_SLOT * MAX_REGIONS * Vk.FRAME_RING, VkBuf.UNIFORM);
			this.frameView = VkBuf.device(16, 0);
		}
		int width = target.width();
		int height = target.height();
		int colorFormat = target.colorFormat();
		this.layerOutput = target.layer();
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
		Vk.barrier(cb);
		boolean readFill = !this.paused;
		List<WaterRenderer.Body> waterBodies = new ArrayList<>();
		List<WaterRenderer.Body> sandBodies = new ArrayList<>();
		List<WaterRenderer.Body> leafBodies = new ArrayList<>();
		boolean windy = level.isRaining() && WaterSettings.leafWindStrength > 0.0F && WaterSettings.leafWindLift > 0.0F;
		List<Runnable> recordings = new ArrayList<>(active.size());
		for (int i = 0; i < active.size(); i++) {
			WaterRegion r = active.get(i);
			r.planWindow(level, this.solids(r.material, r.res), r.boxWorld);
			r.updateLight(level);
			boolean sand = r.material.isGranular();
			List<float[]> regionBlasts = this.blastsFor(r);
			if (entityNear(level, r) || !regionBlasts.isEmpty() || (windy && r.material.isLeaves()) || this.paddleNear(level, r)) {
				r.wake(this.frameCounter);
			}
			boolean leaves = r.material.isLeaves();
			int regionSteps = r.dormant(!this.sourcesFor(r).isEmpty()) ? 0 : sand ? sandSteps : leaves ? leafSteps : steps;
			r.trackCalm(frameDt, regionSteps);
			int interval = nearMixPartner(r, active) ? MIX_INTERVAL : SYNC_INTERVAL;
			if (readFill && r.initialized() && (this.frameCounter + r.id) % interval == 0) {
				r.fillRequested = true;
			}
			int slot = Vk.ringSlot() * MAX_REGIONS + i;
			List<float[]> removals = r.takeRemovals();
			int numSources = this.writeGlobals(mc, level, r, slot, sand ? sandDt : leaves ? leafDt : stepDt, width, height, removals);
			Desc ubo = Desc.ubo(this.uboRing, (long) slot * UBO_SLOT, UBO_SIZE);
			FlowProbe probe = r.material == Material.WATER && isHost() ? flowProbe(level, r) : null;
			int frame = this.frameCounter;
			VkBuf view = this.frameView;
			int removalCount = removals.size();
			recordings.add(() -> {
				r.record(cb, ubo, regionSteps, numSources, frame, view, removalCount, regionBlasts);
				if (probe != null) {
					r.recordFlowProbe(cb, ubo, probe.points(), probe.owners(), probe.count());
				}
			});
			(sand ? sandBodies : leaves ? leafBodies : waterBodies).add(new WaterRenderer.Body(r, ubo));
		}
		Vk.interleave(cb, recordings);
		Vk.barrier(cb);
		this.lateSand.clear();
		this.lateLeaves.clear();
		if (target.layer()) {
			this.lateSand.addAll(sandBodies);
			this.lateLeaves.addAll(leafBodies);
			this.lateLightmap = lightmap;
		} else {
			if (!sandBodies.isEmpty()) {
				this.sandRenderer.record(cb, sandBodies, width, height, colorFormat, target.colorView(), depthView, lightmap);
				Vk.barrier(cb);
			}
			if (!leafBodies.isEmpty()) {
				this.leafRenderer.record(cb, leafBodies, width, height, colorFormat, target.colorView(), depthView, lightmap);
				Vk.barrier(cb);
			}
		}
		if (!waterBodies.isEmpty()) {
			this.renderer.record(cb, waterBodies, Desc.ssbo(this.frameView), target.colorImage(), target.colorView(), depthImage,
				depthView, lightmap, target.layer(), fluidArea(mc, waterBodies, width, height));
		}
		Vk.barrier(cb);
		Vk.endRecording();
		Vk.check(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer");
		Vk.encoder().execute(cb);
		this.retire(level, merged, drained);
		Vk.endFrame();
		this.frameCounter++;
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
	}

	public boolean hasLate() {
		return !this.lateSand.isEmpty() || !this.lateLeaves.isEmpty();
	}

	public void renderLate(FrameTarget target) {
		if (!this.hasLate() || this.failed || this.sandRenderer == null || this.leafRenderer == null) {
			this.beginLevel();
			return;
		}
		try {
			VkCommandBuffer cb = Vk.encoder().allocateAndBeginTransientCommandBuffer();
			Vk.beginRecording(cb);
			Vk.barrier(cb);
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
			r.close();
		}
		for (WaterRegion r : drained) {
			this.regions.remove(r);
			if (isHost() && !this.paused) {
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
		return !level.getEntities((Entity) null, box, e -> !e.isSpectator()).isEmpty() || !movingPistons(level, box.inflate(1.0)).isEmpty();
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
		putInts(b, o + 48, r.material.usesWhitewater() && WaterSettings.whitewater ? 1 : 0, sourceCount, !sand && this.localPlayerFirst ? 1 : 0,
			WaterSettings.wwCellLimit);
		putFloats(b, o + 64, 0.0F, -WaterSettings.gravity * res, 0.0F, dt);
		float flipRatio = lava ? WaterSettings.lavaFlipRatio : slime ? WaterSettings.slimeFlipRatio : WaterSettings.flipRatio;
		float flip = (float) Math.pow(flipRatio, dt * 60.0F);
		putFloats(b, o + 80, flip, PARTICLES_PER_CELL, slime ? WaterSettings.slimeVolumeCorrection : WaterSettings.volumeCorrection, 0.0F);
		float maxSpeed = (lava ? WaterSettings.lavaMaxSpeed : slime ? WaterSettings.slimeMaxSpeed : leaves ? WaterSettings.leafMaxSpeed : WaterSettings.maxSpeed) * res;
		putFloats(b, o + 96, maxSpeed, h, this.simTime, 0.0F);
		float wwScale = WaterSettings.adaptiveQuality ? WaterSettings.quality : 1.0F;
		putFloats(b, o + 112, WaterSettings.wwTrappedAirRate * wwScale, WaterSettings.wwWaveCrestRate * wwScale, WaterSettings.wwEnergyMin, WaterSettings.wwEnergyMax);
		putFloats(b, o + 128, WaterSettings.wwTrappedAirMin, WaterSettings.wwTrappedAirMax, WaterSettings.wwWaveCrestMin, WaterSettings.wwWaveCrestMax);
		putFloats(b, o + 144, WaterSettings.wwFoamLifeMin, WaterSettings.wwFoamLifeMax, WaterSettings.wwBubbleBuoyancy, WaterSettings.wwBubbleDrag);
		putFloats(b, o + 160, WaterSettings.wwSprayDrag, WaterSettings.wwSprayThreshold, WaterSettings.wwBubbleThreshold, WaterSettings.wwMaxPerParticle);

		Matrix4f proj = new Matrix4f(camera.projectionMatrix);
		Matrix4f bob = new Matrix4f(proj).invert().mul(LEVEL_PROJECTION);
		Matrix4f view = bob.mul(camera.viewRotationMatrix);
		proj.get(o + 176, b);
		view.get(o + 240, b);
		new Matrix4f(proj).invert().get(o + 304, b);
		new Matrix4f(view).invert().get(o + 368, b);

		Vec3 cam = camera.pos;
		putFloats(b, o + 432, (float) (r.ox - cam.x), (float) (r.oy - cam.y), (float) (r.oz - cam.z), h);
		putFloats(b, o + 448, width, height, 1.0F / width, 1.0F / height);

		Vector3f sunDir = new Matrix3f().rotateY((float) Math.toRadians(-90.0)).rotateX(sky.sunAngle).transform(new Vector3f(0.0F, 1.0F, 0.0F)).normalize();
		Vector4f sunWorld = new Vector4f(sunDir, 0.0F);
		Vector4f sunView = view.transform(new Vector4f(sunWorld));
		float sunIntensity = Mth.clamp(sunWorld.y * 4.0F, 0.0F, 1.0F) * (1.0F - sky.rainBrightness * 0.8F);
		putFloats(b, o + 464, sunView.x, sunView.y, sunView.z, sunIntensity);

		Vector3f skyColor = sky.skyColor != null ? new Vector3f(sky.skyColor) : new Vector3f(0.5F, 0.7F, 1.0F);
		Vector4f fog = camera.fogData.color;
		float ambient = Mth.clamp(0.15F + 1.2F * (0.3F * skyColor.x + 0.59F * skyColor.y + 0.11F * skyColor.z), 0.12F, 1.0F);
		putFloats(b, o + 480, skyColor.x, skyColor.y, skyColor.z, ambient);
		putFloats(b, o + 496, fog.x, fog.y, fog.z, 1.0F);
		float refraction = lava ? WaterSettings.lavaRefraction : slime ? 0.0F : WaterSettings.refraction;
		float surfaceSmooth = sand ? WaterSettings.sandSurfaceSmoothing : lava ? WaterSettings.lavaSurfaceSmoothing
			: slime ? WaterSettings.slimeSurfaceSmoothing : WaterSettings.surfaceSmoothing;
		putFloats(b, o + 512, WaterSettings.particleRadius * h, WaterSettings.thicknessScale, refraction, surfaceSmooth);
		float absorbR = lava ? WaterSettings.lavaAbsorbR : WaterSettings.absorbR;
		float absorbG = lava ? WaterSettings.lavaAbsorbG : WaterSettings.absorbG;
		float absorbB = lava ? WaterSettings.lavaAbsorbB : WaterSettings.absorbB;
		putFloats(b, o + 528, absorbR, absorbG, absorbB, 0.0F);
		float scatterR = lava ? WaterSettings.lavaScatterR : WaterSettings.scatterR;
		float scatterG = lava ? WaterSettings.lavaScatterG : WaterSettings.scatterG;
		float scatterB = lava ? WaterSettings.lavaScatterB : WaterSettings.scatterB;
		float scatterDensity = lava ? WaterSettings.lavaScatterDensity : WaterSettings.scatterDensity;
		putFloats(b, o + 544, scatterR, scatterG, scatterB, scatterDensity);
		putFloats(b, o + 560, WaterSettings.wwRadius, WaterSettings.foamCoverage, WaterSettings.bubbleAlpha, WaterSettings.sprayAlpha);
		putFloats(b, o + 3136, (float) (cam.x - r.ox) * res, (float) (cam.y - r.oy) * res, (float) (cam.z - r.oz) * res, res);
		float underwaterFog = lava ? WaterSettings.lavaUnderwaterFog : WaterSettings.underwaterFog;
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
		float detailStrength = lava ? WaterSettings.lavaDetailStrength : slime ? 0.0F : WaterSettings.detailStrength;
		float roughness = lava ? WaterSettings.lavaRoughness : WaterSettings.roughness;
		float specular = lava ? WaterSettings.lavaSpecular : slime ? 0.0F : WaterSettings.specular;
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
		if (leaves) {
			this.writeLeafGlobals(level, b, o, res);
		}
		putFloats(b, o + 3920, this.layerOutput ? 1.0F : 0.0F, WaterSettings.underwaterMirror ? 1.0F : 0.0F, 0.0F, 0.0F);
		this.uboRing.flush(o, UBO_SIZE);
		return sourceCount;
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
		AABB window = new AABB(r.ox, r.oy, r.oz, r.ox + r.sx, r.oy + r.sy, r.oz + r.sz).inflate(1.0);
		List<Entity> entities = new ArrayList<>(mc.level.getEntities((Entity) null, window));
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
		for (Pump pump : this.pumps(level)) {
			if (r.windowContains(BlockPos.containing(pump.center()), 1)) {
				return true;
			}
		}
		return false;
	}

	private static final float[] PUMP_SPEEDS = {2.0F, 4.0F, 14.0F};
	private static final double BODY_PUMP_RANGE = 96.0;

	record Pump(Object key, Vec3 center, Vec3 dir, int power, int speed, Vec3 intake, Vec3 outlet) {
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
			for (RealisticBlockEntity e : level.getEntitiesOfClass(RealisticBlockEntity.class, mc.player.getBoundingBox().inflate(BODY_PUMP_RANGE))) {
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
			float speed = PUMP_SPEEDS[pump.speed()] * WaterSettings.waveMakerSpeed * res;
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
		for (RigidBody.Cell cell : body.cells) {
			if (n >= MAX_ENTITIES) {
				break;
			}
			body.toWorld(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, c);
			if (!window.contains(c.x, c.y, c.z)) {
				continue;
			}
			double[] w = cell.waterBounds;
			if (w != null) {
				body.toWorld(cell.x + (w[0] + w[3]) * 0.5, cell.y + (w[1] + w[4]) * 0.5, cell.z + (w[2] + w[5]) * 0.5, c);
			} else {
				body.toWorld(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, c);
			}
			double rx = c.x - now.x;
			double ry = c.y - now.y;
			double rz = c.z - now.z;
			float cvx = (float) (vx + spin.y * rz - spin.z * ry) * res;
			float cvy = (float) (vy + spin.z * rx - spin.x * rz) * res;
			float cvz = (float) (vz + spin.x * ry - spin.y * rx) * res;
			int base = o + n * 48;
			putFloats(b, base, (float) (c.x - r.ox) * res, (float) (c.y - r.oy) * res, (float) (c.z - r.oz) * res, q.x);
			putFloats(b, base + 16, cvx, cvy, cvz, q.y);
			if (w != null) {
				putFloats(b, base + 32, q.z, (float) (w[3] - w[0]) * 0.5F * res, (float) (w[4] - w[1]) * 0.5F * res,
					-Math.max((float) (w[5] - w[2]) * 0.5F * res, 1.0E-3F));
			} else {
				putFloats(b, base + 32, q.z, q.w, 0.5F * res, 1.0F);
			}
			n++;
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
		return String.format("%d water bodies  fluid %,d  whitewater %,d  |  %d lava bodies  lava fluid %,d  |  %d slime bodies  slime fluid %,d  |  "
				+ "%d sand bodies  sand particles %,d  |  %d leaf piles  leaves %,d  |  "
				+ "active %,d cells of %,d allocated  |  frame %.1f ms  water steps %d x %.1f ms  sand steps %d x %.2f ms  sources %d  %s",
			bodies, fluid, ww, lavaBodies, lavaFluid, slimeBodies, slimeFluid, piles, sand, leafPiles, leafCount, cells, windowCells, this.smoothedFrameMs, this.lastSteps, this.lastDt * 1000.0F,
			this.lastSandSteps, this.lastSandDt * 1000.0F, this.sources.size(), isHost() ? "(syncing blocks)" : "(not host: blocks follow the server)");
	}

	public void clear() {
		this.waveMakerSounds.clear();
		this.closeRegions();
		this.blasts.clear();
		this.recentBlasts.clear();
		this.sources.clear();
		this.chunkScanQueue.clear();
		this.failed = false;
	}

	public void shutdown() {
		ClipRecorder.get().shutdown();
		this.clear();
		if (this.renderer != null || this.sandRenderer != null || this.leafRenderer != null) {
			Vk.waitIdle();
		}
		if (this.renderer != null) {
			this.renderer.close();
			this.renderer = null;
		}
		if (this.leafRenderer != null) {
			this.leafRenderer.close();
			this.leafRenderer = null;
		}
		if (this.sandRenderer != null) {
			this.sandRenderer.close();
			this.sandRenderer = null;
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
