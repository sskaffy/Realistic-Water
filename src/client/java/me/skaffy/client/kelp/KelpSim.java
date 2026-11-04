package me.skaffy.client.kelp;

import java.util.Arrays;
import java.util.List;

final class KelpSim {
	interface Env {
		boolean water(double x, double y, double z);

		boolean collide(double[] p, float radius);
	}

	record Body(double x0, double y0, double z0, double x1, double y1, double z1, double vx, double vy, double vz) {
	}

	record Params(float buoyancy, float gravity, float waterDrag, float airDrag, float sway, float stiffness, boolean selfCollision) {
	}

	static final int SUBSTEPS = 4;
	static final int ITERATIONS = 6;
	static final float RADIUS = 0.2F;
	private static final float CELL = 0.8F;
	private static final float GRAB_STEP = 0.1F;
	private static final float REACH2 = (KelpStrand.SEGMENT * 1.1F + 2.0F * RADIUS) * (KelpStrand.SEGMENT * 1.1F + 2.0F * RADIUS);
	private static final float TICK = 0.05F;

	KelpStrand grabbed;
	int grabbedNode;
	double grabX;
	double grabY;
	double grabZ;

	private final double[] p = new double[3];
	private int[] head = new int[0];
	private int[] next = new int[0];
	private int[] nodeStrand = new int[0];
	private int[] nodeIndex = new int[0];
	private long[] cellKeys = new long[0];

	void tick(List<KelpStrand> awake, List<KelpStrand> involved, Env env, List<Body> bodies, float time, Params params) {
		float dt = TICK / SUBSTEPS;
		for (KelpStrand s : awake) {
			s.snapshot();
		}
		for (int sub = 0; sub < SUBSTEPS; sub++) {
			float t = time + sub * dt;
			for (KelpStrand s : awake) {
				this.integrate(s, env, t, dt, params);
			}
			for (KelpStrand s : awake) {
				this.holdGrab(s);
			}
			if (params.selfCollision()) {
				this.hashSegments(involved);
			}
			for (int it = 0; it < ITERATIONS; it++) {
				for (KelpStrand s : awake) {
					this.constrain(s, params.stiffness());
				}
				if (params.selfCollision() && (it == 1 || it == ITERATIONS - 1)) {
					this.collideSegments(involved);
				}
			}
			for (KelpStrand s : awake) {
				this.collideBodies(s, bodies, dt);
				this.collideWorld(s, env);
			}
		}
		for (KelpStrand s : awake) {
			s.updateBounds();
		}
	}

	private void integrate(KelpStrand s, Env env, float t, float dt, Params params) {
		float seed = (float) ((s.root * 0x9E3779B97F4A7C15L) >>> 40) * 1.0e-5F;
		float dt2 = dt * dt;
		for (int i = 1; i < s.count; i++) {
			if (s.pinned[i] || s == this.grabbed && i == this.grabbedNode) {
				continue;
			}
			double wx = s.rootX + s.x[i];
			double wy = s.rootY + s.y[i];
			double wz = s.rootZ + s.z[i];
			boolean wet = env.water(wx, wy, wz);
			float keep = (float) Math.exp(-(wet ? params.waterDrag() : params.airDrag()) * dt);
			float ax = 0.0F;
			float ay;
			float az = 0.0F;
			if (wet) {
				ay = params.buoyancy() * (0.6F + 0.4F * i / s.count);
				float h = (float) i / s.count;
				float wave = t * 0.9F - s.y[i] * 0.35F + seed;
				ax = params.sway() * h * ((float) Math.sin(wave) + 0.4F * (float) Math.sin(wave * 2.3F + 1.7F));
				az = params.sway() * h * ((float) Math.cos(wave * 0.8F + 0.6F) + 0.4F * (float) Math.sin(wave * 1.9F));
			} else {
				ay = -params.gravity();
			}
			float vx = (s.x[i] - s.px[i]) * keep;
			float vy = (s.y[i] - s.py[i]) * keep;
			float vz = (s.z[i] - s.pz[i]) * keep;
			s.px[i] = s.x[i];
			s.py[i] = s.y[i];
			s.pz[i] = s.z[i];
			s.x[i] += vx + ax * dt2;
			s.y[i] += vy + ay * dt2;
			s.z[i] += vz + az * dt2;
		}
	}

	private float invMass(KelpStrand s, int i) {
		return s.pinned[i] || s == this.grabbed && i == this.grabbedNode ? 0.0F : 1.0F;
	}

	private void constrain(KelpStrand s, float stiffness) {
		for (int i = 0; i + 1 < s.count; i++) {
			this.distance(s, i, i + 1, KelpStrand.SEGMENT, 1.0F);
		}
		if (stiffness > 0.0F) {
			for (int i = 0; i + 2 < s.count; i++) {
				this.distance(s, i, i + 2, 2.0F * KelpStrand.SEGMENT, stiffness);
			}
		}
	}

