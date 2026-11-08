package me.skaffy.client.ice;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.skaffy.RealisticWater;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.water.WaterSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class IceWorld {
	private static final Long2ObjectOpenHashMap<int[]> faces = new Long2ObjectOpenHashMap<>();
	private static final LongOpenHashSet iceSections = new LongOpenHashSet();
	private static final LongOpenHashSet dirty = new LongOpenHashSet();
	private static @Nullable ClientLevel level;
	private static volatile boolean active;
	private static boolean failedSaid;
	private static boolean failed;
	private static int[] packed = new int[0];
	private static int faceCount;
	private static int version;
	private static boolean changed;
	private static int relightTimer;
	private static @Nullable IceRenderer renderer;
	private static final IceBodies bodies = new IceBodies();

	private IceWorld() {
	}

	public static boolean isIce(BlockState state) {
		return state.is(Blocks.ICE) || state.is(Blocks.FROSTED_ICE);
	}

	public static boolean active() {
		return active;
	}

	public static boolean drawsBodyCell(BlockState state) {
		return active && isIce(state);
	}

	public static boolean hidesModel(net.minecraft.client.renderer.block.BlockAndTintGetter at, BlockState state) {
		return active && isIce(state) && !(at instanceof net.minecraft.client.renderer.block.MovingBlockRenderState);
	}

	private static void use(ClientLevel current) {
		if (level != current) {
			clear();
			level = current;
		}
	}

	public static void clear() {
		faces.clear();
		bodies.clear();
		iceSections.clear();
		dirty.clear();
		packed = new int[0];
		faceCount = 0;
		version++;
		level = null;
	}

	private static long key(int sx, int sy, int sz) {
		return SectionPos.asLong(sx, sy, sz);
	}

	public static void onChunkLoad(ClientLevel current, LevelChunk chunk) {
		use(current);
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		LevelChunkSection[] sections = chunk.getSections();
		for (int s = 0; s < sections.length; s++) {
			int sy = current.getMinSectionY() + s;
			if (!sections[s].hasOnlyAir() && sections[s].maybeHas(IceWorld::isIce)) {
				long k = key(cx, sy, cz);
				iceSections.add(k);
				dirty.add(k);
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				long n = key(cx + d.getStepX(), sy, cz + d.getStepZ());
				if (iceSections.contains(n)) {
					dirty.add(n);
				}
			}
		}
	}

	public static void onChunkUnload(ClientLevel current, LevelChunk chunk) {
		if (current != level) {
			return;
		}
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		iceSections.removeIf(k -> SectionPos.x(k) == cx && SectionPos.z(k) == cz);
		dirty.removeIf(k -> SectionPos.x(k) == cx && SectionPos.z(k) == cz);
		if (faces.keySet().removeIf(k -> SectionPos.x(k) == cx && SectionPos.z(k) == cz)) {
			changed = true;
		}
	}

	public static void onBlockChanged(ClientLevel current, BlockPos pos, BlockState oldState, BlockState newState) {
		use(current);
		long own = SectionPos.asLong(pos);
		if (isIce(newState)) {
			iceSections.add(own);
		}
		if (iceSections.contains(own)) {
			dirty.add(own);
		}
		for (Direction d : Direction.values()) {
			long n = SectionPos.asLong(pos.relative(d));
			if (n != own && iceSections.contains(n)) {
				dirty.add(n);
			}
		}
	}

	public static void tick(Minecraft mc) {
		boolean want = WaterSettings.realisticIce && !failed && Vk.tryDevice() != null && !me.skaffy.client.water.WaterWorld.get().drawingFailed();
		if (want != active) {
			active = want;
			mc.levelExtractor.allChanged();
			for (long k : iceSections) {
				dirty.add(k);
			}
		}
		if (mc.level == null) {
			if (level != null) {
				clear();
			}
			return;
		}
		use(mc.level);
		if (!active) {
			return;
		}
		Entity view = mc.getCameraEntity();
		if (view != null && !mc.isPaused()) {
			bodies.tick(mc, mc.level, mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos);
		}
		if (view != null && ++relightTimer >= 40) {
			relightTimer = 0;
			int sx = SectionPos.blockToSectionCoord(view.getBlockX());
			int sy = SectionPos.blockToSectionCoord(view.getBlockY());
			int sz = SectionPos.blockToSectionCoord(view.getBlockZ());
			for (LongIterator it = iceSections.iterator(); it.hasNext(); ) {
				long k = it.nextLong();
				if (Math.abs(SectionPos.x(k) - sx) <= 3 && Math.abs(SectionPos.y(k) - sy) <= 2 && Math.abs(SectionPos.z(k) - sz) <= 3) {
					dirty.add(k);
				}
			}
		}
		long until = System.nanoTime() + 4_000_000L;
		while (!dirty.isEmpty() && System.nanoTime() < until) {
			LongIterator it = dirty.iterator();
			long k = it.nextLong();
			it.remove();
			int[] built = build(mc.level, k);
			int[] old = built == null ? faces.remove(k) : faces.put(k, built);
			if (built == null && old == null) {
				iceSections.remove(k);
				continue;
			}
			if (old == null || built == null || !java.util.Arrays.equals(old, built)) {
				changed = true;
			}
		}
		if (changed) {
			changed = false;
			int total = 0;
			for (int[] f : faces.values()) {
				total += f.length;
			}
			int[] all = new int[total];
			int at = 0;
			for (int[] f : faces.values()) {
				System.arraycopy(f, 0, all, at, f.length);
				at += f.length;
			}
			packed = all;
			faceCount = total / 4;
			version++;
		}
	}

	private static int @Nullable [] build(ClientLevel lvl, long key) {
		int sx = SectionPos.x(key);
		int sy = SectionPos.y(key);
		int sz = SectionPos.z(key);
		int index = lvl.getSectionIndexFromSectionY(sy);
		if (!lvl.hasChunk(sx, sz) || index < 0 || index >= lvl.getSectionsCount()) {
			return null;
		}
		LevelChunkSection section = lvl.getChunk(sx, sz).getSection(index);
		if (section.hasOnlyAir() || !section.maybeHas(IceWorld::isIce)) {
			return null;
		}
		IntArrayList out = new IntArrayList();
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		Direction[] dirs = Direction.values();
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					if (!isIce(section.getBlockState(x, y, z))) {
						continue;
					}
					int wx = (sx << 4) + x;
					int wy = (sy << 4) + y;
					int wz = (sz << 4) + z;
					for (Direction d : dirs) {
						int nx = x + d.getStepX();
						int ny = y + d.getStepY();
						int nz = z + d.getStepZ();
						n.set(wx + d.getStepX(), wy + d.getStepY(), wz + d.getStepZ());
						BlockState other = nx >= 0 && nx < 16 && ny >= 0 && ny < 16 && nz >= 0 && nz < 16 ? section.getBlockState(nx, ny, nz)
							: lvl.getBlockState(n);
						if (isIce(other) || other.isSolidRender()) {
							continue;
						}
						int light = lvl.getBrightness(LightLayer.BLOCK, n) | lvl.getBrightness(LightLayer.SKY, n) << 4;
						out.add(wx);
						out.add(wy);
						out.add(wz);
						out.add(d.ordinal() | light << 3);
					}
				}
			}
		}
		return out.isEmpty() ? new int[0] : out.toIntArray();
	}

	public static boolean wanted() {
		return active && level != null && (faceCount > 0 || bodies.wanted());
	}

	public static void record(VkCommandBuffer cb, Desc ubo, int width, int height, int colorFormat, long colorImage, long colorView, long depthImage,
		long depthView, long lightmap) {
		if (!wanted()) {
			return;
		}
		try {
			if (renderer == null) {
				renderer = new IceRenderer();
			}
			Minecraft mc = Minecraft.getInstance();
			var state = mc.gameRenderer.gameRenderState().levelRenderState;
			float[] free = level != null ? bodies.build(mc, level, state.cameraRenderState.pos, state.worldPartialTicks) : new float[0];
			int freeVerts = level != null ? bodies.vertexCount() : 0;
			renderer.record(cb, ubo, packed, faceCount, version, free, freeVerts, width, height, colorFormat, colorImage, colorView, depthImage, depthView,
				lightmap);
		} catch (RuntimeException e) {
			failed = true;
			if (!failedSaid) {
				failedSaid = true;
				RealisticWater.LOGGER.error("Realistic ice couldn't be drawn; showing vanilla ice", e);
			}
		}
	}

	public static void shutdown() {
		if (renderer != null) {
			renderer.close();
			renderer = null;
		}
		clear();
	}
}
