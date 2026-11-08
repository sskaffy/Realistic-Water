package me.skaffy.client.trees;

import me.skaffy.client.water.WaterSettings;
import net.minecraft.util.Mth;

public final class TreeLeafGen {
	static final float OVERLAP = 0.14F;

	public static final class Leaf {
		public float cx, cy, cz;
		public float nx, ny, nz;
		public float ax, ay, az;
		public float bx, by, bz;
		public float size;
		public float ao;
		public int seed;
	}

	private TreeLeafGen() {
	}

	static int hash(int x) {
		x ^= x >>> 16;
		x *= 0x7feb352d;
		x ^= x >>> 15;
		x *= 0x846ca68b;
		x ^= x >>> 16;
		return x;
	}

	static float rand(int[] s) {
		s[0] = hash(s[0] + 0x9e3779b9);
		return (s[0] >>> 8) * (1.0F / 16777216.0F);
	}

	static int seed(int x, int y, int z, int salt) {
		return hash(x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ (salt + 1) * (int) 2654435761L);
	}

	private static float cornerHash(int x, int y, int z) {
		return Integer.toUnsignedLong(hash(x * 73856093 ^ y * 19349663 ^ z * 83492791)) * (1.0F / 4294967295.0F);
	}

	static float noise(float px, float py, float pz) {
		int bx = Mth.floor(px);
		int by = Mth.floor(py);
		int bz = Mth.floor(pz);
		float fx = px - bx;
		float fy = py - by;
		float fz = pz - bz;
		fx = fx * fx * (3.0F - 2.0F * fx);
		fy = fy * fy * (3.0F - 2.0F * fy);
		fz = fz * fz * (3.0F - 2.0F * fz);
		float[] v = new float[8];
		for (int i = 0; i < 8; i++) {
			v[i] = cornerHash(bx + (i & 1), by + ((i >> 1) & 1), bz + (i >> 2));
		}
		float a = Mth.lerp(fy, Mth.lerp(fx, v[0], v[1]), Mth.lerp(fx, v[2], v[3]));
		float b = Mth.lerp(fy, Mth.lerp(fx, v[4], v[5]), Mth.lerp(fx, v[6], v[7]));
		return Mth.lerp(fz, a, b);
	}

	public static int perBlock() {
		int perBlock = Mth.clamp(WaterSettings.treeLeavesPerBlock, 1, 1024);
		if (WaterSettings.treeVanillaTextures) {
			perBlock = Math.max(1, Math.round(perBlock * 0.125F));
		}
		return perBlock;
	}

	static float radius() {
		return Math.max(8, WaterSettings.treeLeafRadius);
	}

	static float detailRange() {
		return Math.min(Math.max(WaterSettings.treeLeafDetailRange, 0.0F), radius());
	}

	static float farDensity() {
		return Mth.clamp(WaterSettings.treeLeafFarDensity, 0.02F, 1.0F);
	}

	static float sizeScale() {
		return Math.max(WaterSettings.treeLeafSize, 0.05F);
	}

