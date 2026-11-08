package me.skaffy.client.jelly;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

final class JellyMesher {
	static final int FLOATS = 12;
	private static final float BEVEL = 0.075F;
	private static final int L = JellyField.LATTICE;
	private static final float[][][][][] GRID = new float[2][2][2][2][];

	static {
		for (int lo = 0; lo < 2; lo++) {
			for (int hi = 0; hi < 2; hi++) {
				for (int awake = 0; awake < 2; awake++) {
					for (int near = 0; near < 2; near++) {
						float[] g = new float[12];
						int n = 0;
						g[n++] = 0.0F;
						if (lo == 1) {
							if (near == 1) {
								g[n++] = BEVEL * 0.3F;
								g[n++] = BEVEL * 0.62F;
							}
							g[n++] = BEVEL;
						}
						if (awake == 1) {
							for (int k = 1; k < L; k++) {
								g[n++] = k / (float) L;
							}
						}
						if (hi == 1) {
							g[n++] = 1.0F - BEVEL;
							if (near == 1) {
								g[n++] = 1.0F - BEVEL * 0.62F;
								g[n++] = 1.0F - BEVEL * 0.3F;
							}
						}
						g[n++] = 1.0F;
						GRID[lo][hi][awake][near] = Arrays.copyOf(g, n);
					}
				}
			}
		}
	}

	float[] vertices = new float[FLOATS * 4096];
	int[] indices = new int[8192];
	int vertexCount;
	int indexCount;
	private final float[] disp = new float[(L + 1) * (L + 1) * (L + 1) * 3];
	private final float[] lo = new float[3];
	private final float[] hi = new float[3];
	private final float[] q = new float[3];
	private final float[] rest = new float[3];
	private final float[] normal = new float[3];
	private final float[] sampled = new float[3];
	private final float[] slope = new float[3];

	void reset() {
		this.vertexCount = 0;
		this.indexCount = 0;
	}

	void block(BlockPos pos, JellyKind kind, int exposed, boolean awake, boolean near, float ox, float oy, float oz, int light, float sag,
		@Nullable float[] moved) {
		if (exposed == 0) {
			return;
		}
		if (awake) {
			JellyField.sample(pos, moved != null ? 1.0F : this.partial, this.disp);
		}
		boolean[][] open = {
			{(exposed & 16) != 0, (exposed & 32) != 0},
			{(exposed & 1) != 0, (exposed & 2) != 0},
			{(exposed & 4) != 0, (exposed & 8) != 0}
		};
		for (int a = 0; a < 3; a++) {
			this.lo[a] = open[a][0] ? BEVEL : 0.0F;
			this.hi[a] = open[a][1] ? 1.0F - BEVEL : 1.0F;
		}
		for (int axis = 0; axis < 3; axis++) {
			for (int side = 0; side < 2; side++) {
				if (open[axis][side]) {
					this.face(axis, side == 1, open, awake, near, ox, oy, oz, pos, kind, light, sag);
				}
			}
		}
	}

	private float partial;

	void partial(float partial) {
		this.partial = partial;
	}

	private void face(int k, boolean positive, boolean[][] open, boolean awake, boolean near, float ox, float oy, float oz, BlockPos pos,
		JellyKind kind, int light, float sag) {
		int a = (k + 1) % 3;
		int b = (k + 2) % 3;
		float[] us = GRID[open[a][0] ? 1 : 0][open[a][1] ? 1 : 0][awake ? 1 : 0][near ? 1 : 0];
		float[] vs = GRID[open[b][0] ? 1 : 0][open[b][1] ? 1 : 0][awake ? 1 : 0][near ? 1 : 0];
		int nu = us.length;
		int nv = vs.length;
		this.reserve(nu * nv, (nu - 1) * (nv - 1) * 6);
		int first = this.vertexCount;
		for (int i = 0; i < nu; i++) {
			for (int j = 0; j < nv; j++) {
				this.q[k] = positive ? 1.0F : 0.0F;
				this.q[a] = us[i];
				this.q[b] = vs[j];
				this.roundCorner(k, positive);
				float x = this.rest[0];
				float y = this.rest[1];
				float z = this.rest[2];
				if (awake) {
					this.bend();
					x += this.sampled[0];
					y += this.sampled[1];
					z += this.sampled[2];
				}
				if (kind.inset > 0.0) {
					float in = (float) kind.inset;
					if (open[0][0] || open[0][1]) {
						x = (open[0][0] ? in : 0.0F) + x * (1.0F - (open[0][0] ? in : 0.0F) - (open[0][1] ? in : 0.0F));
					}
					if (open[2][0] || open[2][1]) {
						z = (open[2][0] ? in : 0.0F) + z * (1.0F - (open[2][0] ? in : 0.0F) - (open[2][1] ? in : 0.0F));
					}
					if (open[1][1]) {
						y *= (float) kind.top;
					}
				}
				float hang = 0.0F;
				if (sag > 0.0F) {
					float below = 1.0F - Math.clamp(this.rest[1], 0.0F, 1.0F);
					hang = sag * below * below;
					y -= hang;
				}
				int o = this.vertexCount * FLOATS;
				float[] v = this.vertices;
				v[o] = ox + x;
				v[o + 1] = oy + y;
				v[o + 2] = oz + z;
				v[o + 3] = this.normal[0];
				v[o + 4] = this.normal[1];
				v[o + 5] = this.normal[2];
				v[o + 6] = (pos.getX() & 1023) + this.rest[0];
				v[o + 7] = (pos.getY() & 1023) + this.rest[1];
				v[o + 8] = (pos.getZ() & 1023) + this.rest[2];
				v[o + 9] = kind.ordinal();
				v[o + 10] = light;
				v[o + 11] = hang;
				this.vertexCount++;
			}
		}
		int[] idx = this.indices;
		int n = this.indexCount;
		for (int i = 0; i < nu - 1; i++) {
			for (int j = 0; j < nv - 1; j++) {
				int v00 = first + i * nv + j;
				int v10 = v00 + nv;
				idx[n++] = v00;
				idx[n++] = v10;
				idx[n++] = v10 + 1;
				idx[n++] = v00;
				idx[n++] = v10 + 1;
				idx[n++] = v00 + 1;
			}
		}
		this.indexCount = n;
	}

