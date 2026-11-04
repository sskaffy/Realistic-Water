package me.skaffy.client.realistic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.realistic.BlockMaterials;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

public final class ModelShapes {
	private static final int N = 16;
	private static final int MAX_BOXES = 32;

	private ModelShapes() {
	}

	public static void install() {
		BlockMaterials.setModelShapes(ModelShapes::boxes);
	}

	static double @Nullable [][] boxes(BlockState state) {
		if (state.getRenderShape() != RenderShape.MODEL) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		BlockStateModel model;
		try {
			model = mc.getModelManager().getBlockStateModelSet().get(state);
		} catch (RuntimeException e) {
			return null;
		}
		if (model == null) {
			return null;
		}
		List<BlockStateModelPart> parts = new ArrayList<>();
		model.collectParts(RandomSource.create(42L), parts);
		boolean[] shell = new boolean[N * N * N];
		boolean any = false;
		for (BlockStateModelPart part : parts) {
			for (int d = -1; d < 6; d++) {
				for (BakedQuad quad : part.getQuads(d < 0 ? null : Direction.from3DDataValue(d))) {
					any |= trace(quad, shell);
				}
			}
		}
		if (!any) {
			return null;
		}
		boolean[] solid = fill(shell);
		double[][] boxes = merge(solid, N);
		if (boxes != null && boxes.length > MAX_BOXES) {
			boxes = merge(halve(solid), N / 2);
		}
		return boxes;
	}

	private static boolean trace(BakedQuad quad, boolean[] shell) {
		TextureAtlasSprite sprite = quad.materialInfo().sprite();
		SpriteContents contents = sprite.contents();
		int w = contents.width();
		int h = contents.height();
		float su = sprite.getU1() - sprite.getU0();
		float sv = sprite.getV1() - sprite.getV0();
		if (su == 0.0F || sv == 0.0F) {
			return false;
		}
		Vector3fc p0 = quad.position0();
		Vector3fc p1 = quad.position1();
		Vector3fc p3 = quad.position3();
		float ax = p1.x() - p0.x();
		float ay = p1.y() - p0.y();
		float az = p1.z() - p0.z();
		float bx = p3.x() - p0.x();
		float by = p3.y() - p0.y();
		float bz = p3.z() - p0.z();
		float nx = ay * bz - az * by;
		float ny = az * bx - ax * bz;
		float nz = ax * by - ay * bx;
		float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (nl < 1.0e-6F) {
			return false;
		}
		nx /= nl;
		ny /= nl;
		nz /= nl;
		var facing = quad.direction().getUnitVec3i();
		if (nx * facing.getX() + ny * facing.getY() + nz * facing.getZ() < 0.0F) {
			nx = -nx;
			ny = -ny;
			nz = -nz;
		}
		float inset = 0.5F / N;
		float u0 = UVPair.unpackU(quad.packedUV0());
		float v0 = UVPair.unpackV(quad.packedUV0());
		float u1 = UVPair.unpackU(quad.packedUV1());
		float v1 = UVPair.unpackV(quad.packedUV1());
		float u3 = UVPair.unpackU(quad.packedUV3());
		float v3 = UVPair.unpackV(quad.packedUV3());
		float la = (float) Math.sqrt(ax * ax + ay * ay + az * az);
		float lb = (float) Math.sqrt(bx * bx + by * by + bz * bz);
		int sa = Math.max(1, (int) Math.ceil(la * N * 3));
		int sb = Math.max(1, (int) Math.ceil(lb * N * 3));
		boolean marked = false;
		for (int j = 0; j < sb; j++) {
			float t = (j + 0.5F) / sb;
			for (int i = 0; i < sa; i++) {
				float s = (i + 0.5F) / sa;
				float u = u0 + (u1 - u0) * s + (u3 - u0) * t;
				float v = v0 + (v1 - v0) * s + (v3 - v0) * t;
				int tx = Math.clamp((int) Math.floor((u - sprite.getU0()) / su * w), 0, w - 1);
				int ty = Math.clamp((int) Math.floor((v - sprite.getV0()) / sv * h), 0, h - 1);
				if (contents.isTransparent(0, tx, ty)) {
					continue;
				}
				float x = p0.x() + ax * s + bx * t - nx * inset;
				float y = p0.y() + ay * s + by * t - ny * inset;
				float z = p0.z() + az * s + bz * t - nz * inset;
				int vx = (int) Math.floor(x * N);
				int vy = (int) Math.floor(y * N);
				int vz = (int) Math.floor(z * N);
				if (vx < 0 || vy < 0 || vz < 0 || vx >= N || vy >= N || vz >= N) {
					continue;
				}
				shell[vx + N * (vy + N * vz)] = true;
				marked = true;
			}
		}
		return marked;
	}

