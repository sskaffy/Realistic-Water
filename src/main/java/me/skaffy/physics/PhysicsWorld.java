package me.skaffy.physics;

import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix3d;
import org.joml.Vector3d;

public final class PhysicsWorld {
	public double gravity = 9.81;
	public java.util.function.@org.jspecify.annotations.Nullable ToDoubleFunction<RigidBody> gravityOf;
	public int iterations = 10;
	public double friction = 0.6;
	public double restitution = 0.1;
	public double waterDensity = 1000.0;
	public double lavaDensity = 2000.0;
	public double waterDrag = 4.0;
	public double sidewaysWaterDrag = 0.3;
	public double lavaDrag = 6.0;
	public double burnTime = 3.0;
	public double maxSpeed = 50.0;

	private static final double SLOP = 0.004;
	private static final double BETA = 0.25;
	private static final double MAX_BIAS = 4.0;

	public final List<RigidBody> bodies = new ArrayList<>();
	public final List<Push> pushes = new ArrayList<>();

	public static final class Push {
		public final RigidBody body;
		final Vector3d grid = new Vector3d();
		final Vector3d dir = new Vector3d();
		final double target;
		final double force;
		double kick;

		public Push(RigidBody body, double px, double py, double pz, double dx, double dy, double dz, double target, double force, double kick) {
			this.body = body;
			body.toGrid(px, py, pz, this.grid);
			this.dir.set(dx, dy, dz).normalize();
			this.target = target;
			this.force = force;
			this.kick = kick;
		}
	}

	public final List<Turn> turns = new ArrayList<>();

	public static final class Turn {
		public final RigidBody body;
		final Vector3d axis = new Vector3d();
		final double target;
		final double torque;

		public Turn(RigidBody body, double ax, double ay, double az, double target, double torque) {
			this.body = body;
			this.axis.set(ax, ay, az).normalize();
			this.target = target;
			this.torque = torque;
		}
	}

	public interface Events {
		void burnedOut(RigidBody body, RigidBody.Cell cell);
	}

	private static final class Contact {
		RigidBody a;
		RigidBody b;
		int point;
		final Vector3d p = new Vector3d();
		final Vector3d n = new Vector3d();
		final Vector3d ra = new Vector3d();
		final Vector3d rb = new Vector3d();
		double depth;
		double mu;
		double kn;
		double bias;
		double jn;
		final Vector3d jt = new Vector3d();
	}

	private final List<Contact> contacts = new ArrayList<>();
	private final List<Contact> pool = new ArrayList<>();
	private int stepCount;

