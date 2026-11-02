package me.skaffy.client.water;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.skaffy.RealisticWater;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

// collision shapes fill gaps on purpose (a fence is a 1.5 block wall), so fluids go by what the model draws
final class ModelShapes {
	private static final int G = 32;
	private static final Direction[] SIDES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, null};
	private static final Map<BlockState, Entry> CACHE = new ConcurrentHashMap<>();

	private record Entry(BlockStateModel model, boolean @Nullable [] solid, Map<Integer, long[]> cells) {
	}

	private ModelShapes() {
	}

	// bits are c + res * (j + res * k), null means use the collision shape
	static long @Nullable [] cells(BlockState state, int res) {
		if (state.getRenderShape() != RenderShape.MODEL || state.hasOffsetFunction() || state.is(BlockTags.LEAVES)
			|| BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals(RealisticWater.MOD_ID)) {
			return null;
		}
		BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
		Entry e = CACHE.get(state);
		if (e == null || e.model != model) {
			// clear pixels in glass are still glass, in bars and ladders they're gaps
			e = new Entry(model, voxelize(model, state, state.getSoundType() != SoundType.GLASS), new ConcurrentHashMap<>());
			CACHE.put(state, e);
		}
		boolean[] solid = e.solid;
		return solid == null ? null : e.cells.computeIfAbsent(res, r -> toCells(solid, r));
	}

	private static int index(int x, int y, int z) {
		return x + G * (y + G * z);
	}

	// solid if half full, or if half the lines through it on one axis hit a run thinner than a cell centered here.
	// keeps panes watertight without the edge of a thick rail closing the gap next to it
	private static long[] toCells(boolean[] solid, int r) {
		long[] bits = new long[(r * r * r + 63) / 64];
		float size = (float) G / r;
		int[] lo = new int[r];
		int[] hi = new int[r];
		for (int v = G - 1; v >= 0; v--) {
			int c = Math.min(r - 1, (int) ((v + 0.5F) / size));
			lo[c] = v;
			hi[c] = Math.max(hi[c], v + 1);
		}
		for (int k = 0; k < r; k++) {
			for (int j = 0; j < r; j++) {
				for (int c = 0; c < r; c++) {
					int[][] range = {{lo[c], hi[c]}, {lo[j], hi[j]}, {lo[k], hi[k]}};
					int filled = 0;
					int total = 0;
					for (int z = range[2][0]; z < range[2][1]; z++) {
						for (int y = range[1][0]; y < range[1][1]; y++) {
							for (int x = range[0][0]; x < range[0][1]; x++) {
								total++;
								filled += solid[index(x, y, z)] ? 1 : 0;
							}
						}
					}
					int[] cell = {c, j, k};
					boolean hit = filled * 2 >= total;
					for (int a = 0; a < 3 && !hit; a++) {
						hit = sheet(solid, range, a, cell[a], size, r);
					}
					if (hit) {
						int bit = c + r * (j + r * k);
						bits[bit >> 6] |= 1L << (bit & 63);
					}
				}
			}
		}
		return bits;
	}

	private static boolean sheet(boolean[] solid, int[][] range, int a, int cell, float size, int r) {
		int b = (a + 1) % 3;
		int d = (a + 2) % 3;
		int[] q = new int[3];
		int lines = 0;
		int hits = 0;
		for (int u = range[b][0]; u < range[b][1]; u++) {
			for (int w = range[d][0]; w < range[d][1]; w++) {
				q[b] = u;
				q[d] = w;
				lines++;
				hits += thinRun(solid, q, a, range[a][0], range[a][1], cell, size, r) ? 1 : 0;
			}
		}
		return hits * 2 >= lines;
	}

	private static boolean thinRun(boolean[] solid, int[] q, int a, int lo, int hi, int cell, float size, int r) {
		for (int v = lo; v < hi; v++) {
			q[a] = v;
			if (!solid[index(q[0], q[1], q[2])]) {
				continue;
			}
			int s = v;
			while (s > 0) {
				q[a] = s - 1;
				if (!solid[index(q[0], q[1], q[2])]) {
					break;
				}
				s--;
			}
			int e = v;
			while (e < G - 1) {
				q[a] = e + 1;
				if (!solid[index(q[0], q[1], q[2])]) {
					break;
				}
				e++;
			}
			if (e - s + 1 < size && Mth.clamp((int) Math.floor((s + e + 1) * 0.5F / size), 0, r - 1) == cell) {
				return true;
			}
			v = e;
		}
		return false;
	}

	private static boolean @Nullable [] voxelize(BlockStateModel model, BlockState state, boolean holes) {
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(42L), parts);
		boolean[] wall = new boolean[G * G * G];
		// end strips of bars and panes get culled when connected. they still close cuboids for the fill so fence rails
		// stay solid, they just don't leave anything of their own
		boolean[] hidden = new boolean[G * G * G];
		boolean any = false;
		for (BlockStateModelPart part : parts) {
			for (Direction side : SIDES) {
				BooleanProperty link = side == null ? null : PipeBlock.PROPERTY_BY_DIRECTION.get(side);
				boolean[] into = link != null && state.hasProperty(link) && state.getValue(link) ? hidden : wall;
				for (BakedQuad quad : part.getQuads(side)) {
					any |= rasterize(quad, into, holes ? quad.materialInfo().sprite() : null);
				}
			}
		}
		if (!any) {
			return null;
		}
		boolean[] closed = wall.clone();
		for (int i = 0; i < closed.length; i++) {
			closed[i] |= hidden[i];
		}
		boolean[] solid = inside(closed);
		for (int i = 0; i < solid.length; i++) {
			solid[i] &= !hidden[i] || wall[i];
		}
		return solid;
	}

	private static boolean clear(TextureAtlasSprite sprite, float u, float v) {
		SpriteContents tex = sprite.contents();
		float du = sprite.getU1() - sprite.getU0();
		float dv = sprite.getV1() - sprite.getV0();
		if (du == 0.0F || dv == 0.0F) {
			return false;
		}
		int x = Mth.clamp((int) Math.floor((u - sprite.getU0()) / du * tex.width()), 0, tex.width() - 1);
		int y = Mth.clamp((int) Math.floor((v - sprite.getV0()) / dv * tex.height()), 0, tex.height() - 1);
		try {
			return tex.isTransparent(0, x, y);
		} catch (RuntimeException e) {
			return false;
		}
	}

	// marks the voxel just behind the face so a cuboid's faces land on its own outer voxels. clear texels leave holes
	private static boolean rasterize(BakedQuad quad, boolean[] wall, @Nullable TextureAtlasSprite sprite) {
		Vector3fc a = quad.position0();
		Vector3fc b = quad.position1();
		Vector3fc c = quad.position2();
		Vector3fc d = quad.position3();
		float[] us = {UVPair.unpackU(quad.packedUV0()), UVPair.unpackU(quad.packedUV1()), UVPair.unpackU(quad.packedUV2()), UVPair.unpackU(quad.packedUV3())};
		float[] vs = {UVPair.unpackV(quad.packedUV0()), UVPair.unpackV(quad.packedUV1()), UVPair.unpackV(quad.packedUV2()), UVPair.unpackV(quad.packedUV3())};
		Vector3fc n = quad.direction().getUnitVec3f();
		float len = Math.max(Math.max(a.distance(b), b.distance(c)), Math.max(c.distance(d), d.distance(a)));
		int steps = (int) Math.ceil(len * G * 2.0F) + 1;
		float in = 0.25F / G;
		boolean any = false;
		for (int i = 0; i < steps; i++) {
			float u = (i + 0.5F) / steps;
			for (int j = 0; j < steps; j++) {
				float v = (j + 0.5F) / steps;
				float w0 = (1.0F - u) * (1.0F - v);
				float w1 = u * (1.0F - v);
				float w2 = u * v;
				float w3 = (1.0F - u) * v;
				if (sprite != null && clear(sprite, w0 * us[0] + w1 * us[1] + w2 * us[2] + w3 * us[3], w0 * vs[0] + w1 * vs[1] + w2 * vs[2] + w3 * vs[3])) {
					continue;
				}
				int x = (int) Math.floor((w0 * a.x() + w1 * b.x() + w2 * c.x() + w3 * d.x() - n.x() * in) * G);
				int y = (int) Math.floor((w0 * a.y() + w1 * b.y() + w2 * c.y() + w3 * d.y() - n.y() * in) * G);
				int z = (int) Math.floor((w0 * a.z() + w1 * b.z() + w2 * c.z() + w3 * d.z() - n.z() * in) * G);
				if (x >= 0 && y >= 0 && z >= 0 && x < G && y < G && z < G) {
					wall[index(x, y, z)] = true;
					any = true;
				}
			}
		}
		return any;
	}

	// flood fill from outside, whatever it can't reach is inside
	private static boolean[] inside(boolean[] wall) {
		boolean[] out = new boolean[G * G * G];
		int[] queue = new int[G * G * G];
		int tail = 0;
		for (int z = 0; z < G; z++) {
			for (int y = 0; y < G; y++) {
				for (int x = 0; x < G; x++) {
					boolean edge = x == 0 || y == 0 || z == 0 || x == G - 1 || y == G - 1 || z == G - 1;
					int i = index(x, y, z);
					if (edge && !wall[i]) {
						out[i] = true;
						queue[tail++] = i;
					}
				}
			}
		}
		for (int head = 0; head < tail; head++) {
			int i = queue[head];
			int x = i % G;
			int y = (i / G) % G;
			int z = i / (G * G);
			for (int k = 0; k < 6; k++) {
				int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
				int ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
				int nz = z + (k == 4 ? 1 : k == 5 ? -1 : 0);
				if (nx < 0 || ny < 0 || nz < 0 || nx >= G || ny >= G || nz >= G) {
					continue;
				}
				int j = index(nx, ny, nz);
				if (!out[j] && !wall[j]) {
					out[j] = true;
					queue[tail++] = j;
				}
			}
		}
		for (int i = 0; i < out.length; i++) {
			out[i] = !out[i];
		}
		return out;
	}
}