	private void distance(KelpStrand s, int a, int b, float rest, float k) {
		float wa = this.invMass(s, a);
		float wb = this.invMass(s, b);
		float w = wa + wb;
		if (w <= 0.0F) {
			return;
		}
		float dx = s.x[b] - s.x[a];
		float dy = s.y[b] - s.y[a];
		float dz = s.z[b] - s.z[a];
		float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (d < 1.0e-6F) {
			return;
		}
		float c = (d - rest) / (d * w) * k;
		s.x[a] += dx * c * wa;
		s.y[a] += dy * c * wa;
		s.z[a] += dz * c * wa;
		s.x[b] -= dx * c * wb;
		s.y[b] -= dy * c * wb;
		s.z[b] -= dz * c * wb;
	}

	private void holdGrab(KelpStrand s) {
		if (s != this.grabbed) {
			return;
		}
		int i = this.grabbedNode;
		double tx = this.grabX - s.rootX - s.x[0];
		double ty = this.grabY - s.rootY - s.y[0];
		double tz = this.grabZ - s.rootZ - s.z[0];
		double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
		double max = KelpStrand.reach(i) * 1.02;
		if (len > max && len > 1.0e-6) {
			tx *= max / len;
			ty *= max / len;
			tz *= max / len;
		}
		double mx = s.x[0] + tx - s.x[i];
		double my = s.y[0] + ty - s.y[i];
		double mz = s.z[0] + tz - s.z[i];
		double step = Math.sqrt(mx * mx + my * my + mz * mz);
		if (step > GRAB_STEP) {
			mx *= GRAB_STEP / step;
			my *= GRAB_STEP / step;
			mz *= GRAB_STEP / step;
		}
		s.px[i] = s.x[i];
		s.py[i] = s.y[i];
		s.pz[i] = s.z[i];
		s.x[i] += (float) mx;
		s.y[i] += (float) my;
		s.z[i] += (float) mz;
	}

	private void collideWorld(KelpStrand s, Env env) {
		for (int i = 1; i < s.count; i++) {
			if (this.invMass(s, i) == 0.0F) {
				continue;
			}
			this.p[0] = s.rootX + s.x[i];
			this.p[1] = s.rootY + s.y[i];
			this.p[2] = s.rootZ + s.z[i];
			if (env.collide(this.p, RADIUS)) {
				s.x[i] = (float) (this.p[0] - s.rootX);
				s.y[i] = (float) (this.p[1] - s.rootY);
				s.z[i] = (float) (this.p[2] - s.rootZ);
				s.px[i] += (s.x[i] - s.px[i]) * 0.5F;
				s.py[i] += (s.y[i] - s.py[i]) * 0.5F;
				s.pz[i] += (s.z[i] - s.pz[i]) * 0.5F;
			}
		}
	}

	private void collideBodies(KelpStrand s, List<Body> bodies, float dt) {
		float r = RADIUS;
		for (Body b : bodies) {
			if (!s.overlaps(b.x0() - r, b.y0() - r, b.z0() - r, b.x1() + r, b.y1() + r, b.z1() + r)) {
				continue;
			}
			float share = dt / TICK;
			for (int i = 1; i < s.count; i++) {
				if (this.invMass(s, i) == 0.0F) {
					continue;
				}
				double wx = s.rootX + s.x[i];
				double wy = s.rootY + s.y[i];
				double wz = s.rootZ + s.z[i];
				if (wx < b.x0() - r || wx > b.x1() + r || wy < b.y0() - r || wy > b.y1() + r || wz < b.z0() - r || wz > b.z1() + r) {
					continue;
				}
				double toX0 = wx - (b.x0() - r);
				double toX1 = (b.x1() + r) - wx;
				double toZ0 = wz - (b.z0() - r);
				double toZ1 = (b.z1() + r) - wz;
				double m = Math.min(Math.min(toX0, toX1), Math.min(toZ0, toZ1));
				if (m == toX0) {
					s.x[i] -= (float) toX0;
				} else if (m == toX1) {
					s.x[i] += (float) toX1;
				} else if (m == toZ0) {
					s.z[i] -= (float) toZ0;
				} else {
					s.z[i] += (float) toZ1;
				}
				s.px[i] -= (float) (b.vx() * 0.6 * share);
				s.py[i] -= (float) (b.vy() * 0.6 * share);
				s.pz[i] -= (float) (b.vz() * 0.6 * share);
			}
		}
	}