	public void step(PhysicsView view, double dt, Events events) {
		for (RigidBody b : this.bodies) {
			if (b.mass <= 0.0) {
				continue;
			}
			b.flowAge += dt;
			b.surfaceAge += dt;
			if (b.kinematic) {
				this.fluidForces(b, view, dt, events, false);
				continue;
			}
			b.vel.y -= (this.gravityOf == null ? this.gravity : this.gravityOf.applyAsDouble(b)) * dt;
			this.fluidForces(b, view, dt, events, true);
		}
		this.applyPushes(dt);
		this.applyTurns(dt);

		this.contacts.clear();
		for (RigidBody b : this.bodies) {
			if (dynamic(b)) {
				int before = this.contacts.size();
				this.worldContacts(b, view);
				b.worldContacts = this.contacts.size() - before;
			}
		}
		for (int i = 0; i < this.bodies.size(); i++) {
			RigidBody a = this.bodies.get(i);
			for (int j = i + 1; j < this.bodies.size(); j++) {
				RigidBody b = this.bodies.get(j);
				if (!dynamic(a) && !dynamic(b) || a.mass <= 0.0 || b.mass <= 0.0 || !a.overlaps(b, 0.05)) {
					continue;
				}
				this.bodyContacts(a, b);
				this.bodyContacts(b, a);
			}
		}

		this.stepCount++;
		for (Contact c : this.contacts) {
			this.prepare(c, dt);
		}
		for (Contact c : this.contacts) {
			this.warmStart(c);
		}
		for (int it = 0; it < this.iterations; it++) {
			for (Contact c : this.contacts) {
				this.solve(c);
			}
		}
		for (Contact c : this.contacts) {
			c.a.remember(c.point, this.stepCount, c.b, c.n, c.jn, c.jt);
		}

		for (RigidBody b : this.bodies) {
			if (!dynamic(b)) {
				continue;
			}
			double sp = b.vel.length();
			if (sp > this.maxSpeed) {
				b.vel.mul(this.maxSpeed / sp);
			}
			double w = b.ang.length();
			if (w > 30.0) {
				b.ang.mul(30.0 / w);
			}
			b.pos.fma(dt, b.vel);
			double hx = 0.5 * dt * b.ang.x;
			double hy = 0.5 * dt * b.ang.y;
			double hz = 0.5 * dt * b.ang.z;
			double qx = b.rot.x;
			double qy = b.rot.y;
			double qz = b.rot.z;
			double qw = b.rot.w;
			b.rot.set(qx + hx * qw + hy * qz - hz * qy, qy + hy * qw + hz * qx - hx * qz, qz + hz * qw + hx * qy - hy * qx,
				qw - hx * qx - hy * qy - hz * qz);
			b.updateDerived();
			b.vel.mul(1.0 - 0.02 * dt);
			b.ang.mul(1.0 - 0.1 * dt);
		}
	}

	private void applyTurns(double dt) {
		for (Turn t : this.turns) {
			RigidBody b = t.body;
			if (!dynamic(b)) {
				continue;
			}
			Vector3d response = b.angularResponse(t.axis);
			double k = response.dot(t.axis);
			if (k <= 0.0) {
				continue;
			}
			double cap = t.torque * dt;
			double l = Math.clamp((t.target - b.ang.dot(t.axis)) / k, -cap, cap);
			b.ang.fma(l, response);
		}
	}

	private void applyPushes(double dt) {
		Vector3d at = new Vector3d();
		Vector3d v = new Vector3d();
		for (Push p : this.pushes) {
			RigidBody b = p.body;
			if (!dynamic(b)) {
				continue;
			}
			b.toWorld(p.grid.x, p.grid.y, p.grid.z, at);
			double cap = p.force * dt + p.kick;
			double j = cap;
			if (!Double.isInfinite(p.target)) {
				double along = b.velocityAt(at.x, at.y, at.z, v).dot(p.dir);
				j = Math.min(cap, b.effectiveMass(at.x, at.y, at.z, p.dir.x, p.dir.y, p.dir.z) * (p.target - along));
			}
			if (j <= 0.0) {
				continue;
			}
			p.kick = Math.max(0.0, p.kick - Math.max(0.0, j - p.force * dt));
			b.applyImpulse(at.x, at.y, at.z, p.dir.x * j, p.dir.y * j, p.dir.z * j);
		}
	}


	private Contact newContact() {
		Contact c = this.contacts.size() < this.pool.size() ? this.pool.get(this.contacts.size()) : null;
		if (c == null) {
			c = new Contact();
			this.pool.add(c);
		}
		this.contacts.add(c);
		c.jn = 0.0;
		c.jt.zero();
		return c;
	}

	private static boolean inside(double[] box, double x, double y, double z) {
		return x > box[0] && x < box[3] && y > box[1] && y < box[4] && z > box[2] && z < box[5];
	}

