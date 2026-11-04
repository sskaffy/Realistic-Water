package me.skaffy.client.water;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.nio.IntBuffer;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.ocean.OceanWaves;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class OceanSurfaces implements AutoCloseable {
	static final int ENTRY_INTS = 8;
	static final int KIND_TOP = 0;
	private static final long SCAN_BUDGET_NANOS = 2_000_000L;
	private static final int UPLOAD_INTERVAL = 15;
	private static final int NO_TOP = Integer.MIN_VALUE;
	private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	private static final class Chunk {
		final int[] entries;
		final int[] tops = new int[256];
		final float[] amps = new float[256];

		Chunk(int[] entries) {
			this.entries = entries;
		}
	}

	private static final int COLOR_CELLS = 512;
	private static final int COLOR_BITS = 9;

	private final Long2ObjectOpenHashMap<Chunk> chunks = new Long2ObjectOpenHashMap<>();
	private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
	private boolean changed;
	private int framesSinceUpload;
	private @Nullable VkBuf buffer;
	private int count;
	private final int[] colors = new int[COLOR_CELLS * COLOR_CELLS];
	private boolean colorsChanged;
	private @Nullable VkBuf colorBuffer;

	int count() {
		return this.count;
	}

	@Nullable VkBuf buffer() {
		return this.buffer;
	}

	@Nullable VkBuf colors() {
		return this.colorBuffer;
	}

	void clear() {
		this.chunks.clear();
		this.dirty.clear();
		this.changed = true;
		this.count = 0;
		if (this.buffer != null) {
			Vk.destroyLater(this.buffer);
			this.buffer = null;
		}
		java.util.Arrays.fill(this.colors, 0);
		this.colorsChanged = false;
		if (this.colorBuffer != null) {
			Vk.destroyLater(this.colorBuffer);
			this.colorBuffer = null;
		}
	}

	void chunkLoaded(int cx, int cz) {
		this.dirty.add(ChunkPos.pack(cx, cz));
		for (Direction d : SIDES) {
			long key = ChunkPos.pack(cx + d.getStepX(), cz + d.getStepZ());
			if (this.chunks.containsKey(key)) {
				this.dirty.add(key);
			}
		}
	}

	void chunkUnloaded(int cx, int cz) {
		long key = ChunkPos.pack(cx, cz);
		this.dirty.remove(key);
		if (this.chunks.remove(key) != null) {
			this.changed = true;
		}
	}

	void blockChanged(BlockPos pos) {
		int reach = OceanWaves.SHORE_REACH + 1;
		for (int cz = SectionPos.blockToSectionCoord(pos.getZ() - reach); cz <= SectionPos.blockToSectionCoord(pos.getZ() + reach); cz++) {
			for (int cx = SectionPos.blockToSectionCoord(pos.getX() - reach); cx <= SectionPos.blockToSectionCoord(pos.getX() + reach); cx++) {
				this.dirty.add(ChunkPos.pack(cx, cz));
			}
		}
	}

	void scanAll(ClientLevel level, Vec3 camera, int radiusChunks) {
		int ccx = SectionPos.blockToSectionCoord(camera.x);
		int ccz = SectionPos.blockToSectionCoord(camera.z);
		for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
			for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
				if (level.hasChunk(ccx + dx, ccz + dz)) {
					this.dirty.add(ChunkPos.pack(ccx + dx, ccz + dz));
				}
			}
		}
	}

	int restTop(int x, int z) {
		Chunk c = this.chunks.get(ChunkPos.pack(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)));
		return c == null ? NO_TOP : c.tops[(x & 15) | (z & 15) << 4];
	}

	float amplitude(int x, int z) {
		Chunk c = this.chunks.get(ChunkPos.pack(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)));
		return c == null ? 0.0F : c.amps[(x & 15) | (z & 15) << 4];
	}

	float cellAmplitude(int x0, int z0, int size, int seaTop) {
		Chunk c = this.chunks.get(ChunkPos.pack(SectionPos.blockToSectionCoord(x0), SectionPos.blockToSectionCoord(z0)));
		if (c == null) {
			return 0.0F;
		}
		float a = Float.MAX_VALUE;
		for (int z = z0; z < z0 + size; z++) {
			for (int x = x0; x < x0 + size; x++) {
				int i = (x & 15) | (z & 15) << 4;
				if (c.tops[i] != seaTop) {
					return 0.0F;
				}
				a = Math.min(a, c.amps[i]);
			}
		}
		return a;
	}

	static boolean hasTop(int top) {
		return top != NO_TOP;
	}

	void tick(ClientLevel level, Vec3 camera) {
		long start = System.nanoTime();
		while (!this.dirty.isEmpty() && System.nanoTime() - start < SCAN_BUDGET_NANOS) {
			long key = this.nearest(camera);
			this.dirty.remove(key);
			int cx = ChunkPos.getX(key);
			int cz = ChunkPos.getZ(key);
			if (!level.hasChunk(cx, cz)) {
				if (this.chunks.remove(key) != null) {
					this.changed = true;
				}
				continue;
			}
			Chunk scanned = scan(level, level.getChunk(cx, cz));
			if (scanned == null) {
				if (this.chunks.remove(key) != null) {
					this.changed = true;
				}
			} else {
				this.chunks.put(key, scanned);
				this.changed = true;
				this.paintColors(level, cx, cz, scanned.tops);
			}
		}
	}

	private void paintColors(ClientLevel level, int cx, int cz, int[] tops) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < 16; i++) {
			int lx = (i & 3) << 2 | 2;
			int lz = (i >> 2) << 2 | 2;
			int top = tops[lx | lz << 4];
			int x = (cx << 4) + lx;
			int z = (cz << 4) + lz;
			pos.set(x, top != NO_TOP ? top : level.getSeaLevel() - 1, z);
			int rgb = BiomeColors.getAverageWaterColor(level, pos) & 0xFFFFFF;
			int cellX = x >> 2;
			int cellZ = z >> 2;
			int tag = 0x80 | (cellX >> COLOR_BITS) & 7 | ((cellZ >> COLOR_BITS) & 7) << 3;
			int index = (cellX & (COLOR_CELLS - 1)) + (cellZ & (COLOR_CELLS - 1)) * COLOR_CELLS;
			int value = rgb | tag << 24;
			if (this.colors[index] != value) {
				this.colors[index] = value;
				this.colorsChanged = true;
			}
		}
	}

	private long nearest(Vec3 camera) {
		if (this.dirty.size() > 4096) {
			return this.dirty.firstLong();
		}
		int ccx = SectionPos.blockToSectionCoord(camera.x);
		int ccz = SectionPos.blockToSectionCoord(camera.z);
		long best = this.dirty.firstLong();
		long bestD = Long.MAX_VALUE;
		for (LongIterator it = this.dirty.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			long dx = ChunkPos.getX(key) - ccx;
			long dz = ChunkPos.getZ(key) - ccz;
			long d = dx * dx + dz * dz;
			if (d < bestD) {
				bestD = d;
				best = key;
			}
		}
		return best;
	}

	boolean upload() {
		this.framesSinceUpload++;
		if (this.colorsChanged && this.framesSinceUpload >= UPLOAD_INTERVAL) {
			this.colorsChanged = false;
			if (this.colorBuffer == null) {
				this.colorBuffer = VkBuf.upload((long) this.colors.length * 4L, VkBuf.STORAGE);
			}
			this.colorBuffer.mapped().asIntBuffer().put(0, this.colors);
			this.colorBuffer.flush(0, (long) this.colors.length * 4L);
		}
		if (this.changed && this.framesSinceUpload >= UPLOAD_INTERVAL) {
			this.changed = false;
			this.framesSinceUpload = 0;
			int total = 0;
			for (Chunk c : this.chunks.values()) {
				total += c.entries.length;
			}
			if (this.buffer != null) {
				Vk.destroyLater(this.buffer);
				this.buffer = null;
			}
			this.count = total / ENTRY_INTS;
			if (total > 0) {
				this.buffer = VkBuf.upload(total * 4L, VkBuf.STORAGE);
				IntBuffer out = this.buffer.mapped().asIntBuffer();
				int at = 0;
				for (Chunk c : this.chunks.values()) {
					out.put(at, c.entries);
					at += c.entries.length;
				}
				this.buffer.flush(0, total * 4L);
			}
		}
		return this.count > 0 && this.buffer != null;
	}

	private static float height(OceanWaves.States states, int x, int y, int z) {
		BlockState state = states.get(x, y, z);
		if (OceanWaves.isRestWater(state)) {
			if (OceanWaves.isRestWater(states.get(x, y + 1, z))) {
				return 1.0F;
			}
			FluidState fluid = state.getFluidState();
			return OceanMode.isVanillaWater(fluid) ? fluid.getOwnHeight() : OceanWaves.REST_HEIGHT;
		}
		return OceanWaves.isRestAir(state) || !state.isSolid() ? 0.0F : -1.0F;
	}

	private static void weigh(float[] w, float h) {
		if (h >= 0.8F) {
			w[0] += h * 10.0F;
			w[1] += 10.0F;
		} else if (h >= 0.0F) {
			w[0] += h;
			w[1]++;
		}
	}

	private static float corner(OceanWaves.States states, float self, float h2, float h1, int x, int y, int z) {
		if (h1 >= 1.0F || h2 >= 1.0F) {
			return 1.0F;
		}
		float[] w = new float[2];
		if (h1 > 0.0F || h2 > 0.0F) {
			float hc = height(states, x, y, z);
			if (hc >= 1.0F) {
				return 1.0F;
			}
			weigh(w, hc);
		}
		weigh(w, self);
		weigh(w, h1);
		weigh(w, h2);
		return w[0] / w[1];
	}

	private static int packBytes(float a, float b, float c, float d) {
		return quant(a) | quant(b) << 8 | quant(c) << 16 | quant(d) << 24;
	}

	private static int quant(float v) {
		return Math.clamp(Math.round(v * 255.0F), 0, 255);
	}

	private static @Nullable Chunk scan(ClientLevel level, LevelChunk chunk) {
		OceanWaves.States states = OceanWaves.of(level);
		LevelChunkSection[] sections = chunk.getSections();
		IntArrayList out = new IntArrayList();
		int[] tops = new int[256];
		float[] amps = new float[256];
		java.util.Arrays.fill(tops, NO_TOP);
		Long2FloatOpenHashMap ampCache = new Long2FloatOpenHashMap();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int bx = chunk.getPos().x() << 4;
		int bz = chunk.getPos().z() << 4;
		for (int s = 0; s < sections.length; s++) {
			LevelChunkSection section = sections[s];
			if (section.hasOnlyAir() || !section.maybeHas(OceanWaves::isRestWater)) {
				continue;
			}
			int baseY = (level.getMinSectionY() + s) << 4;
			for (int i = 0; i < 4096; i++) {
				int lx = i & 15;
				int ly = (i >> 4) & 15;
				int lz = i >> 8;
				BlockState state = section.getBlockState(lx, ly, lz);
				if (!OceanWaves.isRestWater(state)) {
					continue;
				}
				int x = bx + lx;
				int y = baseY + ly;
				int z = bz + lz;
				BlockState above = states.get(x, y + 1, z);
				if (OceanWaves.isRestWater(above) || above.isSolidRender()) {
					continue;
				}
				float self = height(states, x, y, z);
				float hN = height(states, x, y, z - 1);
				float hS = height(states, x, y, z + 1);
				float hW = height(states, x - 1, y, z);
				float hE = height(states, x + 1, y, z);
				float nw = corner(states, self, hN, hW, x - 1, y, z - 1);
				float sw = corner(states, self, hS, hW, x - 1, y, z + 1);
				float se = corner(states, self, hS, hE, x + 1, y, z + 1);
				float ne = corner(states, self, hN, hE, x + 1, y, z - 1);
				if (self >= 1.0F) {
					nw = sw = se = ne = 1.0F;
				}
				float c00 = columnAmp(level, states, ampCache, pos, x - 1, y, z - 1);
				float c10 = columnAmp(level, states, ampCache, pos, x, y, z - 1);
				float c20 = columnAmp(level, states, ampCache, pos, x + 1, y, z - 1);
				float c01 = columnAmp(level, states, ampCache, pos, x - 1, y, z);
				float c11 = columnAmp(level, states, ampCache, pos, x, y, z);
				float c21 = columnAmp(level, states, ampCache, pos, x + 1, y, z);
				float c02 = columnAmp(level, states, ampCache, pos, x - 1, y, z + 1);
				float c12 = columnAmp(level, states, ampCache, pos, x, y, z + 1);
				float c22 = columnAmp(level, states, ampCache, pos, x + 1, y, z + 1);
				float aNW = Math.min(Math.min(c00, c10), Math.min(c01, c11));
				float aNE = Math.min(Math.min(c10, c20), Math.min(c11, c21));
				float aSW = Math.min(Math.min(c01, c11), Math.min(c02, c12));
				float aSE = Math.min(Math.min(c11, c21), Math.min(c12, c22));
				pos.set(x, y + 1, z);
				int light = level.getBrightness(LightLayer.BLOCK, pos) | level.getBrightness(LightLayer.SKY, pos) << 4;
				Vec3 flow = state.getFluidState().getFlow(level, pos.set(x, y, z));
				int heights = packBytes(nw, sw, se, ne);
				int amp = packBytes(aNW / OceanWaves.AMP_MAX, aSW / OceanWaves.AMP_MAX, aSE / OceanWaves.AMP_MAX, aNE / OceanWaves.AMP_MAX);
				add(out, x, y, z, light, KIND_TOP, heights, amp, (float) flow.x, (float) flow.z);
				for (int k = 0; k < SIDES.length; k++) {
					Direction d = SIDES[k];
					BlockState side = states.get(x + d.getStepX(), y, z + d.getStepZ());
					if (!OceanWaves.isRestWater(side) && !side.isSolidRender()) {
						add(out, x, y, z, light, k + 1, heights, amp, 0.0F, 0.0F);
					}
				}
				int column = lx | lz << 4;
				if (y > tops[column] || tops[column] == NO_TOP) {
					tops[column] = y;
					amps[column] = c11;
				}
			}
		}
		if (out.isEmpty()) {
			return null;
		}
		Chunk c = new Chunk(out.toIntArray());
		System.arraycopy(tops, 0, c.tops, 0, 256);
		System.arraycopy(amps, 0, c.amps, 0, 256);
		return c;
	}

	private static float columnAmp(ClientLevel level, OceanWaves.States states, Long2FloatOpenHashMap cache, BlockPos.MutableBlockPos pos, int x, int y,
		int z) {
		long key = BlockPos.asLong(x, y, z);
		if (cache.containsKey(key)) {
			return cache.get(key);
		}
		float amp = 0.0F;
		if (OceanWaves.isWaveSurface(states.get(x, y, z), states.get(x, y + 1, z))) {
			amp = OceanWaves.columnAmplitude(states, x, y, z, OceanWaves.biomeFactor(level.getBiome(pos.set(x, y, z))));
			amp = Math.round(Math.min(amp, OceanWaves.AMP_MAX) / OceanWaves.AMP_MAX * 31.0F) / 31.0F * OceanWaves.AMP_MAX;
		}
		cache.put(key, amp);
		return amp;
	}

	private static void add(IntArrayList out, int x, int y, int z, int light, int kind, int heights, int amp, float flowX, float flowZ) {
		out.add(x);
		out.add(z);
		out.add((y + 32768) & 0xFFFF | (light & 0xFF) << 16 | kind << 24);
		out.add(heights);
		out.add(amp);
		out.add(Float.floatToRawIntBits(flowX));
		out.add(Float.floatToRawIntBits(flowZ));
		out.add(0);
	}

	@Override
	public void close() {
		this.clear();
	}
}