	private static float smoothstep(float e0, float e1, float x) {
		float t = Mth.clamp((x - e0) / (e1 - e0), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	public static int count(float dist) {
		int full = Math.max(perBlock(), 1);
		float f = Mth.lerp(smoothstep(detailRange(), Math.max(radius(), detailRange() + 1.0F), dist), 1.0F, farDensity());
		return Mth.clamp(Math.round(full * f), Math.min(full, 8), full);
	}

	static float grow(int count) {
		return Math.min((float) Math.sqrt((float) Math.max(perBlock(), 1) / Math.max(count, 1)), 2.2F);
	}

	public static Leaf leaf(int x, int y, int z, int open, int leafy, int i, int count, Leaf out) {
		int seed = seed(x, y, z, 1000 + i);
		out.seed = seed;
		int[] s = {seed};
		float rx = rand(s);
		float ry = rand(s);
		float rz = rand(s);
		float[] lo = new float[3];
		float[] hi = new float[3];
		for (int a = 0; a < 3; a++) {
			lo[a] = (leafy & 1 << a * 2) != 0 ? -OVERLAP : 0.03F;
			hi[a] = (leafy & 1 << a * 2 + 1) != 0 ? 1.0F + OVERLAP : 0.97F;
		}
		float[] r = {rx, ry, rz};
		float[] p = new float[3];
		for (int a = 0; a < 3; a++) {
			p[a] = lo[a] + (hi[a] - lo[a]) * r[a];
		}
		float[] world = {x + p[0], y + p[1], z + p[2]};
		int[] block = {x, y, z};
		float[] outward = new float[3];
		for (int a = 0; a < 3; a++) {
			for (int side = 0; side < 2; side++) {
				int f = a * 2 + side;
				if ((open & 1 << f) == 0) {
					continue;
				}
				float[] plane = world.clone();
				plane[a] = block[a] + side;
				float n = noise(plane[0] * 1.6F + f * 17.0F, plane[1] * 1.6F + f * 17.0F, plane[2] * 1.6F + f * 17.0F) * 0.7F
					+ noise(plane[0] * 4.1F + 5.0F, plane[1] * 4.1F + 5.0F, plane[2] * 4.1F + 5.0F) * 0.3F;
				float inset = 0.02F + 0.36F * n;
				if (side == 0) {
					lo[a] = inset;
				} else {
					hi[a] = 1.0F - inset;
				}
				outward[a] += side == 0 ? -1.0F : 1.0F;
			}
		}
		for (int a = 0; a < 3; a++) {
			p[a] = lo[a] + (hi[a] - lo[a]) * r[a];
		}
		float edge = 9.0F;
		for (int a = 0; a < 3; a++) {
			if ((open & 1 << a * 2) != 0) {
				edge = Math.min(edge, p[a]);
			}
			if ((open & 1 << a * 2 + 1) != 0) {
				edge = Math.min(edge, 1.0F - p[a]);
			}
		}
		out.ao = open == 0 ? 0.55F : Mth.lerp((float) Math.exp(-Math.max(edge, 0.0F) * 2.6F), 0.5F, 1.0F);
		out.cx = p[0];
		out.cy = p[1];
		out.cz = p[2];
		float ol = (float) Math.sqrt(outward[0] * outward[0] + outward[1] * outward[1] + outward[2] * outward[2]);
		float ox = ol > 0.0F ? outward[0] / ol : 0.0F;
		float oy = ol > 0.0F ? outward[1] / ol : 0.0F;
		float oz = ol > 0.0F ? outward[2] / ol : 0.0F;
		float uz = rand(s) * 2.0F - 1.0F;
		float ua = rand(s) * 6.2831853F;
		float ur = (float) Math.sqrt(Math.max(1.0F - uz * uz, 0.0F));
		float vx = ur * (float) Math.cos(ua) + ox * 0.5F;
		float vy = uz + 0.7F + oy * 0.5F + 1e-4F;
		float vz = ur * (float) Math.sin(ua) + oz * 0.5F;
		float vl = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
		out.nx = vx / vl;
		out.ny = vy / vl;
		out.nz = vz / vl;
		float refX = Math.abs(out.ny) < 0.95F ? 0.0F : 1.0F;
		float refY = Math.abs(out.ny) < 0.95F ? 1.0F : 0.0F;
		float t0x = out.ny * 0.0F - out.nz * refY;
		float t0y = out.nz * refX - out.nx * 0.0F;
		float t0z = out.nx * refY - out.ny * refX;
		float tl = (float) Math.sqrt(t0x * t0x + t0y * t0y + t0z * t0z);
		t0x /= tl;
		t0y /= tl;
		t0z /= tl;
		float t1x = out.ny * t0z - out.nz * t0y;
		float t1y = out.nz * t0x - out.nx * t0z;
		float t1z = out.nx * t0y - out.ny * t0x;
		float spin = rand(s) * 6.2831853F;
		float c = (float) Math.cos(spin);
		float sn = (float) Math.sin(spin);
		out.ax = t0x * c + t1x * sn;
		out.ay = t0y * c + t1y * sn;
		out.az = t0z * c + t1z * sn;
		out.bx = out.ny * out.az - out.nz * out.ay;
		out.by = out.nz * out.ax - out.nx * out.az;
		out.bz = out.nx * out.ay - out.ny * out.ax;
		float base = WaterSettings.treeVanillaTextures ? 0.3125F : 0.21F;
		out.size = base * Mth.lerp(rand(s), 0.8F, 1.2F) * sizeScale() * grow(count);
		return out;
	}

	public static final class Stick {
		public float ax, ay, az, bx, by, bz, wx, wy, wz, ra, rb;

		public float[] at(float t) {
			float bow = (float) Math.sin(Math.PI * t);
			return new float[]{Mth.lerp(t, this.ax, this.bx) + this.wx * bow, Mth.lerp(t, this.ay, this.by) + this.wy * bow,
				Mth.lerp(t, this.az, this.bz) + this.wz * bow};
		}
	}

	private static float[] rand3(int[] s) {
		return new float[]{rand(s), rand(s), rand(s)};
	}

	private static float[] randUnit(int[] s) {
		float z = rand(s) * 2.0F - 1.0F;
		float a = rand(s) * 6.2831853F;
		float r = (float) Math.sqrt(Math.max(1.0F - z * z, 0.0F));
		return new float[]{r * (float) Math.cos(a), z, r * (float) Math.sin(a)};
	}

	static float[] node(int x, int y, int z) {
		int[] s = {seed(x, y, z, 7)};
		float[] r = rand3(s);
		return new float[]{0.5F + (r[0] - 0.5F) * 0.5F, 0.5F + (r[1] - 0.5F) * 0.5F, 0.5F + (r[2] - 0.5F) * 0.5F};
	}

	static int[] neighbour(int n) {
		int l = n < 13 ? n : n + 1;
		return new int[]{l % 3 - 1, (l / 3) % 3 - 1, l / 9 - 1};
	}

	static float stickRadius(float carried) {
		return Math.min(0.017F + 0.011F * (float) Math.sqrt(Math.max(carried, 1.0F)), 0.13F);
	}

	public static int stickCount(int flags) {
		return ((flags >> 12 & 31) != 31 ? 1 : 0) + Mth.clamp(WaterSettings.treeSideSticks, 0, 6);
	}

	public static Stick stick(int x, int y, int z, int flags, int carried, int k) {
		Stick st = new Stick();
		int parent = flags >> 12 & 31;
		boolean hasMain = parent != 31;
		boolean parentLog = (flags & 1 << 17) != 0;
		float[] node = node(x, y, z);
		float[] mainEnd = node.clone();
		if (hasMain) {
			int[] o = neighbour(parent);
			if (parentLog) {
				float[] q = new float[3];
				int[] s = {seed(x, y, z, 11)};
				for (int a = 0; a < 3; a++) {
					q[a] = Mth.clamp(node[a], o[a], o[a] + 1.0F);
				}
				for (int a = 0; a < 3; a++) {
					if (q[a] > o[a] + 1e-3F && q[a] < o[a] + 1.0F - 1e-3F) {
						q[a] = Mth.clamp(q[a] + (rand(s) - 0.5F) * 0.7F, o[a] + 0.15F, o[a] + 0.85F);
					}
				}
				float cx = o[0] + 0.5F - q[0] + 1e-4F;
				float cy = o[1] + 0.5F - q[1] + 1e-4F;
				float cz = o[2] + 0.5F - q[2] + 1e-4F;
				float cl = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
				mainEnd = new float[]{q[0] + cx / cl * 0.08F, q[1] + cy / cl * 0.08F, q[2] + cz / cl * 0.08F};
			} else {
				float[] n = node(x + o[0], y + o[1], z + o[2]);
				mainEnd = new float[]{o[0] + n[0], o[1] + n[1], o[2] + n[2]};
			}
		}
		boolean vanilla = WaterSettings.treeVanillaTextures;
		if (k == 0 && hasMain) {
			st.ax = mainEnd[0];
			st.ay = mainEnd[1];
			st.az = mainEnd[2];
			st.bx = node[0];
			st.by = node[1];
			st.bz = node[2];
			st.ra = vanilla ? 0.0625F : stickRadius(carried + 1.0F);
			st.rb = vanilla ? 0.0625F : stickRadius(carried);
			int[] s = {seed(x, y, z, 13)};
			float dx = st.bx - st.ax;
			float dy = st.by - st.ay;
			float dz = st.bz - st.az;
			float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			float[] u = randUnit(s);
			st.wx = u[0] * 0.07F * len;
			st.wy = u[1] * 0.07F * len;
			st.wz = u[2] * 0.07F * len;
			if (vanilla) {
				float il = 1.0F / Math.max(len, 1e-5F);
				st.ax += dx * il * 0.035F;
				st.ay += dy * il * 0.035F;
				st.az += dz * il * 0.035F;
				st.bx -= dx * il * 0.035F;
				st.by -= dy * il * 0.035F;
				st.bz -= dz * il * 0.035F;
				st.wx = st.wy = st.wz = 0.0F;
			}
			return st;
		}
		int side = hasMain ? k - 1 : k;
		int[] s = {seed(x, y, z, 100 + side)};
		float[] start;
		float r0;
		if (hasMain) {
			float t = Mth.lerp(rand(s), 0.15F, 0.85F);
			start = new float[]{Mth.lerp(t, mainEnd[0], node[0]), Mth.lerp(t, mainEnd[1], node[1]), Mth.lerp(t, mainEnd[2], node[2])};
			r0 = Mth.lerp(t, stickRadius(carried + 1.0F), stickRadius(carried)) * 0.5F;
		} else {
			float[] r = rand3(s);
			start = new float[]{0.25F + r[0] * 0.5F, 0.25F + r[1] * 0.5F, 0.25F + r[2] * 0.5F};
			r0 = 0.02F;
			rand(s);
		}
		float ox = 0.0F;
		float oy = 0.0F;
		float oz = 0.0F;
		int open = flags & 63;
		for (int f = 0; f < 6; f++) {
			if ((open & 1 << f) != 0) {
				float v = (f & 1) == 0 ? -1.0F : 1.0F;
				if (f >> 1 == 0) {
					ox += v;
				} else if (f >> 1 == 1) {
					oy += v;
				} else {
					oz += v;
				}
			}
		}
		float ol = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
		float[] u = randUnit(s);
		float dx = u[0] + (ol > 0.0F ? ox / ol * 0.6F : 0.0F);
		float dy = u[1] + 0.35F + (ol > 0.0F ? oy / ol * 0.6F : 0.0F);
		float dz = u[2] + (ol > 0.0F ? oz / ol * 0.6F : 0.0F);
		float dl = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		float len = Mth.lerp(rand(s), 0.22F, 0.48F);
		st.ax = start[0];
		st.ay = start[1];
		st.az = start[2];
		st.bx = start[0] + dx / dl * len;
		st.by = start[1] + dy / dl * len;
		st.bz = start[2] + dz / dl * len;
		st.ra = vanilla ? 0.03125F : Math.max(r0, 0.012F);
		st.rb = vanilla ? 0.03125F : Math.max(r0 * 0.4F, 0.006F);
		if (!vanilla) {
			float[] w = randUnit(s);
			st.wx = w[0] * 0.05F * len;
			st.wy = w[1] * 0.05F * len;
			st.wz = w[2] * 0.05F * len;
		}
		return st;
	}
}
