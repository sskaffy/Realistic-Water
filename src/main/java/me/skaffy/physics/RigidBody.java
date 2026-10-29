package me.skaffy.physics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public final class RigidBody {
	public static final class Cell {
		public final int x;
		public final int y;
		public final int z;
		public final double density;
		public final boolean flammable;
		public final double friction;
		public final Object payload;
		public final double @org.jspecify.annotations.Nullable [][] boxes;
		public final double @org.jspecify.annotations.Nullable [][] envelope;
		public final double @org.jspecify.annotations.Nullable [] waterBounds;
		public final double volume;
		final Vector3d centroid = new Vector3d(0.5, 0.5, 0.5);
		final double[] eighths = {1, 1, 1, 1, 1, 1, 1, 1};
		public double burn;
		public boolean burning;

		public Cell(int x, int y, int z, double density, boolean flammable, double friction, Object payload) {
			this(x, y, z, density, flammable, friction, payload, null);
		}

		public Cell(int x, int y, int z, double density, boolean flammable, double friction, Object payload, double[][] boxes) {
			this(x, y, z, density, flammable, friction, payload, boxes, null);
		}

		public Cell(int x, int y, int z, double density, boolean flammable, double friction, Object payload, double[][] boxes, double[][] envelope) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.density = density;
			this.flammable = flammable;
			this.friction = friction;
			this.payload = payload;
			this.boxes = boxes == null || fullCube(boxes) ? null : boxes;
			this.envelope = envelope;
			double[][] wet = envelope != null ? envelope : this.boxes;
			if (wet == null || fullCube(wet)) {
				this.waterBounds = null;
			} else {
				double[] w = {1, 1, 1, 0, 0, 0};
				for (double[] b : wet) {
					for (int i = 0; i < 3; i++) {
						w[i] = Math.min(w[i], b[i]);
						w[i + 3] = Math.max(w[i + 3], b[i + 3]);
					}
				}
				this.waterBounds = w;
			}
			if (this.boxes == null) {
				this.volume = 1.0;
				if (envelope == null) {
					return;
				}
			} else {
				double v = 0.0;
				this.centroid.zero();
				for (double[] b : this.boxes) {
					double bv = (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
					v += bv;
					this.centroid.add((b[0] + b[3]) * 0.5 * bv, (b[1] + b[4]) * 0.5 * bv, (b[2] + b[5]) * 0.5 * bv);
				}
				this.volume = v;
				if (v > 0.0) {
					this.centroid.div(v);
				} else {
					this.centroid.set(0.5, 0.5, 0.5);
				}
			}
			this.coverEighths(wet == null ? new double[][]{{0, 0, 0, 1, 1, 1}} : wet);
		}

		private void coverEighths(double[][] wet) {
			for (int s = 0; s < 8; s++) {
				double ox = 0.5 * (s & 1);
				double oy = 0.5 * ((s >> 1) & 1);
				double oz = 0.5 * (s >> 2);
				double covered = 0.0;
				for (double[] b : wet) {
					covered += Math.max(0.0, Math.min(b[3], ox + 0.5) - Math.max(b[0], ox)) * Math.max(0.0, Math.min(b[4], oy + 0.5) - Math.max(b[1], oy))
						* Math.max(0.0, Math.min(b[5], oz + 0.5) - Math.max(b[2], oz));
				}
				this.eighths[s] = Math.min(covered / 0.125, 1.0);
			}
		}

		private static boolean fullCube(double[][] boxes) {
			return boxes.length == 1 && boxes[0][0] <= 0.0 && boxes[0][1] <= 0.0 && boxes[0][2] <= 0.0 && boxes[0][3] >= 1.0 && boxes[0][4] >= 1.0
				&& boxes[0][5] >= 1.0;
		}

		public boolean full() {
			return this.boxes == null;
		}

		public boolean contains(double fx, double fy, double fz) {
			if (this.boxes == null) {
				return fx >= 0.0 && fx <= 1.0 && fy >= 0.0 && fy <= 1.0 && fz >= 0.0 && fz <= 1.0;
			}
			for (double[] b : this.boxes) {
				if (fx >= b[0] && fx <= b[3] && fy >= b[1] && fy <= b[4] && fz >= b[2] && fz <= b[5]) {
					return true;
				}
			}
			return false;
		}

		public Cell moved(int nx, int ny, int nz) {
			Cell c = new Cell(nx, ny, nz, this.density, this.flammable, this.friction, this.payload, this.boxes, this.envelope);
			c.burn = this.burn;
			return c;
		}
	}

	public final List<Cell> cells = new ArrayList<>();
	private final Long2ObjectOpenHashMap<Cell> byKey = new Long2ObjectOpenHashMap<>();
	public final Vector3d com = new Vector3d();
	public double mass;
	public double invMass;
	final Matrix3d invInertiaLocal = new Matrix3d();
	final Matrix3d invInertiaWorld = new Matrix3d();
	final Matrix3d rotation = new Matrix3d();
	double[] points = new double[0];
	double[] pointFriction = new double[0];
	private double[] warm = new double[0];
	private Object[] warmOther = new Object[0];
	private int[] warmStep = new int[0];
	private static final Object WORLD = new Object();
	private boolean shapeBuilt;

	public final Vector3d pos = new Vector3d();
	public final Quaterniond rot = new Quaterniond();
	public final Vector3d vel = new Vector3d();
	public final Vector3d ang = new Vector3d();
	public boolean kinematic;
	public final Vector3d flow = new Vector3d();
	public double flowAge = Double.MAX_VALUE;
	public double surfaceY;
	public double surfaceAge = Double.MAX_VALUE;
	public boolean floating() {
		return this.submerged && this.worldContacts == 0;
	}

	public boolean inFluid() {
		return this.submerged;
	}

	boolean submerged;
	int worldContacts;
	public double minX;
	public double minY;
	public double minZ;
	public double maxX;
	public double maxY;
	public double maxZ;

	public static long key(int x, int y, int z) {
		return (x & 0x1FFFFFL) | (y & 0x1FFFFFL) << 21 | (z & 0x1FFFFFL) << 42;
	}

	public Cell cellAt(int x, int y, int z) {
		return this.byKey.get(key(x, y, z));
	}

	public boolean hasCell(int x, int y, int z) {
		return this.byKey.containsKey(key(x, y, z));
	}

	public void addCell(Cell c) {
		if (this.byKey.putIfAbsent(key(c.x, c.y, c.z), c) == null) {
			this.cells.add(c);
		}
	}

	public void setCells(List<Cell> fresh) {
		this.cells.clear();
		this.byKey.clear();
		this.shapeBuilt = false;
		for (Cell c : fresh) {
			this.addCell(c);
		}
	}

	public void replaceCell(Cell c) {
		Cell old = this.byKey.put(key(c.x, c.y, c.z), c);
		if (old == null) {
			this.cells.add(c);
		} else {
			c.burn = old.burn;
			this.cells.set(this.cells.indexOf(old), c);
		}
	}

	public Cell removeCell(int x, int y, int z) {
		Cell c = this.byKey.remove(key(x, y, z));
		if (c != null) {
			this.cells.remove(c);
		}
		return c;
	}

	public void rebuild() {
		double m = 0.0;
		Vector3d c = new Vector3d();
		for (Cell cell : this.cells) {
			double w = cell.density * cell.volume;
			m += w;
			c.add((cell.x + cell.centroid.x) * w, (cell.y + cell.centroid.y) * w, (cell.z + cell.centroid.z) * w);
		}
		if (m <= 0.0) {
			this.mass = 0.0;
			this.invMass = 0.0;
			return;
		}
		c.div(m);
		if (this.shapeBuilt) {
			Vector3d shift = new Vector3d(c).sub(this.com);
			this.rot.transform(shift);
			this.pos.add(shift);
		}
		this.com.set(c);
		this.mass = m;
		this.invMass = 1.0 / m;
		Matrix3d inertia = new Matrix3d().zero();
		double[][] cube = {{0, 0, 0, 1, 1, 1}};
		for (Cell cell : this.cells) {
			for (double[] b : cell.boxes == null ? cube : cell.boxes) {
				double sx = b[3] - b[0];
				double sy = b[4] - b[1];
				double sz = b[5] - b[2];
				double w = cell.density * sx * sy * sz;
				double rx = cell.x + (b[0] + b[3]) * 0.5 - c.x;
				double ry = cell.y + (b[1] + b[4]) * 0.5 - c.y;
				double rz = cell.z + (b[2] + b[5]) * 0.5 - c.z;
				double r2 = rx * rx + ry * ry + rz * rz;
				inertia.m00 += w * (sy * sy + sz * sz) / 12.0 + w * (r2 - rx * rx);
				inertia.m11 += w * (sx * sx + sz * sz) / 12.0 + w * (r2 - ry * ry);
				inertia.m22 += w * (sx * sx + sy * sy) / 12.0 + w * (r2 - rz * rz);
				inertia.m01 -= w * rx * ry;
				inertia.m02 -= w * rx * rz;
				inertia.m12 -= w * ry * rz;
			}
		}
		inertia.m10 = inertia.m01;
		inertia.m20 = inertia.m02;
		inertia.m21 = inertia.m12;
		inertia.invert(this.invInertiaLocal);
		this.buildPoints();
		this.shapeBuilt = true;
		this.updateDerived();
	}

	private static final int SAMPLES = 4;

	private void buildPoints() {
		int n = SAMPLES;
		LongOpenHashSet seen = new LongOpenHashSet();
		List<double[]> out = new ArrayList<>();
		for (Cell cell : this.cells) {
			if (!cell.full()) {
				this.shapePoints(cell, out);
				continue;
			}
			for (int i = 0; i <= n; i++) {
				for (int j = 0; j <= n; j++) {
					for (int k = 0; k <= n; k++) {
						if (i != 0 && i != n && j != 0 && j != n && k != 0 && k != n) {
							continue;
						}
						int u = cell.x * n + i;
						int v = cell.y * n + j;
						int w = cell.z * n + k;
						if (seen.add(key(u, v, w)) && !this.buried(u, v, w, n)) {
							out.add(new double[]{(double) u / n, (double) v / n, (double) w / n, cell.friction});
						}
					}
				}
			}
		}
		this.points = new double[out.size() * 3];
		this.pointFriction = new double[out.size()];
		for (int i = 0; i < out.size(); i++) {
			double[] p = out.get(i);
			this.points[i * 3] = p[0];
			this.points[i * 3 + 1] = p[1];
			this.points[i * 3 + 2] = p[2];
			this.pointFriction[i] = p[3];
		}
		this.warm = new double[out.size() * 7];
		this.warmOther = new Object[out.size()];
		this.warmStep = new int[out.size()];
	}

	void remember(int point, int step, RigidBody other, Vector3d n, double jn, Vector3d jt) {
		if (point >= this.warmStep.length) {
			return;
		}
		int o = point * 7;
		this.warm[o] = jn;
		this.warm[o + 1] = jt.x;
		this.warm[o + 2] = jt.y;
		this.warm[o + 3] = jt.z;
		this.warm[o + 4] = n.x;
		this.warm[o + 5] = n.y;
		this.warm[o + 6] = n.z;
		this.warmOther[point] = other == null ? WORLD : other;
		this.warmStep[point] = step;
	}

	double[] recalled(int point, int step, RigidBody other, Vector3d n) {
		if (point >= this.warmStep.length || this.warmStep[point] != step || this.warmOther[point] != (other == null ? WORLD : other)) {
			return null;
		}
		int o = point * 7;
		if (this.warm[o + 4] * n.x + this.warm[o + 5] * n.y + this.warm[o + 6] * n.z < 0.95) {
			return null;
		}
		return new double[]{this.warm[o], this.warm[o + 1], this.warm[o + 2], this.warm[o + 3]};
	}

	private void shapePoints(Cell cell, List<double[]> out) {
		for (double[] b : cell.boxes) {
			int nx = Math.max(1, (int) Math.ceil((b[3] - b[0]) * SAMPLES - 1.0E-6));
			int ny = Math.max(1, (int) Math.ceil((b[4] - b[1]) * SAMPLES - 1.0E-6));
			int nz = Math.max(1, (int) Math.ceil((b[5] - b[2]) * SAMPLES - 1.0E-6));
			for (int i = 0; i <= nx; i++) {
				for (int j = 0; j <= ny; j++) {
					for (int k = 0; k <= nz; k++) {
						if (i != 0 && i != nx && j != 0 && j != ny && k != 0 && k != nz) {
							continue;
						}
						double gx = cell.x + b[0] + (b[3] - b[0]) * i / nx;
						double gy = cell.y + b[1] + (b[4] - b[1]) * j / ny;
						double gz = cell.z + b[2] + (b[5] - b[2]) * k / nz;
						if (!this.insideFullNeighbour(cell, gx, gy, gz)) {
							out.add(new double[]{gx, gy, gz, cell.friction});
						}
					}
				}
			}
		}
	}

	private boolean insideFullNeighbour(Cell cell, double gx, double gy, double gz) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					Cell o = (dx | dy | dz) == 0 ? null : this.cellAt(cell.x + dx, cell.y + dy, cell.z + dz);
					if (o != null && o.full() && gx > o.x - 1.0E-6 && gx < o.x + 1 + 1.0E-6 && gy > o.y - 1.0E-6 && gy < o.y + 1 + 1.0E-6 && gz > o.z - 1.0E-6
						&& gz < o.z + 1 + 1.0E-6) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private boolean buried(int u, int v, int w, int n) {
		int x1 = Math.floorDiv(u, n);
		int y1 = Math.floorDiv(v, n);
		int z1 = Math.floorDiv(w, n);
		int x0 = Math.floorMod(u, n) == 0 ? x1 - 1 : x1;
		int y0 = Math.floorMod(v, n) == 0 ? y1 - 1 : y1;
		int z0 = Math.floorMod(w, n) == 0 ? z1 - 1 : z1;
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					Cell c = this.cellAt(x, y, z);
					if (c == null || !c.full()) {
						return false;
					}
				}
			}
		}
		return true;
	}

	public void updateDerived() {
		this.rot.normalize();
		this.rot.get(this.rotation);
		Matrix3d rt = new Matrix3d(this.rotation).transpose();
		this.invInertiaWorld.set(this.rotation).mul(this.invInertiaLocal).mul(rt);
		double x0 = Double.POSITIVE_INFINITY;
		double y0 = Double.POSITIVE_INFINITY;
		double z0 = Double.POSITIVE_INFINITY;
		double x1 = Double.NEGATIVE_INFINITY;
		double y1 = Double.NEGATIVE_INFINITY;
		double z1 = Double.NEGATIVE_INFINITY;
		Vector3d p = new Vector3d();
		for (Cell cell : this.cells) {
			this.toWorld(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, p);
			double ex = 0.5 * (Math.abs(this.rotation.m00) + Math.abs(this.rotation.m10) + Math.abs(this.rotation.m20));
			double ey = 0.5 * (Math.abs(this.rotation.m01) + Math.abs(this.rotation.m11) + Math.abs(this.rotation.m21));
			double ez = 0.5 * (Math.abs(this.rotation.m02) + Math.abs(this.rotation.m12) + Math.abs(this.rotation.m22));
			x0 = Math.min(x0, p.x - ex);
			y0 = Math.min(y0, p.y - ey);
			z0 = Math.min(z0, p.z - ez);
			x1 = Math.max(x1, p.x + ex);
			y1 = Math.max(y1, p.y + ey);
			z1 = Math.max(z1, p.z + ez);
		}
		this.minX = x0;
		this.minY = y0;
		this.minZ = z0;
		this.maxX = x1;
		this.maxY = y1;
		this.maxZ = z1;
	}

	public record Hit(int x, int y, int z, int face, double distance) {
	}

	private static final double[][] UNIT_BOX = {{0, 0, 0, 1, 1, 1}};

	public @Nullable Hit rayCast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDistance) {
		if (this.cells.isEmpty()) {
			return null;
		}
		Vector3d origin = this.toGrid(ox, oy, oz, new Vector3d());
		Vector3d dir = this.dirToGrid(dx, dy, dz, new Vector3d());
		double[] o = {origin.x, origin.y, origin.z};
		double[] d = {dir.x, dir.y, dir.z};
		Hit best = null;
		double bestT = maxDistance;
		for (Cell cell : this.cells) {
			for (double[] box : cell.boxes == null ? UNIT_BOX : cell.boxes) {
				double[] lo = {cell.x + box[0], cell.y + box[1], cell.z + box[2]};
				double[] hi = {cell.x + box[3], cell.y + box[4], cell.z + box[5]};
				double t0 = 0.0;
				double t1 = bestT;
				int enter = -1;
				boolean miss = false;
				for (int a = 0; a < 3 && !miss; a++) {
					if (Math.abs(d[a]) < 1.0E-12) {
						miss = o[a] < lo[a] || o[a] > hi[a];
						continue;
					}
					double ta = (lo[a] - o[a]) / d[a];
					double tb = (hi[a] - o[a]) / d[a];
					double near = Math.min(ta, tb);
					double far = Math.max(ta, tb);
					if (near > t0) {
						t0 = near;
						enter = 2 * a + (d[a] > 0.0 ? 0 : 1);
					}
					t1 = Math.min(t1, far);
					miss = t0 > t1;
				}
				if (!miss && t0 < bestT && enter >= 0) {
					bestT = t0;
					best = new Hit(cell.x, cell.y, cell.z, enter, t0);
				}
			}
		}
		return best;
	}

	public Vector3d toWorld(double gx, double gy, double gz, Vector3d out) {
		double lx = gx - this.com.x;
		double ly = gy - this.com.y;
		double lz = gz - this.com.z;
		Matrix3d r = this.rotation;
		out.set(
			this.pos.x + r.m00 * lx + r.m10 * ly + r.m20 * lz,
			this.pos.y + r.m01 * lx + r.m11 * ly + r.m21 * lz,
			this.pos.z + r.m02 * lx + r.m12 * ly + r.m22 * lz);
		return out;
	}

	public Vector3d toGrid(double wx, double wy, double wz, Vector3d out) {
		double dx = wx - this.pos.x;
		double dy = wy - this.pos.y;
		double dz = wz - this.pos.z;
		Matrix3d r = this.rotation;
		out.set(
			r.m00 * dx + r.m01 * dy + r.m02 * dz + this.com.x,
			r.m10 * dx + r.m11 * dy + r.m12 * dz + this.com.y,
			r.m20 * dx + r.m21 * dy + r.m22 * dz + this.com.z);
		return out;
	}

	public Vector3d dirToWorld(double x, double y, double z, Vector3d out) {
		Matrix3d r = this.rotation;
		out.set(r.m00 * x + r.m10 * y + r.m20 * z, r.m01 * x + r.m11 * y + r.m21 * z, r.m02 * x + r.m12 * y + r.m22 * z);
		return out;
	}

	public boolean overlaps(RigidBody o, double margin) {
		return this.minX - margin < o.maxX && o.minX - margin < this.maxX && this.minY - margin < o.maxY && o.minY - margin < this.maxY
			&& this.minZ - margin < o.maxZ && o.minZ - margin < this.maxZ;
	}

	public boolean containsWorld(double x, double y, double z) {
		if (x < this.minX || x > this.maxX || y < this.minY || y > this.maxY || z < this.minZ || z > this.maxZ) {
			return false;
		}
		Vector3d g = this.toGrid(x, y, z, new Vector3d());
		int cx = (int) Math.floor(g.x);
		int cy = (int) Math.floor(g.y);
		int cz = (int) Math.floor(g.z);
		Cell c = this.cellAt(cx, cy, cz);
		return c != null && c.contains(g.x - cx, g.y - cy, g.z - cz);
	}

	public Vector3d dirToGrid(double x, double y, double z, Vector3d out) {
		Matrix3d r = this.rotation;
		out.set(r.m00 * x + r.m01 * y + r.m02 * z, r.m10 * x + r.m11 * y + r.m12 * z, r.m20 * x + r.m21 * y + r.m22 * z);
		return out;
	}

	public Vector3d angularResponse(Vector3d v) {
		return this.invInertiaWorld.transform(new Vector3d(v));
	}

	public Vector3d velocityAt(double px, double py, double pz, Vector3d out) {
		double rx = px - this.pos.x;
		double ry = py - this.pos.y;
		double rz = pz - this.pos.z;
		return out.set(this.vel.x + this.ang.y * rz - this.ang.z * ry, this.vel.y + this.ang.z * rx - this.ang.x * rz,
			this.vel.z + this.ang.x * ry - this.ang.y * rx);
	}

	public double effectiveMass(double px, double py, double pz, double nx, double ny, double nz) {
		Vector3d r = new Vector3d(px - this.pos.x, py - this.pos.y, pz - this.pos.z);
		Vector3d t = this.angularResponse(new Vector3d(r).cross(nx, ny, nz));
		double k = this.invMass + t.cross(r).dot(nx, ny, nz);
		return k > 0.0 ? 1.0 / k : 0.0;
	}

	public void applyImpulse(double px, double py, double pz, double ix, double iy, double iz) {
		if (this.mass <= 0.0) {
			return;
		}
		this.vel.add(ix * this.invMass, iy * this.invMass, iz * this.invMass);
		double rx = px - this.pos.x;
		double ry = py - this.pos.y;
		double rz = pz - this.pos.z;
		Vector3d torque = new Vector3d(ry * iz - rz * iy, rz * ix - rx * iz, rx * iy - ry * ix);
		this.invInertiaWorld.transform(torque);
		this.ang.add(torque);
	}

	public List<List<Cell>> components() {
		List<List<Cell>> out = new ArrayList<>();
		LongOpenHashSet done = new LongOpenHashSet();
		ArrayDeque<Cell> queue = new ArrayDeque<>();
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (Cell start : this.cells) {
			if (!done.add(key(start.x, start.y, start.z))) {
				continue;
			}
			List<Cell> group = new ArrayList<>();
			queue.add(start);
			while (!queue.isEmpty()) {
				Cell c = queue.poll();
				group.add(c);
				for (int[] d : dirs) {
					Cell n = this.cellAt(c.x + d[0], c.y + d[1], c.z + d[2]);
					if (n != null && done.add(key(n.x, n.y, n.z))) {
						queue.add(n);
					}
				}
			}
			out.add(group);
		}
		return out;
	}
}