	private static boolean solidAt(PhysicsView view, double x, double y, double z) {
		double[][] boxes = view.boxes((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
		if (boxes != null) {
			for (double[] box : boxes) {
				if (inside(box, x, y, z)) {
					return true;
				}
			}
		}
		return false;
	}

	private static final double[][] DIRS = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};

	private void worldContacts(RigidBody b, PhysicsView view) {
		double[] pts = b.points;
		Vector3d p = new Vector3d();
		for (int i = 0; i < pts.length; i += 3) {
			b.toWorld(pts[i], pts[i + 1], pts[i + 2], p);
			int bx = (int) Math.floor(p.x);
			int by = (int) Math.floor(p.y);
			int bz = (int) Math.floor(p.z);
			double[][] boxes = view.boxes(bx, by, bz);
			if (boxes == null) {
				continue;
			}
			for (double[] box : boxes) {
				if (!inside(box, p.x, p.y, p.z)) {
					continue;
				}
				double[] pen = {box[4] - p.y, box[3] - p.x, p.x - box[0], box[5] - p.z, p.z - box[2], p.y - box[1]};
				int best = -1;
				for (int pass = 0; pass < 6 && best < 0; pass++) {
					int k = -1;
					for (int d = 0; d < 6; d++) {
						if (pen[d] >= 0.0 && (k < 0 || pen[d] < pen[k])) {
							k = d;
						}
					}
					if (k < 0) {
						break;
					}
					double s = pen[k] + 1e-4;
					if (!solidAt(view, p.x + DIRS[k][0] * s, p.y + DIRS[k][1] * s, p.z + DIRS[k][2] * s)) {
						best = k;
					} else {
						pen[k] = -1.0;
					}
				}
				if (best < 0) {
					best = 0;
					pen[0] = box[4] - p.y;
				}
				Contact c = this.newContact();
				c.a = b;
				c.b = null;
				c.point = i / 3;
				c.p.set(p);
				c.n.set(DIRS[best][0], DIRS[best][1], DIRS[best][2]);
				c.depth = pen[best];
				c.mu = this.friction * b.pointFriction[i / 3] * view.friction(bx, by, bz);
				break;
			}
		}
	}

	private void bodyContacts(RigidBody a, RigidBody b) {
		double[] pts = a.points;
		Vector3d p = new Vector3d();
		Vector3d g = new Vector3d();
		for (int i = 0; i < pts.length; i += 3) {
			a.toWorld(pts[i], pts[i + 1], pts[i + 2], p);
			if (p.x < b.minX || p.x > b.maxX || p.y < b.minY || p.y > b.maxY || p.z < b.minZ || p.z > b.maxZ) {
				continue;
			}
			b.toGrid(p.x, p.y, p.z, g);
			int cx = (int) Math.floor(g.x);
			int cy = (int) Math.floor(g.y);
			int cz = (int) Math.floor(g.z);
			RigidBody.Cell cell = b.cellAt(cx, cy, cz);
			if (cell == null) {
				continue;
			}
			double fx = g.x - cx;
			double fy = g.y - cy;
			double fz = g.z - cz;
			double[] pen;
			int best = -1;
			if (cell.full()) {
				pen = new double[]{1.0 - fy, 1.0 - fx, fx, 1.0 - fz, fz, fy};
				for (int d = 0; d < 6; d++) {
					RigidBody.Cell n = b.cellAt(cx + (int) DIRS[d][0], cy + (int) DIRS[d][1], cz + (int) DIRS[d][2]);
					if ((n == null || !n.full()) && (best < 0 || pen[d] < pen[best])) {
						best = d;
					}
				}
			} else {
				double[] box = null;
				for (double[] bx : cell.boxes) {
					if (fx > bx[0] && fx < bx[3] && fy > bx[1] && fy < bx[4] && fz > bx[2] && fz < bx[5]) {
						box = bx;
						break;
					}
				}
				if (box == null) {
					continue;
				}
				pen = new double[]{box[4] - fy, box[3] - fx, fx - box[0], box[5] - fz, fz - box[2], fy - box[1]};
				for (int d = 0; d < 6; d++) {
					if (best < 0 || pen[d] < pen[best]) {
						best = d;
					}
				}
			}
			if (best < 0) {
				continue;
			}
			Contact c = this.newContact();
			c.a = a;
			c.b = b;
			c.point = i / 3;
			c.p.set(p);
			b.dirToWorld(DIRS[best][0], DIRS[best][1], DIRS[best][2], c.n);
			c.depth = pen[best];
			c.mu = this.friction * Math.sqrt(a.pointFriction[i / 3] * cell.friction);
		}
	}


	private static boolean dynamic(RigidBody b) {
		return b != null && !b.kinematic && b.mass > 0.0;
	}

	private static double angularTerm(Matrix3d invI, Vector3d r, Vector3d n) {
		Vector3d rn = new Vector3d(r).cross(n);
		invI.transform(rn);
		return rn.cross(r).dot(n);
	}

	private void prepare(Contact c, double dt) {
		c.ra.set(c.p).sub(c.a.pos);
		double k = 0.0;
		if (dynamic(c.a)) {
			k += c.a.invMass + angularTerm(c.a.invInertiaWorld, c.ra, c.n);
		}
		if (c.b != null) {
			c.rb.set(c.p).sub(c.b.pos);
			if (dynamic(c.b)) {
				k += c.b.invMass + angularTerm(c.b.invInertiaWorld, c.rb, c.n);
			}
		}
		c.kn = k > 0.0 ? 1.0 / k : 0.0;
		c.bias = Math.min(BETA / dt * Math.max(c.depth - SLOP, 0.0), MAX_BIAS);
		double vn = this.relativeVelocity(c, new Vector3d()).dot(c.n);
		if (vn < -1.5) {
			c.bias = Math.max(c.bias, -this.restitution * vn);
		}
	}

	private void warmStart(Contact c) {
		if (c.kn <= 0.0) {
			return;
		}
		double[] w = c.a.recalled(c.point, this.stepCount - 1, c.b, c.n);
		if (w == null) {
			return;
		}
		c.jn = w[0] * 0.95;
		Vector3d jt = new Vector3d(w[1], w[2], w[3]);
		jt.fma(-jt.dot(c.n), c.n).mul(0.95);
		double limit = c.mu * c.jn;
		if (jt.length() > limit) {
			jt.mul(limit / jt.length());
		}
		c.jt.set(jt);
		this.apply(c, new Vector3d(c.n).mul(c.jn).add(jt));
	}

	private Vector3d relativeVelocity(Contact c, Vector3d out) {
		c.a.velocityAt(c.p.x, c.p.y, c.p.z, out);
		if (c.b != null) {
			Vector3d vb = c.b.velocityAt(c.p.x, c.p.y, c.p.z, new Vector3d());
			out.sub(vb);
		}
		return out;
	}

	private void apply(Contact c, Vector3d impulse) {
		if (dynamic(c.a)) {
			c.a.vel.fma(c.a.invMass, impulse);
			Vector3d t = new Vector3d(c.ra).cross(impulse);
			c.a.invInertiaWorld.transform(t);
			c.a.ang.add(t);
		}
		if (dynamic(c.b)) {
			c.b.vel.fma(-c.b.invMass, impulse);
			Vector3d t = new Vector3d(c.rb).cross(impulse);
			c.b.invInertiaWorld.transform(t);
			c.b.ang.sub(t);
		}
	}

	private void solve(Contact c) {
		if (c.kn <= 0.0) {
			return;
		}
		Vector3d vr = this.relativeVelocity(c, new Vector3d());
		double vn = vr.dot(c.n);
		double lambda = (c.bias - vn) * c.kn;
		double old = c.jn;
		c.jn = Math.max(old + lambda, 0.0);
		lambda = c.jn - old;
		if (lambda != 0.0) {
			this.apply(c, new Vector3d(c.n).mul(lambda));
		}

		this.relativeVelocity(c, vr);
		Vector3d vt = new Vector3d(vr).fma(-vr.dot(c.n), c.n);
		double speed = vt.length();
		if (speed < 1e-9) {
			return;
		}
		Vector3d t = new Vector3d(vt).div(speed);
		double k = 0.0;
		if (dynamic(c.a)) {
			k += c.a.invMass + angularTerm(c.a.invInertiaWorld, c.ra, t);
		}
		if (dynamic(c.b)) {
			k += c.b.invMass + angularTerm(c.b.invInertiaWorld, c.rb, t);
		}
		if (k <= 0.0) {
			return;
		}
		Vector3d want = new Vector3d(t).mul(-speed / k);
		Vector3d total = new Vector3d(c.jt).add(want);
		double limit = c.mu * c.jn;
		double len = total.length();
		if (len > limit) {
			total.mul(limit / len);
		}
		Vector3d delta = new Vector3d(total).sub(c.jt);
		c.jt.set(total);
		this.apply(c, delta);
	}


	private static int fluidAt(RigidBody b, PhysicsView view, double x, double y, double z) {
		int f = fluidAt(view, x, y, z);
		if (f == 1 || b.surfaceAge >= 0.75) {
			return f;
		}
		return y < b.surfaceY ? 0 : -1;
	}

	private static int fluidAt(PhysicsView view, double x, double y, double z) {
		int by = (int) Math.floor(y);
		int m = view.medium((int) Math.floor(x), by, (int) Math.floor(z));
		if (m == 0) {
			return -1;
		}
		double fy = y - by;
		if (((m >> PhysicsView.LAVA_SHIFT) & 0xF) / 8.0 >= fy) {
			return 1;
		}
		return (m & PhysicsView.WATER_MASK) / 8.0 >= fy ? 0 : -1;
	}

	private void fluidForces(RigidBody b, PhysicsView view, double dt, Events events, boolean apply) {
		Vector3d p = new Vector3d();
		Vector3d v = new Vector3d();
		double[] current = new double[3];
		boolean simulatedFlow = b.flowAge < 0.75;
		b.submerged = false;
		List<RigidBody.Cell> done = null;
		for (RigidBody.Cell cell : b.cells) {
			boolean hot = false;
			for (int s = 0; s < 8; s++) {
				b.toWorld(cell.x + 0.25 + 0.5 * (s & 1), cell.y + 0.25 + 0.5 * ((s >> 1) & 1), cell.z + 0.25 + 0.5 * (s >> 2), p);
				hot |= (view.medium((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)) & PhysicsView.FIRE) != 0;
				double submerged = 0.0;
				boolean lavaHere = false;
				for (int q = 0; q < 4; q++) {
					int f = fluidAt(b, view, p.x, p.y - 0.1875 + 0.125 * q, p.z);
					if (f >= 0) {
						submerged += 0.25;
						lavaHere |= f == 1;
					}
				}
				double share = cell.eighths[s];
				if (submerged <= 0.0 || share <= 0.0) {
					continue;
				}
				hot |= lavaHere;
				b.submerged = true;
				if (!apply) {
					continue;
				}
				double density = lavaHere ? this.lavaDensity : this.waterDensity;
				double drag = lavaHere ? this.lavaDrag : this.waterDrag;
				b.velocityAt(p.x, p.y, p.z, v);
				if (simulatedFlow && !lavaHere) {
					v.sub(b.flow);
				} else if (view.flow((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z), current)) {
					v.sub(current[0], current[1], current[2]);
				}
				double eighth = cell.density * 0.125 * share;
				double rate = drag * submerged * density / cell.density;
				double slow = eighth * (1.0 - Math.exp(-rate * dt));
				double side = lavaHere ? slow : eighth * (1.0 - Math.exp(-rate * this.sidewaysWaterDrag * dt));
				double lift = density * 0.125 * share * submerged * this.gravity * dt;
				b.applyImpulse(p.x, p.y, p.z, -side * v.x, lift - slow * v.y, -side * v.z);
			}
			cell.burning = hot && cell.flammable;
			if (!cell.burning) {
				cell.burn = Math.max(cell.burn - 0.5 * dt, 0.0);
			} else {
				cell.burn += dt;
				if (cell.burn >= this.burnTime) {
					if (done == null) {
						done = new ArrayList<>();
					}
					done.add(cell);
				}
			}
		}
		if (done != null) {
			for (RigidBody.Cell cell : done) {
				events.burnedOut(b, cell);
			}
		}
	}
}
