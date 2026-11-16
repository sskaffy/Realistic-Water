package me.skaffy.client.heat;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.RealisticWater;
import me.skaffy.block.HotPlateBlock;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class HotBlocks {
	private static final int SECTIONS_XZ = 2;
	private static final int SECTIONS_Y = 1;
	private static final int SECTIONS_PER_TICK = 2;
	private static final int UPDATE_INTERVAL = 4;
	private static final float METAL = 1.0F;
	private static final float STONE = 2.0F;
	private static final float[] METAL_FALLOFF = {1.0F, 0.75F, 0.5F};
	private static final float[] STONE_FALLOFF = {0.85F, 0.3F};
	public static final float HOT = 0.55F;
	public static final float FLASH = 0.85F;
	private static final int TOUCH_INTERVAL = 5;
	private static final double TOUCH_RANGE = 32.0;

	private static float touching(Entity entity, BlockPos.MutableBlockPos pos, float start) {
		net.minecraft.world.phys.AABB box = entity.getBoundingBox().inflate(0.06);
		float best = 0.0F;
		for (int x = net.minecraft.util.Mth.floor(box.minX); x <= net.minecraft.util.Mth.floor(box.maxX); x++) {
			for (int y = net.minecraft.util.Mth.floor(box.minY); y <= net.minecraft.util.Mth.floor(box.maxY); y++) {
				for (int z = net.minecraft.util.Mth.floor(box.minZ); z <= net.minecraft.util.Mth.floor(box.maxZ); z++) {
					best = Math.max(best, heat.get(BlockPos.asLong(x, y, z)));
				}
			}
		}
		return best <= start ? 0.0F : (best - start) / (1.0F - start);
	}

	private static void reportTouch(Minecraft mc, ClientLevel lvl) {
		if (!WaterSettings.hotBlockDamage || heat.isEmpty() || mc.player == null
			|| !net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(me.skaffy.net.HotTouchPayload.TYPE)) {
			return;
		}
		float start = Math.clamp(WaterSettings.hotBlockGlowStart, 0.0F, 0.95F);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		float self = mc.player.isSpectator() || mc.player.isCreative() || mc.player.fireImmune() ? 0.0F : touching(mc.player, pos, start);
		it.unimi.dsi.fastutil.ints.IntArrayList ids = new it.unimi.dsi.fastutil.ints.IntArrayList();
		it.unimi.dsi.fastutil.floats.FloatArrayList heats = new it.unimi.dsi.fastutil.floats.FloatArrayList();
		if (WaterWorld.isHostClient()) {
			for (Entity entity : lvl.entitiesForRendering()) {
				if (ids.size() >= me.skaffy.net.HotTouchPayload.MAX_ENTITIES) {
					break;
				}
				if (entity == mc.player || !(entity instanceof net.minecraft.world.entity.LivingEntity) || entity.fireImmune()
					|| entity.distanceToSqr(mc.player) > TOUCH_RANGE * TOUCH_RANGE) {
					continue;
				}
				float h = touching(entity, pos, start);
				if (h > 0.0F) {
					ids.add(entity.getId());
					heats.add(h);
				}
			}
		}
		if (self > 0.0F || !ids.isEmpty()) {
			net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
				new me.skaffy.net.HotTouchPayload(self, ids.toIntArray(), heats.toFloatArray()));
		}
	}

	private static final Map<Block, Float> KINDS = new HashMap<>();
	private static final Long2ObjectOpenHashMap<Long2FloatOpenHashMap> targetsBySection = new Long2ObjectOpenHashMap<>();
	private static final Long2FloatOpenHashMap heat = new Long2FloatOpenHashMap();
	private static @Nullable ClientLevel level;
	private static int cursor;
	private static int[] packed = new int[0];
	private static int faceCount;
	private static int version;
	private static boolean failed;

	private static final VkBuf[] buffers = new VkBuf[Vk.FRAME_RING];
	private static final int[] uploaded = {-1, -1, -1};
	private static final Map<Integer, GraphicsProgram> programs = new HashMap<>();
	private static final long start = System.nanoTime();

	private HotBlocks() {
	}

	private static float kind(BlockState state) {
		Float known = KINDS.get(state.getBlock());
		if (known != null) {
			return known;
		}
		Block b = state.getBlock();
		float k = 0.0F;
		if (b == Blocks.STONE || b == Blocks.COBBLESTONE || b == Blocks.DEEPSLATE || b == Blocks.COBBLED_DEEPSLATE) {
			k = STONE;
		} else if (b == Blocks.IRON_BLOCK) {
			k = METAL;
		}
		KINDS.put(b, k);
		return k;
	}

	private static float sourceStrength(BlockState state) {
		if (state.isAir()) {
			return 0.0F;
		}
		if (state.getFluidState().is(FluidTags.LAVA) || HotPlateBlock.isHot(state)) {
			return 1.0F;
		}
		if (state.is(BlockTags.FIRE)) {
			return 0.9F;
		}
		if (state.is(BlockTags.CAMPFIRES) && state.getValue(CampfireBlock.LIT)) {
			return 0.7F;
		}
		if (state.is(Blocks.MAGMA_BLOCK)) {
			return 0.6F;
		}
		if (state.getBlock() instanceof AbstractFurnaceBlock && state.getValue(AbstractFurnaceBlock.LIT)) {
			return 0.6F;
		}
		return 0.0F;
	}

	private static boolean isSource(BlockState state) {
		return sourceStrength(state) > 0.0F;
	}

	public static boolean enabled() {
		return WaterSettings.hotBlocks && !failed;
	}

	public static float heatAt(BlockPos pos) {
		return heat.isEmpty() ? 0.0F : heat.get(pos.asLong());
	}

	public static boolean hot(BlockPos pos) {
		return heatAt(pos) >= HOT;
	}

	public static void clear() {
		targetsBySection.clear();
		heat.clear();
		packed = new int[0];
		faceCount = 0;
		version++;
		level = null;
	}

	private static void spread(ClientLevel lvl, Long2FloatOpenHashMap out, BlockPos.MutableBlockPos probe, int x, int y, int z, float strength,
		float kindFilter, int depth) {
		for (Direction d : Direction.values()) {
			int nx = x + d.getStepX();
			int ny = y + d.getStepY();
			int nz = z + d.getStepZ();
			BlockState state = lvl.getBlockState(probe.set(nx, ny, nz));
			float k = kind(state);
			if (k == 0.0F || kindFilter != 0.0F && k != kindFilter) {
				continue;
			}
			float[] falloff = k == METAL ? METAL_FALLOFF : STONE_FALLOFF;
			if (depth >= falloff.length) {
				continue;
			}
			float value = strength * falloff[depth];
			long key = BlockPos.asLong(nx, ny, nz);
			if (out.get(key) >= value) {
				continue;
			}
			out.put(key, value);
			spread(lvl, out, probe, nx, ny, nz, strength, k, depth + 1);
		}
	}

	private static @Nullable Long2FloatOpenHashMap scan(ClientLevel lvl, int sx, int sy, int sz) {
		int index = lvl.getSectionIndexFromSectionY(sy);
		if (!lvl.hasChunk(sx, sz) || index < 0 || index >= lvl.getSectionsCount()) {
			return null;
		}
		LevelChunkSection section = lvl.getChunk(sx, sz).getSection(index);
		if (section.hasOnlyAir() || !section.maybeHas(HotBlocks::isSource)) {
			return null;
		}
		Long2FloatOpenHashMap out = new Long2FloatOpenHashMap();
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					float strength = sourceStrength(section.getBlockState(x, y, z));
					if (strength > 0.0F) {
						spread(lvl, out, probe, (sx << 4) + x, (sy << 4) + y, (sz << 4) + z, strength, 0.0F, 0);
					}
				}
			}
		}
		return out.isEmpty() ? null : out;
	}

	public static void tick(Minecraft mc) {
		if (mc.level == null || !enabled()) {
			if (level != null || !heat.isEmpty()) {
				clear();
			}
			return;
		}
		if (level != mc.level) {
			clear();
			level = mc.level;
		}
		Entity view = mc.getCameraEntity();
		if (view == null || mc.isPaused()) {
			return;
		}
		ClientLevel lvl = mc.level;
		int cx = SectionPos.blockToSectionCoord(view.getBlockX());
		int cy = SectionPos.blockToSectionCoord(view.getBlockY());
		int cz = SectionPos.blockToSectionCoord(view.getBlockZ());
		int wide = SECTIONS_XZ * 2 + 1;
		int tall = SECTIONS_Y * 2 + 1;
		int total = wide * wide * tall;
		for (int n = 0; n < SECTIONS_PER_TICK; n++) {
			int i = cursor++ % total;
			int sx = cx + i % wide - SECTIONS_XZ;
			int sz = cz + (i / wide) % wide - SECTIONS_XZ;
			int sy = cy + i / (wide * wide) - SECTIONS_Y;
			long key = SectionPos.asLong(sx, sy, sz);
			Long2FloatOpenHashMap found = scan(lvl, sx, sy, sz);
			if (found == null) {
				targetsBySection.remove(key);
			} else {
				targetsBySection.put(key, found);
			}
		}
		targetsBySection.keySet().removeIf(k -> Math.abs(SectionPos.x(k) - cx) > SECTIONS_XZ + 1 || Math.abs(SectionPos.y(k) - cy) > SECTIONS_Y + 1
			|| Math.abs(SectionPos.z(k) - cz) > SECTIONS_XZ + 1);
		if (lvl.getGameTime() % TOUCH_INTERVAL == 0) {
			reportTouch(mc, lvl);
		}
		if (lvl.getGameTime() % UPDATE_INTERVAL != 0) {
			return;
		}

		Long2FloatOpenHashMap targets = new Long2FloatOpenHashMap();
		for (Long2FloatOpenHashMap m : targetsBySection.values()) {
			for (Long2FloatMap.Entry e : m.long2FloatEntrySet()) {
				if (targets.get(e.getLongKey()) < e.getFloatValue()) {
					targets.put(e.getLongKey(), e.getFloatValue());
				}
			}
		}
		float dt = UPDATE_INTERVAL / 20.0F;
		BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
		for (Long2FloatMap.Entry e : targets.long2FloatEntrySet()) {
			if (!heat.containsKey(e.getLongKey())) {
				heat.put(e.getLongKey(), 0.0F);
			}
		}
		boolean changed = false;
		var it = heat.long2FloatEntrySet().iterator();
		while (it.hasNext()) {
			Long2FloatMap.Entry e = it.next();
			long key = e.getLongKey();
			float k = kind(lvl.getBlockState(probe.set(key)));
			if (k == 0.0F) {
				it.remove();
				changed = true;
				continue;
			}
			float seconds = Math.max(k == METAL ? WaterSettings.hotBlockMetalTime : WaterSettings.hotBlockStoneTime, 0.5F);
			float target = targets.get(key);
			float now = e.getFloatValue();
			float next = now < target ? Math.min(target, now + dt / seconds) : Math.max(target, now - dt / (seconds * 1.5F));
			if (next <= 0.0F && target <= 0.0F) {
				it.remove();
				changed |= now > 0.0F;
				continue;
			}
			if (next != now) {
				e.setValue(next);
				changed = true;
			}
		}
		if (changed) {
			rebuild(lvl);
		}
	}

	private static void rebuild(ClientLevel lvl) {
		IntArrayList out = new IntArrayList();
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		float threshold = Math.clamp(WaterSettings.hotBlockGlowStart, 0.0F, 0.95F);
		for (Long2FloatMap.Entry e : heat.long2FloatEntrySet()) {
			float glow = (e.getFloatValue() - threshold) / (1.0F - threshold);
			if (glow <= 0.004F) {
				continue;
			}
			long key = e.getLongKey();
			int x = BlockPos.getX(key);
			int y = BlockPos.getY(key);
			int z = BlockPos.getZ(key);
			int level255 = Math.clamp(Math.round(glow * 255.0F), 1, 255);
			for (Direction d : Direction.values()) {
				BlockState other = lvl.getBlockState(n.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()));
				if (other.isSolidRender()) {
					continue;
				}
				out.add(x);
				out.add(y);
				out.add(z);
				out.add(d.ordinal() | level255 << 3);
			}
		}
		packed = out.toIntArray();
		faceCount = packed.length / 4;
		version++;
	}

	public static boolean wanted() {
		return enabled() && level != null && faceCount > 0 && !WaterWorld.get().drawingFailed();
	}

	public static void record(VkCommandBuffer cb, Desc ubo, int width, int height, int colorFormat, long colorView, long depthView) {
		if (!wanted()) {
			return;
		}
		try {
			int slot = Vk.ringSlot();
			long bytes = (long) faceCount * 16L;
			VkBuf buf = buffers[slot];
			if (buf == null || buf.size < bytes) {
				if (buf != null) {
					Vk.destroyLater(buf);
				}
				buf = VkBuf.upload(Math.max(bytes + bytes / 2, 16L * 1024L), VkBuf.STORAGE);
				buffers[slot] = buf;
				uploaded[slot] = -1;
			}
			if (uploaded[slot] != version) {
				buf.mapped().asIntBuffer().put(0, packed, 0, faceCount * 4);
				buf.flush(0, bytes);
				uploaded[slot] = version;
			}
			GraphicsProgram program = programs.computeIfAbsent(colorFormat, f -> new GraphicsProgram("hot.vert", "hot_block.frag", Map.of(),
				new GraphicsProgram.State(f, VkTex.D32, true, false, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.PREMULTIPLIED), Desc.UBO, Desc.SSBO));
			float time = (float) ((System.nanoTime() - start) / 1.0e9 % 1000.0);
			GraphicsProgram.begin(cb, width, height, colorView, null, depthView);
			program.bind(cb, ubo, Desc.ssbo(buf, 0, bytes));
			program.push(cb, Program.f(time), Program.f(Math.clamp(WaterSettings.hotBlockGlow, 0.0F, 1.0F)), Program.f(96.0F), 0, 0, 0, 0, 0, 0, 0, 0, 0,
				0, 0, 0, 0);
			program.draw(cb, faceCount * 6, 1);
			GraphicsProgram.end(cb);
		} catch (RuntimeException e) {
			failed = true;
			RealisticWater.LOGGER.error("Hot block glow couldn't be drawn; turning it off", e);
		}
	}

	public static void shutdown() {
		programs.values().forEach(GraphicsProgram::close);
		programs.clear();
		for (int i = 0; i < buffers.length; i++) {
			if (buffers[i] != null) {
				buffers[i].close();
				buffers[i] = null;
			}
			uploaded[i] = -1;
		}
		clear();
	}
}