	private void roundCorner(int k, boolean positive) {
		float dx = this.q[0] - Math.clamp(this.q[0], this.lo[0], this.hi[0]);
		float dy = this.q[1] - Math.clamp(this.q[1], this.lo[1], this.hi[1]);
		float dz = this.q[2] - Math.clamp(this.q[2], this.lo[2], this.hi[2]);
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1.0E-6F) {
			this.normal[0] = k == 0 ? (positive ? 1.0F : -1.0F) : 0.0F;
			this.normal[1] = k == 1 ? (positive ? 1.0F : -1.0F) : 0.0F;
			this.normal[2] = k == 2 ? (positive ? 1.0F : -1.0F) : 0.0F;
			System.arraycopy(this.q, 0, this.rest, 0, 3);
			return;
		}
		this.normal[0] = dx / len;
		this.normal[1] = dy / len;
		this.normal[2] = dz / len;
		float out = BEVEL / len - 1.0F;
		this.rest[0] = this.q[0] + dx * out;
		this.rest[1] = this.q[1] + dy * out;
		this.rest[2] = this.q[2] + dz * out;
	}

	private void bend() {
		float rx = this.rest[0];
		float ry = this.rest[1];
		float rz = this.rest[2];
		float nx = this.normal[0];
		float ny = this.normal[1];
		float nz = this.normal[2];
		float[] slope = this.slope;
		for (int axis = 0; axis < 3; axis++) {
			float step = this.rest[axis] + 0.25F <= 1.0F ? 0.25F : -0.25F;
			this.displace(axis == 0 ? rx + step : rx, axis == 1 ? ry + step : ry, axis == 2 ? rz + step : rz);
			slope[axis] = (nx * this.sampled[0] + ny * this.sampled[1] + nz * this.sampled[2]);
			slope[axis] = slope[axis] / step;
		}
		this.displace(rx, ry, rz);
		float along = nx * this.sampled[0] + ny * this.sampled[1] + nz * this.sampled[2];
		float gx = slope[0] - along / (this.rest[0] + 0.25F <= 1.0F ? 0.25F : -0.25F);
		float gy = slope[1] - along / (this.rest[1] + 0.25F <= 1.0F ? 0.25F : -0.25F);
		float gz = slope[2] - along / (this.rest[2] + 0.25F <= 1.0F ? 0.25F : -0.25F);
		float within = gx * nx + gy * ny + gz * nz;
		gx -= within * nx;
		gy -= within * ny;
		gz -= within * nz;
		float mx = nx - gx;
		float my = ny - gy;
		float mz = nz - gz;
		float len = (float) Math.sqrt(mx * mx + my * my + mz * mz);
		this.normal[0] = mx / len;
		this.normal[1] = my / len;
		this.normal[2] = mz / len;
	}

	private void displace(float x, float y, float z) {
		float gx = Math.clamp(x * L, 0.0F, L);
		float gy = Math.clamp(y * L, 0.0F, L);
		float gz = Math.clamp(z * L, 0.0F, L);
		int i0 = Math.min((int) gx, L - 1);
		int j0 = Math.min((int) gy, L - 1);
		int k0 = Math.min((int) gz, L - 1);
		float fx = gx - i0;
		float fy = gy - j0;
		float fz = gz - k0;
		float sx = 0.0F;
		float sy = 0.0F;
		float sz = 0.0F;
		for (int c = 0; c < 8; c++) {
			int ci = c & 1;
			int cj = (c >> 1) & 1;
			int ck = (c >> 2) & 1;
			float w = (ci == 1 ? fx : 1.0F - fx) * (cj == 1 ? fy : 1.0F - fy) * (ck == 1 ? fz : 1.0F - fz);
			if (w == 0.0F) {
				continue;
			}
			int at = (((i0 + ci) * (L + 1) + j0 + cj) * (L + 1) + k0 + ck) * 3;
			sx += this.disp[at] * w;
			sy += this.disp[at + 1] * w;
			sz += this.disp[at + 2] * w;
		}
		this.sampled[0] = sx;
		this.sampled[1] = sy;
		this.sampled[2] = sz;
	}

	private void reserve(int moreVertices, int moreIndices) {
		if ((this.vertexCount + moreVertices) * FLOATS > this.vertices.length) {
			this.vertices = Arrays.copyOf(this.vertices, Math.max(this.vertices.length * 2, (this.vertexCount + moreVertices) * FLOATS));
		}
		if (this.indexCount + moreIndices > this.indices.length) {
			this.indices = Arrays.copyOf(this.indices, Math.max(this.indices.length * 2, this.indexCount + moreIndices));
		}
	}
}