	private static boolean[] fill(boolean[] shell) {
		int m = N + 2;
		boolean[] outside = new boolean[m * m * m];
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		outside[0] = true;
		queue.add(new int[]{0, 0, 0});
		int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		while (!queue.isEmpty()) {
			int[] c = queue.poll();
			for (int[] s : steps) {
				int x = c[0] + s[0];
				int y = c[1] + s[1];
				int z = c[2] + s[2];
				if (x < 0 || y < 0 || z < 0 || x >= m || y >= m || z >= m) {
					continue;
				}
				int at = x + m * (y + m * z);
				if (outside[at]) {
					continue;
				}
				boolean inGrid = x >= 1 && y >= 1 && z >= 1 && x <= N && y <= N && z <= N;
				if (inGrid && shell[(x - 1) + N * ((y - 1) + N * (z - 1))]) {
					continue;
				}
				outside[at] = true;
				queue.add(new int[]{x, y, z});
			}
		}
		boolean[] solid = new boolean[N * N * N];
		for (int z = 0; z < N; z++) {
			for (int y = 0; y < N; y++) {
				for (int x = 0; x < N; x++) {
					solid[x + N * (y + N * z)] = !outside[(x + 1) + m * ((y + 1) + m * (z + 1))];
				}
			}
		}
		return solid;
	}

	private static boolean[] halve(boolean[] solid) {
		int h = N / 2;
		boolean[] out = new boolean[h * h * h];
		for (int z = 0; z < N; z++) {
			for (int y = 0; y < N; y++) {
				for (int x = 0; x < N; x++) {
					if (solid[x + N * (y + N * z)]) {
						out[x / 2 + h * (y / 2 + h * (z / 2))] = true;
					}
				}
			}
		}
		return out;
	}

	private static double @Nullable [][] merge(boolean[] solid, int n) {
		boolean[] open = solid.clone();
		List<double[]> out = new ArrayList<>();
		for (int z = 0; z < n; z++) {
			for (int y = 0; y < n; y++) {
				for (int x = 0; x < n; x++) {
					if (!open[x + n * (y + n * z)]) {
						continue;
					}
					int x1 = x + 1;
					while (x1 < n && open[x1 + n * (y + n * z)]) {
						x1++;
					}
					int y1 = y + 1;
					while (y1 < n && run(open, n, x, x1, y1, y1 + 1, z, z + 1)) {
						y1++;
					}
					int z1 = z + 1;
					while (z1 < n && run(open, n, x, x1, y, y1, z1, z1 + 1)) {
						z1++;
					}
					for (int k = z; k < z1; k++) {
						for (int j = y; j < y1; j++) {
							for (int i = x; i < x1; i++) {
								open[i + n * (j + n * k)] = false;
							}
						}
					}
					out.add(new double[]{(double) x / n, (double) y / n, (double) z / n, (double) x1 / n, (double) y1 / n, (double) z1 / n});
				}
			}
		}
		return out.isEmpty() ? null : out.toArray(new double[0][]);
	}

	private static boolean run(boolean[] open, int n, int x0, int x1, int y0, int y1, int z0, int z1) {
		for (int k = z0; k < z1; k++) {
			for (int j = y0; j < y1; j++) {
				for (int i = x0; i < x1; i++) {
					if (!open[i + n * (j + n * k)]) {
						return false;
					}
				}
			}
		}
		return true;
	}
}