	private void hashSegments(List<KelpStrand> strands) {
		int count = strands.size();
		this.hashed = strands;
		this.hashedCount = 0;
		if (this.near.length < count) {
			this.near = new int[count][];
		}
		float r2 = 2.0F * RADIUS;
		int total = 0;
		for (KelpStrand s : strands) {
			if (s.awake) {
				s.updateBounds();
			}
		}
		for (int k = 0; k < count; k++) {
			KelpStrand s = strands.get(k);
			int found = 0;
			int[] list = this.near[k];
			if (list == null || list.length < 8) {
				list = new int[8];
			}
			for (int m = 0; m < count; m++) {
				KelpStrand o = strands.get(m);
				if (m == k || !s.awake && !o.awake) {
					continue;
				}
				if (s.overlaps(o.minX - r2, o.minY - r2, o.minZ - r2, o.maxX + r2, o.maxY + r2, o.maxZ + r2)) {
					if (found == list.length) {
						list = Arrays.copyOf(list, found * 2);
					}
					list[found++] = m;
				}
			}
			this.near[k] = list;
			this.nearCount = this.nearCount.length < count ? Arrays.copyOf(this.nearCount, Math.max(count, 16)) : this.nearCount;
			this.nearCount[k] = found;
			total += s.count - 1;
		}
		if (this.next.length < total) {
			this.next = new int[total];
			this.nodeStrand = new int[total];
			this.nodeIndex = new int[total];
			this.cellKeys = new long[total];
		}
		int buckets = Integer.highestOneBit(Math.max(total, 16)) * 2;
		if (this.head.length != buckets) {
			this.head = new int[buckets];
		}
		Arrays.fill(this.head, -1);
		int n = 0;
		for (int k = 0; k < count; k++) {
			KelpStrand s = strands.get(k);
			boolean folded = folded(s);
			int found = this.nearCount[k];
			if (found == 0 && !folded) {
				continue;
			}
			for (int i = 0; i + 1 < s.count; i++) {
				float x0 = s.rootX + Math.min(s.x[i], s.x[i + 1]) - r2;
				float y0 = s.rootY + Math.min(s.y[i], s.y[i + 1]) - r2;
				float z0 = s.rootZ + Math.min(s.z[i], s.z[i + 1]) - r2;
				float x1 = s.rootX + Math.max(s.x[i], s.x[i + 1]) + r2;
				float y1 = s.rootY + Math.max(s.y[i], s.y[i + 1]) + r2;
				float z1 = s.rootZ + Math.max(s.z[i], s.z[i + 1]) + r2;
				boolean useful = folded;
				for (int f = 0; f < found && !useful; f++) {
					useful = strands.get(this.near[k][f]).overlaps(x0, y0, z0, x1, y1, z1);
				}
				if (!useful) {
					continue;
				}
				long key = cellKey(cell(s.rootX + (s.x[i] + s.x[i + 1]) * 0.5), cell(s.rootY + (s.y[i] + s.y[i + 1]) * 0.5),
					cell(s.rootZ + (s.z[i] + s.z[i + 1]) * 0.5));
				int bucket = bucket(key, buckets);
				this.nodeStrand[n] = k;
				this.nodeIndex[n] = i;
				this.cellKeys[n] = key;
				this.next[n] = this.head[bucket];
				this.head[bucket] = n;
				n++;
			}
		}
		this.hashedCount = n;
	}

	private static boolean folded(KelpStrand s) {
		int t = s.count - 1;
		float dx = s.x[t] - s.x[0];
		float dy = s.y[t] - s.y[0];
		float dz = s.z[t] - s.z[0];
		float len = t * KelpStrand.SEGMENT;
		return dx * dx + dy * dy + dz * dz < len * len * 0.7F;
	}

	private List<KelpStrand> hashed = List.of();
	private int hashedCount;
	private int[][] near = new int[0][];
	private int[] nearCount = new int[16];

