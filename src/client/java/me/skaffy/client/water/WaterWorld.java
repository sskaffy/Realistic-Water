package me.skaffy.client.water;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticSandBlock;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.fluid.RealisticWaterFluid;
import me.skaffy.net.RemoveAllWaterPayload;
import me.skaffy.net.SandSyncPayload;
import me.skaffy.net.WaterSyncPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class WaterWorld {
	private static final WaterWorld INSTANCE = new WaterWorld();
	static final int UBO_SLOT = 4096;
	static final int UBO_SIZE = 3904;
	private static final double WORLD_WRAP = 4096.0;
	static final int MAX_ENTITIES = 32;
	static final int MAX_SOURCES = 64;
	static final int MAX_REGIONS = 16;
	private static final int JOIN_DISTANCE = 16;
	private static final int SYNC_INTERVAL = 30;
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
	private final List<BlockPos> sources = new ArrayList<>();
	private final ArrayDeque<ChunkPos> chunkScanQueue = new ArrayDeque<>();
	private final Echoes waterEchoes = new Echoes();
	private final Echoes sandEchoes = new Echoes();
	private final List<Object[]> pendingFills = new ArrayList<>();
	private final FlowSounds sounds = new FlowSounds();
	private @Nullable SolidCache solids;
	private @Nullable SolidCache sandSolids;
	private @Nullable WaterRenderer renderer;
	private @Nullable SandRenderer sandRenderer;
	private @Nullable VkBuf uboRing;
	private @Nullable VkBuf frameView;
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

	public static WaterWorld get() {
		return INSTANCE;
	}

	public static boolean isRealistic(FluidState state) {
		return RealisticWaterFluid.isRealistic(state);
	}

	private static int amountOf(BlockState state) {
		FluidState fluid = state.getFluidState();
		return isRealistic(fluid) ? fluid.getAmount() : 0;
	}

	private static int amountOf(BlockState state, Material material) {
		return material == Material.SAND ? RealisticSandBlock.layers(state) : amountOf(state);
	}

	private Echoes echoes(Material material) {
		return material == Material.SAND ? this.sandEchoes : this.waterEchoes;
	}

	public boolean togglePause() {
		this.paused = !this.paused;
		return this.paused;
	}

	private SolidCache solids(Material material) {
		return this.solids(material, Math.max(1, WaterSettings.resolution));
	}

	private SolidCache solids(Material material, int res) {
		if (material == Material.SAND) {
			if (this.sandSolids == null || this.sandSolids.res != res) {
				this.sandSolids = new SolidCache(res, true);
			}
			return this.sandSolids;
		}
		if (this.solids == null || this.solids.res != res) {
			this.solids = new SolidCache(res, false);
		}
		return this.solids;
	}

	private static boolean isHost() {
		return Minecraft.getInstance().getSingleplayerServer() != null;
	}


	private @Nullable WaterRegion regionNear(BlockPos pos, int margin, Material material) {
		for (WaterRegion r : this.regions) {
			if (r.material == material && r.res == WaterSettings.resolution && r.claims(pos, margin)) {
				return r;
			}
		}
		return null;
	}

	private WaterRegion regionFor(BlockPos pos, Material material) {
		WaterRegion r = this.regionNear(pos, JOIN_DISTANCE, material);
		if (r == null) {
			r = new WaterRegion(this.nextRegionId++, Math.max(1, WaterSettings.resolution), material);
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

	private void addSource(BlockPos pos) {
		BlockPos p = pos.immutable();
		if (this.sources.contains(p) || !this.vulkanReady()) {
			return;
		}
		if (this.sources.size() >= MAX_SOURCES) {
			this.sources.removeFirst();
		}
		this.sources.add(p);
		this.regionFor(p, Material.WATER).claimBlock(p);
	}

	public void removeAll(int radiusChunks, Material material) {
		if (ClientPlayNetworking.canSend(RemoveAllWaterPayload.TYPE)) {
			ClientPlayNetworking.send(new RemoveAllWaterPayload(radiusChunks, material == Material.SAND));
		}
		for (WaterRegion r : List.copyOf(this.regions)) {
			if (r.material == material) {
				this.regions.remove(r);
				r.close();
			}
		}
		this.echoes(material).clear();
		if (material == Material.WATER) {
			this.sources.clear();
		}
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
		this.pendingFills.clear();
		this.sounds.clear();
	}


	public void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
		if (oldState == newState) {
			return;
		}
		boolean wasSpring = RealisticWaterBlock.isInfinite(oldState);
		boolean isSpring = RealisticWaterBlock.isInfinite(newState);
		int before = amountOf(oldState);
		int after = amountOf(newState);
		long key = pos.asLong();
		if (isSpring) {
			this.addSource(pos);
		} else if (wasSpring) {
			this.sources.remove(pos);
		} else if (before != after) {
			if (this.waterEchoes.consume(key, after)) {
			} else if (after > before) {
				this.addWater(pos.immutable(), before / 8.0, after >= 8 ? 1.0 : after / 8.0);
			} else {
				this.removeMaterial(pos.immutable(), after / 8.0, 1.0, Material.WATER);
			}
		}

		int sandBefore = RealisticSandBlock.layers(oldState);
		int sandAfter = RealisticSandBlock.layers(newState);
		if (sandBefore != sandAfter && !this.sandEchoes.consume(key, sandAfter)) {
			if (sandAfter > sandBefore) {
				this.addSand(pos.immutable(), sandBefore / 8.0, sandAfter / 8.0);
			} else if (newState.isAir()) {
				this.removeMaterial(pos.immutable(), 0.0, 1.0, Material.SAND);
			}
		}

		if (oldState.getBlock() != newState.getBlock() || !oldState.getCollisionShape(level, pos).equals(newState.getCollisionShape(level, pos))) {
			for (Material m : Material.values()) {
				SolidCache cache = this.solids(m);
				if (cache.update(level, pos)) {
					for (WaterRegion r : this.regions) {
						if (r.material == m && r.res == cache.res) {
							r.patchSolid(level, cache, pos);
						}
					}
				}
			}
		}
	}

	public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		for (SolidCache cache : new SolidCache[]{this.solids, this.sandSolids}) {
			if (cache != null) {
				cache.invalidateChunk(pos.x(), pos.z());
			}
		}
		for (WaterRegion r : this.regions) {
			if (r.hasWindow && r.overlapsChunk(pos.x(), pos.z())) {
				r.markSolidsDirty(level, this.solids(r.material, r.res));
			}
		}
		this.chunkScanQueue.add(pos);
	}

	public void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		for (SolidCache cache : new SolidCache[]{this.solids, this.sandSolids}) {
			if (cache != null) {
				cache.invalidateChunk(chunk.getPos().x(), chunk.getPos().z());
			}
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
				|| !section.maybeHas(state -> state.getBlock() == ModContent.REALISTIC_WATER_BLOCK || state.getBlock() == ModContent.REALISTIC_SAND)) {
				continue;
			}
			int baseY = (level.getMinSectionY() + s) << 4;
			for (int i = 0; i < 4096; i++) {
				BlockState state = section.getBlockState(i & 15, (i >> 4) & 15, i >> 8);
				int water = amountOf(state);
				int sand = RealisticSandBlock.layers(state);
				if (water == 0 && sand == 0) {
					continue;
				}
				pos.set((chunkPos.x() << 4) + (i & 15), baseY + ((i >> 4) & 15), (chunkPos.z() << 4) + (i >> 8));
				if (RealisticWaterBlock.isInfinite(state)) {
					this.addSource(pos);
					continue;
				}
				long key = pos.asLong();
				if (water > 0 && !this.owned(key, Material.WATER)) {
					this.addWater(pos.immutable(), 0.0, water >= 8 ? 1.0 : water / 8.0);
				}
				if (sand > 0 && !this.owned(key, Material.SAND)) {
					this.addSand(pos.immutable(), 0.0, sand / 8.0);
				}
			}
		}
	}

	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		if (WaterSettings.resolution != this.builtResolution) {
			this.builtResolution = WaterSettings.resolution;
			this.reload(level);
		}
		for (int i = 0; i < 4 && !this.chunkScanQueue.isEmpty(); i++) {
			this.scanChunk(level, this.chunkScanQueue.poll());
		}
		this.waterEchoes.expire(this.frameCounter);
		this.sandEchoes.expire(this.frameCounter);
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
		this.pendingFills.clear();
		this.sounds.tick(level);
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
		boolean sand = material == Material.SAND;
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
		boolean sand = material == Material.SAND;
		if (!ClientPlayNetworking.canSend(sand ? SandSyncPayload.TYPE : WaterSyncPayload.TYPE)) {
			return;
		}
		int max = sand ? SandSyncPayload.MAX_ENTRIES : WaterSyncPayload.MAX_ENTRIES;
		for (int start = 0; start < positions.size(); start += max) {
			int n = Math.min(max, positions.size() - start);
			long[] p = new long[n];
			byte[] a = new byte[n];
			for (int k = 0; k < n; k++) {
				p[k] = positions.getLong(start + k);
				a[k] = amounts.getByte(start + k);
			}
			ClientPlayNetworking.send(sand ? new SandSyncPayload(p, a) : new WaterSyncPayload(p, a));
		}
	}


	public void renderFrame() {
		Minecraft mc = Minecraft.getInstance();
		if (this.regions.isEmpty() || this.failed || mc.level == null || Vk.tryDevice() == null) {
			this.lastFrameNanos = 0L;
			return;
		}
		try {
			this.renderFrameUnsafe(mc, mc.level);
		} catch (RuntimeException e) {
			this.failed = true;
			RealisticWater.LOGGER.error("Realistic Water frame failed; disabling the simulation", e);
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal("Realistic Water failed: " + e.getMessage()));
			}
		}
	}

	private List<BlockPos> sourcesFor(WaterRegion r) {
		List<BlockPos> out = new ArrayList<>();
		if (r.material != Material.WATER) {
			return out;
		}
		for (BlockPos p : this.sources) {
			if (r.claims(p, 2)) {
				out.add(p);
			}
		}
		return out;
	}

	private void renderFrameUnsafe(Minecraft mc, ClientLevel level) {
		long now = System.nanoTime();
		Float clipDt = ClipRecorder.get().fixedDt();
		float frameDt = clipDt != null ? clipDt
			: this.lastFrameNanos == 0L ? 1.0F / 60.0F : (float) Math.min((now - this.lastFrameNanos) / 1.0e9, 0.1);
		this.lastFrameNanos = now;
		this.smoothedFrameMs = this.smoothedFrameMs * 0.9F + frameDt * 1000.0F * 0.1F;
		LevelRenderState levelState = mc.gameRenderer.gameRenderState().levelRenderState;
		Vec3 cam = levelState.cameraRenderState.pos;
		double reach = (mc.options.renderDistance().get() + 2) * 16.0;

		List<WaterRegion> active = new ArrayList<>();
		List<WaterRegion> merged = new ArrayList<>();
		List<WaterRegion> drained = new ArrayList<>();
		float fastest = 0.0F;
		for (WaterRegion r : this.regions) {
			WaterRegion.Fill fill = r.readBack();
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
			if (Math.abs(cx - cam.x) > reach || Math.abs(cz - cam.z) > reach) {
				continue;
			}
			active.add(r);
			fastest = Math.max(fastest, r.lastMaxSpeed / r.res);
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
		this.lastSteps = steps;
		this.lastDt = stepDt;
		this.simTime += steps * stepDt;

		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		VulkanGpuTexture color = (VulkanGpuTexture) main.getColorTexture();
		VulkanGpuTexture depth = (VulkanGpuTexture) main.getDepthTexture();
		VulkanGpuTextureView colorView = (VulkanGpuTextureView) main.getColorTextureView();
		VulkanGpuTextureView depthView = (VulkanGpuTextureView) main.getDepthTextureView();
		if (active.isEmpty() || color == null || depth == null || colorView == null || depthView == null) {
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
		if (this.uboRing == null) {
			this.uboRing = VkBuf.upload((long) UBO_SLOT * MAX_REGIONS * Vk.FRAME_RING, VkBuf.UNIFORM);
			this.frameView = VkBuf.device(16, 0);
		}
		int width = main.width;
		int height = main.height;
		this.renderer.prepare(width, height);

		VkCommandBuffer cb = Vk.encoder().allocateAndBeginTransientCommandBuffer();
		Vk.flushTransitions(cb);
		Vk.barrier(cb);
		this.frameView.fill(cb, 0);
		Vk.barrier(cb);
		boolean readFill = !this.paused;
		List<WaterRenderer.Body> waterBodies = new ArrayList<>();
		List<WaterRenderer.Body> sandBodies = new ArrayList<>();
		for (int i = 0; i < active.size(); i++) {
			WaterRegion r = active.get(i);
			r.planWindow(level, this.solids(r.material, r.res), r.boxWorld);
			if (readFill && r.initialized() && (this.frameCounter + r.id) % SYNC_INTERVAL == 0) {
				r.fillRequested = true;
			}
			int slot = Vk.ringSlot() * MAX_REGIONS + i;
			List<float[]> removals = r.takeRemovals();
			int numSources = this.writeGlobals(mc, level, r, slot, stepDt, width, height, removals);
			Desc ubo = Desc.ubo(this.uboRing, (long) slot * UBO_SLOT, UBO_SIZE);
			r.record(cb, ubo, steps, numSources, this.frameCounter, this.frameView, removals.size());
			(r.material == Material.SAND ? sandBodies : waterBodies).add(new WaterRenderer.Body(r, ubo));
		}
		Vk.barrier(cb);
		if (!sandBodies.isEmpty()) {
			this.sandRenderer.record(cb, sandBodies, width, height, colorView.vkImageView(), depthView.vkImageView());
			Vk.barrier(cb);
		}
		if (!waterBodies.isEmpty()) {
			this.renderer.record(cb, waterBodies, Desc.ssbo(this.frameView), color.vkImage(), colorView.vkImageView(), depth.vkImage(),
				depthView.vkImageView());
		}
		Vk.barrier(cb);
		Vk.check(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer");
		Vk.encoder().execute(cb);
		this.retire(level, merged, drained);
		Vk.endFrame();
		this.frameCounter++;
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
		boolean sand = r.material == Material.SAND;

		putInts(b, o, r.nx, r.ny, r.nz, r.numCells);
		putInts(b, o + 16, r.numU, r.numV, r.numW, r.numFaces);
		int entityCount = this.writeEntities(mc, r, b, o + 576, levelState.worldPartialTicks);
		putInts(b, o + 32, r.fluidCapacity, r.wwCapacity, entityCount, this.frameCounter);
		int sourceCount = sand ? 0 : this.writeSources(r, b, o + 2112);
		putInts(b, o + 48, !sand && WaterSettings.whitewater ? 1 : 0, sourceCount, !sand && this.localPlayerFirst ? 1 : 0, WaterSettings.wwCellLimit);
		putFloats(b, o + 64, 0.0F, -WaterSettings.gravity * res, 0.0F, dt);
		float flip = (float) Math.pow(sand ? WaterSettings.sandFlipRatio : WaterSettings.flipRatio, dt * 60.0F);
		putFloats(b, o + 80, flip, 8.0F, sand ? WaterSettings.sandVolumeCorrection : WaterSettings.volumeCorrection, 0.0F);
		putFloats(b, o + 96, WaterSettings.maxSpeed * res, h, this.simTime, 0.0F);
		putFloats(b, o + 112, WaterSettings.wwTrappedAirRate, WaterSettings.wwWaveCrestRate, WaterSettings.wwEnergyMin, WaterSettings.wwEnergyMax);
		putFloats(b, o + 128, WaterSettings.wwTrappedAirMin, WaterSettings.wwTrappedAirMax, WaterSettings.wwWaveCrestMin, WaterSettings.wwWaveCrestMax);
		putFloats(b, o + 144, WaterSettings.wwFoamLifeMin, WaterSettings.wwFoamLifeMax, WaterSettings.wwBubbleBuoyancy, WaterSettings.wwBubbleDrag);
		putFloats(b, o + 160, WaterSettings.wwSprayDrag, WaterSettings.wwSprayThreshold, WaterSettings.wwBubbleThreshold, WaterSettings.wwMaxPerParticle);

		Matrix4f proj = new Matrix4f(LEVEL_PROJECTION);
		Matrix4f view = new Matrix4f(camera.viewRotationMatrix);
		proj.get(o + 176, b);
		view.get(o + 240, b);
		new Matrix4f(proj).invert().get(o + 304, b);
		new Matrix4f(view).invert().get(o + 368, b);

		Vec3 cam = camera.pos;
		putFloats(b, o + 432, (float) (r.ox - cam.x), (float) (r.oy - cam.y), (float) (r.oz - cam.z), h);
		putFloats(b, o + 448, width, height, 1.0F / width, 1.0F / height);

		float sunAngle = sky.sunAngle;
		Vector4f sunWorld = new Vector4f(-Mth.sin(sunAngle), Mth.cos(sunAngle), 0.0F, 0.0F);
		Vector4f sunView = view.transform(new Vector4f(sunWorld));
		float sunIntensity = Mth.clamp(sunWorld.y * 4.0F, 0.0F, 1.0F) * (1.0F - sky.rainBrightness * 0.8F);
		putFloats(b, o + 464, sunView.x, sunView.y, sunView.z, sunIntensity);

		Vector3f skyColor = sky.skyColor != null ? new Vector3f(sky.skyColor) : new Vector3f(0.5F, 0.7F, 1.0F);
		Vector4f fog = camera.fogData.color;
		float ambient = Mth.clamp(0.15F + 1.2F * (0.3F * skyColor.x + 0.59F * skyColor.y + 0.11F * skyColor.z), 0.12F, 1.0F);
		putFloats(b, o + 480, skyColor.x, skyColor.y, skyColor.z, ambient);
		putFloats(b, o + 496, fog.x, fog.y, fog.z, 1.0F);
		putFloats(b, o + 512, WaterSettings.particleRadius * h, WaterSettings.thicknessScale, WaterSettings.refraction,
			sand ? WaterSettings.sandSurfaceSmoothing : WaterSettings.surfaceSmoothing);
		putFloats(b, o + 528, WaterSettings.absorbR, WaterSettings.absorbG, WaterSettings.absorbB, 0.0F);
		putFloats(b, o + 544, WaterSettings.scatterR, WaterSettings.scatterG, WaterSettings.scatterB, WaterSettings.scatterDensity);
		putFloats(b, o + 560, WaterSettings.wwRadius, WaterSettings.foamCoverage, WaterSettings.bubbleAlpha, WaterSettings.sprayAlpha);
		putFloats(b, o + 3136, (float) (cam.x - r.ox) * res, (float) (cam.y - r.oy) * res, (float) (cam.z - r.oz) * res, res);
		putFloats(b, o + 3152, WaterSettings.underwaterFog, wrap(cam.x), wrap(cam.y), wrap(cam.z));

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
		putFloats(b, o + 3792, (float) (level.getMinY() - r.oy) * res - 8.0F, WaterRegion.KILL_MARGIN, 0.0F, 0.0F);
		putFloats(b, o + 3808, WaterSettings.detailStrength, WaterSettings.detailScale, WaterSettings.detailSpeed, WaterSettings.roughness);
		putFloats(b, o + 3824, WaterSettings.specular, WaterSettings.wetDarkening, WaterSettings.filmShading, WaterSettings.foamBubbleSize);

		float cohesion = WaterSettings.sandCohesion * res * WaterSettings.gravity * res * dt;
		putFloats(b, o + 3840, r.material.shaderId(), WaterSettings.sandFriction, cohesion, sand ? WaterSettings.sandMinCellCount : 1);
		if (sand) {
			float rest = Math.min(WaterSettings.sandRestSpeed * res, 0.5F * WaterSettings.gravity * res * dt);
			putFloats(b, o + 3856, rest, WaterSettings.sandStiffness, Math.max(1, WaterSettings.sandGrainsPerParticle), WaterSettings.sandGrainSize);
		} else {
			putFloats(b, o + 3856, WaterSettings.filmFriction, WaterSettings.strandedTime, WaterSettings.strandedNeighbors, WaterSettings.filmDepth * res);
		}
		putFloats(b, o + 3872, WaterSettings.sandColorR, WaterSettings.sandColorG, WaterSettings.sandColorB, WaterSettings.sandColorVariation);
		putFloats(b, o + 3888, WaterSettings.sandGrainMinPixels, WaterSettings.sandAo, WaterSettings.sandGrainSpread, 0.0F);
		this.uboRing.flush(o, UBO_SIZE);
		return sourceCount;
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
		this.localPlayerFirst = mc.player != null && entities.remove(mc.player);
		if (this.localPlayerFirst) {
			entities.addFirst(mc.player);
		}
		int n = 0;
		float res = r.res;
		for (Entity e : entities) {
			if (n >= MAX_ENTITIES) {
				break;
			}
			if (e.isSpectator()) {
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
		return n;
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
		int sand = 0;
		int ww = 0;
		int bodies = 0;
		int piles = 0;
		long cells = 0;
		long windowCells = 0;
		for (WaterRegion r : this.regions) {
			if (r.material == Material.SAND) {
				sand += r.lastFluidCount;
				piles++;
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
		return String.format("%d water bodies  fluid %,d  whitewater %,d  |  %d sand bodies  grains %,d  |  active %,d cells of %,d allocated  |  "
				+ "frame %.1f ms  steps %d x %.1f ms  sources %d  %s",
			bodies, fluid, ww, piles, sand, cells, windowCells, this.smoothedFrameMs, this.lastSteps, this.lastDt * 1000.0F, this.sources.size(),
			isHost() ? "(syncing blocks)" : "(not host: blocks follow the server)");
	}

	public void clear() {
		this.closeRegions();
		this.sources.clear();
		this.chunkScanQueue.clear();
		this.failed = false;
	}

	public void shutdown() {
		ClipRecorder.get().shutdown();
		this.clear();
		if (this.renderer != null || this.sandRenderer != null) {
			Vk.waitIdle();
		}
		if (this.renderer != null) {
			this.renderer.close();
			this.renderer = null;
		}
		if (this.sandRenderer != null) {
			this.sandRenderer.close();
			this.sandRenderer = null;
		}
		if (this.uboRing != null) {
			this.uboRing.close();
			this.frameView.close();
			this.uboRing = null;
			this.frameView = null;
		}
		WaterRegion.closePrograms();
		Vk.flushDeferredNow();
	}
}
