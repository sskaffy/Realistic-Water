package me.skaffy.client.water;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import me.skaffy.block.RealisticSandBlock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

final class SolidCache {
	static final byte EMPTY = 0;
	static final byte FULL = 1;
	static final byte PARTIAL = 2;

	final int res;
	final boolean ignoreSand;
	private final int bitsPerBlock;
	private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
	private int minSectionY = Integer.MAX_VALUE;
	private int maxSectionY = Integer.MIN_VALUE;

	private static final class Section {
		final byte[] kind = new byte[4096];
		final long[] @Nullable [] partial = new long[4096][];
	}

	SolidCache(int res, boolean ignoreSand) {
		this.res = res;
		this.ignoreSand = ignoreSand;
		this.bitsPerBlock = res * res * res;
	}

	private static int local(int x, int y, int z) {
		return (x & 15) | (y & 15) << 4 | (z & 15) << 8;
	}

	void clear() {
		this.sections.clear();
		this.minSectionY = Integer.MAX_VALUE;
		this.maxSectionY = Integer.MIN_VALUE;
	}

	void invalidateChunk(int chunkX, int chunkZ) {
		for (int sy = this.minSectionY; sy <= this.maxSectionY; sy++) {
			this.sections.remove(SectionPos.asLong(chunkX, sy, chunkZ));
		}
	}