	private void collideSegments(List<KelpStrand> strands) {
		if (this.hashedCount == 0 || strands != this.hashed) {
			return;
		}
		int buckets = this.head.length;
		float min = 2.0F * RADIUS;
		for (int n = 0; n < this.hashedCount; n++) {
			int k = this.nodeStrand[n];
			int i = this.nodeIndex[n];
			KelpStrand s = strands.get(k);
			if (!s.awake) {
				continue;
			}
			float mx = s.rootX + (s.x[i] + s.x[i + 1]) * 0.5F;
			float my = s.rootY + (s.y[i] + s.y[i + 1]) * 0.5F;
			float mz = s.rootZ + (s.z[i] + s.z[i + 1]) * 0.5F;
			long own = this.cellKeys[n];
			int cx = (int) (own << 43 >> 43);
			int cy = (int) (own << 22 >> 43);
			int cz = (int) (own << 1 >> 43);
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dx = -1; dx <= 1; dx++) {
						long key = cellKey(cx + dx, cy + dy, cz + dz);
						for (int m = this.head[bucket(key, buckets)]; m >= 0; m = this.next[m]) {
							if (this.cellKeys[m] != key) {
								continue;
							}
							KelpStrand o = strands.get(this.nodeStrand[m]);
							int j = this.nodeIndex[m];
							if (o.awake && (this.nodeStrand[m] < k || this.nodeStrand[m] == k && j <= i)) {
								continue;
							}
							if (o == s && Math.abs(i - j) <= 2) {
								continue;
							}
							float ex = o.rootX + (o.x[j] + o.x[j + 1]) * 0.5F - mx;
							float ey = o.rootY + (o.y[j] + o.y[j + 1]) * 0.5F - my;
							float ez = o.rootZ + (o.z[j] + o.z[j + 1]) * 0.5F - mz;
							if (ex * ex + ey * ey + ez * ez >= REACH2) {
								continue;
							}
							this.separate(s, i, o, j, min);
						}
					}
				}
			}
		}
	}

	private void separate(KelpStrand a, int i, KelpStrand b, int j, float min) {
		float ox = b.rootX - a.rootX;
		float oy = b.rootY - a.rootY;
		float oz = b.rootZ - a.rootZ;
		float p0x = a.x[i];
		float p0y = a.y[i];
		float p0z = a.z[i];
		float ux = a.x[i + 1] - p0x;
		float uy = a.y[i + 1] - p0y;
		float uz = a.z[i + 1] - p0z;
		float q0x = b.x[j] + ox;
		float q0y = b.y[j] + oy;
		float q0z = b.z[j] + oz;
		float vx = b.x[j + 1] + ox - q0x;
		float vy = b.y[j + 1] + oy - q0y;
		float vz = b.z[j + 1] + oz - q0z;
		float wx = p0x - q0x;
		float wy = p0y - q0y;
		float wz = p0z - q0z;
		float aa = ux * ux + uy * uy + uz * uz;
		float bb = ux * vx + uy * vy + uz * vz;
		float cc = vx * vx + vy * vy + vz * vz;
		float dd = ux * wx + uy * wy + uz * wz;
		float ee = vx * wx + vy * wy + vz * wz;
		float den = aa * cc - bb * bb;
		float sp = den > 1.0e-8F ? clamp01((bb * ee - cc * dd) / den) : 0.0F;
		float tp = cc > 1.0e-8F ? clamp01((bb * sp + ee) / cc) : 0.0F;
		sp = aa > 1.0e-8F ? clamp01((bb * tp - dd) / aa) : 0.0F;
		float nx = wx + ux * sp - vx * tp;
		float ny = wy + uy * sp - vy * tp;
		float nz = wz + uz * sp - vz * tp;
		float d2 = nx * nx + ny * ny + nz * nz;
		if (d2 >= min * min) {
			return;
		}
		float d = (float) Math.sqrt(d2);
		if (d < 1.0e-5F) {
			nx = -uz;
			ny = 0.0F;
			nz = ux;
			d = (float) Math.sqrt(nx * nx + nz * nz);
			if (d < 1.0e-5F) {
				nx = 1.0F;
				nz = 0.0F;
				d = 1.0F;
			}
			nx /= d;
			nz /= d;
			d = 0.0F;
		} else {
			nx /= d;
			ny /= d;
			nz /= d;
		}
		float wa0 = this.invMass(a, i) * (1.0F - sp);
		float wa1 = this.invMass(a, i + 1) * sp;
		float wb0 = this.invMass(b, j) * (1.0F - tp);
		float wb1 = this.invMass(b, j + 1) * tp;
		float w = wa0 * (1.0F - sp) + wa1 * sp + wb0 * (1.0F - tp) + wb1 * tp;
		if (w <= 1.0e-6F) {
			return;
		}
		b.awake = true;
		b.calmTicks = 0;
		float lambda = (min - d) / w;
		a.x[i] += nx * lambda * wa0;
		a.y[i] += ny * lambda * wa0;
		a.z[i] += nz * lambda * wa0;
		a.x[i + 1] += nx * lambda * wa1;
		a.y[i + 1] += ny * lambda * wa1;
		a.z[i + 1] += nz * lambda * wa1;
		b.x[j] -= nx * lambda * wb0;
		b.y[j] -= ny * lambda * wb0;
		b.z[j] -= nz * lambda * wb0;
		b.x[j + 1] -= nx * lambda * wb1;
		b.y[j + 1] -= ny * lambda * wb1;
		b.z[j + 1] -= nz * lambda * wb1;
	}

	private static float clamp01(float v) {
		return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
	}

	private static int cell(double v) {
		return (int) Math.floor(v / CELL);
	}

	private static long cellKey(int x, int y, int z) {
		return ((long) x & 0x1FFFFFL) | ((long) y & 0x1FFFFFL) << 21 | ((long) z & 0x1FFFFFL) << 42;
	}

	private static int bucket(long key, int buckets) {
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h >>> 40) & (buckets - 1);
	}
}