	boolean update(ClientLevel level, BlockPos pos) {
		Section s = this.sections.get(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
		if (s == null) {
			return true;
		}
		int i = local(pos.getX(), pos.getY(), pos.getZ());
		byte oldKind = s.kind[i];
		long[] oldBits = s.partial[i];
		this.voxelizeBlock(level, pos, level.getBlockState(pos), s, i);
		return oldKind != s.kind[i] || (s.kind[i] == PARTIAL && !java.util.Arrays.equals(oldBits, s.partial[i]));
	}

	private @Nullable Section section(ClientLevel level, int sx, int sy, int sz) {
		long key = SectionPos.asLong(sx, sy, sz);
		Section s = this.sections.get(key);
		if (s != null) {
			return s;
		}
		int sectionIndex = level.getSectionIndexFromSectionY(sy);
		if (sectionIndex >= level.getSectionsCount()) {
			s = new Section();
			this.put(key, sy, s);
			return s;
		}
		if (sectionIndex < 0 || !level.hasChunk(sx, sz)) {
			return null;
		}
		LevelChunk chunk = level.getChunk(sx, sz);
		LevelChunkSection chunkSection = chunk.getSection(sectionIndex);
		s = new Section();
		if (!chunkSection.hasOnlyAir()) {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int i = 0; i < 4096; i++) {
				int x = i & 15;
				int y = (i >> 4) & 15;
				int z = i >> 8;
				BlockState state = chunkSection.getBlockState(x, y, z);
				if (state.isAir()) {
					continue;
				}
				pos.set((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
				this.voxelizeBlock(level, pos, state, s, i);
			}
		}
		this.put(key, sy, s);
		return s;
	}

	private void put(long key, int sy, Section section) {
		this.sections.put(key, section);
		this.minSectionY = Math.min(this.minSectionY, sy);
		this.maxSectionY = Math.max(this.maxSectionY, sy);
	}

	private void voxelizeBlock(ClientLevel level, BlockPos pos, BlockState state, Section s, int i) {
		s.partial[i] = null;
		boolean skip = state.isAir() || (this.ignoreSand && state.getBlock() instanceof RealisticSandBlock);
		VoxelShape shape = skip ? null : state.getCollisionShape(level, pos);
		if (shape == null || shape.isEmpty()) {
			s.kind[i] = EMPTY;
			return;
		}
		boolean full = Block.isShapeFullBlock(shape);
		// opaque full cubes can't have gaps, see-through ones like grates still check the model
		long[] drawn = WaterSettings.modelShapes && (!full || !state.canOcclude()) ? ModelShapes.cells(state, this.res) : null;
		if (full && drawn == null) {
			s.kind[i] = FULL;
			return;
		}
		long[] bits = this.cells(full ? List.of(new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) : shape.toAabbs());
		if (drawn != null) {
			for (int w = 0; w < bits.length; w++) {
				bits[w] &= drawn[w];
			}
		}
		int count = 0;
		for (long w : bits) {
			count += Long.bitCount(w);
		}
		s.kind[i] = count == 0 ? EMPTY : count == this.bitsPerBlock ? FULL : PARTIAL;
		s.partial[i] = s.kind[i] == PARTIAL ? bits : null;
	}

	// for /water shape: one line per cell layer from the bottom, z rows split by '|', '#' is solid
	List<String> describe(ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		Section s = new Section();
		this.voxelizeBlock(level, pos, state, s, 0);
		List<String> out = new java.util.ArrayList<>();
		out.add(state + " kind=" + new String[]{"empty", "full", "partial"}[s.kind[0]] + " model=" + (ModelShapes.cells(state, this.res) != null));
		int r = this.res;
		for (int j = 0; j < r; j++) {
			StringBuilder line = new StringBuilder("y" + j + " ");
			for (int k = 0; k < r; k++) {
				for (int c = 0; c < r; c++) {
					int bit = c + r * (j + r * k);
					boolean solid = s.kind[0] == FULL || s.kind[0] == PARTIAL && ((s.partial[0][bit >> 6] >>> (bit & 63)) & 1L) != 0L;
					line.append(solid ? '#' : '.');
				}
				line.append(k < r - 1 ? "|" : "");
			}
			out.add(line.toString());
		}
		return out;
	}

	private long[] cells(List<AABB> shapeBoxes) {
		int r = this.res;
		List<AABB> boxes = shapeBoxes.stream().map(box -> atLeastOneCell(box, r)).toList();
		long[] bits = new long[(this.bitsPerBlock + 63) / 64];
		for (int k = 0; k < r; k++) {
			for (int j = 0; j < r; j++) {
				for (int c = 0; c < r; c++) {
					double cx = (c + 0.5) / r;
					double cy = (j + 0.5) / r;
					double cz = (k + 0.5) / r;
					for (AABB box : boxes) {
						if (cx >= box.minX - 1e-4 && cx <= box.maxX + 1e-4 && cy >= box.minY - 1e-4 && cy <= box.maxY + 1e-4
							&& cz >= box.minZ - 1e-4 && cz <= box.maxZ + 1e-4) {
							int bit = c + r * (j + r * k);
							bits[bit >> 6] |= 1L << (bit & 63);
							break;
						}
					}
				}
			}
		}
		return bits;
	}

	private static AABB atLeastOneCell(AABB box, int r) {
		double[] lo = {box.minX, box.minY, box.minZ};
		double[] hi = {box.maxX, box.maxY, box.maxZ};
		for (int a = 0; a < 3; a++) {
			if (hi[a] - lo[a] < 1.0 / r) {
				int cell = Mth.clamp((int) Math.floor((lo[a] + hi[a]) * 0.5 * r), 0, r - 1);
				lo[a] = (double) cell / r;
				hi[a] = (double) (cell + 1) / r;
			}
		}
		return new AABB(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
	}

	void writeBlock(ClientLevel level, int bx, int by, int bz, int[] out, int cx, int cy, int cz, int nx, int ny) {
		Section s = this.section(level, bx >> 4, by >> 4, bz >> 4);
		byte kind;
		long[] bits = null;
		if (s == null) {
			kind = FULL;
		} else {
			int i = local(bx, by, bz);
			kind = s.kind[i];
			bits = s.partial[i];
		}
		int r = this.res;
		for (int k = 0; k < r; k++) {
			for (int j = 0; j < r; j++) {
				int row = cx + nx * ((cy + j) + ny * (cz + k));
				for (int c = 0; c < r; c++) {
					int v;
					if (kind == EMPTY) {
						v = 0;
					} else if (kind == FULL) {
						v = 1;
					} else {
						int bit = c + r * (j + r * k);
						v = (int) ((bits[bit >> 6] >>> (bit & 63)) & 1L);
					}
					out[row + c] = v;
				}
			}
		}
	}
}
