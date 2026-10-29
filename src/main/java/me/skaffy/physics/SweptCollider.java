package me.skaffy.physics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class SweptCollider {
	private static final double SKIN = 1.0E-7;
	private static final int PUSH_STEPS = 4;
	private static final double MAX_VERTICAL_BEND = 0.34;

	private SweptCollider() {
	}

	public static final class Oriented {
		final double cx;
		final double cy;
		final double cz;
		final double[] axes;
		final double hx;
		final double hy;
		final double hz;
		final double minX;
		final double minY;
		final double minZ;
		final double maxX;
		final double maxY;
		final double maxZ;

		public Oriented(double cx, double cy, double cz, double[] axes, double hx, double hy, double hz) {
			this.cx = cx;
			this.cy = cy;
			this.cz = cz;
			this.axes = axes;
			this.hx = hx;
			this.hy = hy;
			this.hz = hz;
			double ex = Math.abs(axes[0]) * hx + Math.abs(axes[3]) * hy + Math.abs(axes[6]) * hz;
			double ey = Math.abs(axes[1]) * hx + Math.abs(axes[4]) * hy + Math.abs(axes[7]) * hz;
			double ez = Math.abs(axes[2]) * hx + Math.abs(axes[5]) * hy + Math.abs(axes[8]) * hz;
			this.minX = cx - ex;
			this.minY = cy - ey;
			this.minZ = cz - ez;
			this.maxX = cx + ex;
			this.maxY = cy + ey;
			this.maxZ = cz + ez;
		}

		private double reach(double nx, double ny, double nz) {
			return Math.abs(this.axes[0] * nx + this.axes[1] * ny + this.axes[2] * nz) * this.hx
				+ Math.abs(this.axes[3] * nx + this.axes[4] * ny + this.axes[5] * nz) * this.hy
				+ Math.abs(this.axes[6] * nx + this.axes[7] * ny + this.axes[8] * nz) * this.hz;
		}

		private double centreOn(double nx, double ny, double nz) {
			return this.cx * nx + this.cy * ny + this.cz * nz;
		}

		public boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			return this.minX < maxX && this.maxX > minX && this.minY < maxY && this.maxY > minY && this.minZ < maxZ && this.maxZ > minZ;
		}

		public double[] bounds() {
			return new double[]{this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ};
		}

		private double distanceSqTo(double x, double y, double z) {
			double dx = this.cx - x;
			double dy = this.cy - y;
			double dz = this.cz - z;
			return dx * dx + dy * dy + dz * dz;
		}
	}

	public record Result(double x, double y, double z, boolean grounded, double ex, double ey, double ez) {
	}

	public static Result resolve(double[] box, double dx, double dy, double dz, List<Oriented> boxes, double stepHeight, double maxSlope) {
		if (boxes.isEmpty()) {
			return new Result(dx, dy, dz, false, 0.0, 0.0, 0.0);
		}
		double[] start = box.clone();
		double[] escape = pushOut(start, boxes);
		if (escape != null) {
			move(start, escape[0], escape[1], escape[2]);
		}
		List<Oriented> near = sortedAround(start, boxes);
		double[] plain = correct(start, near, dx, dy, dz, maxSlope, false);
		boolean grounded = grounded(start, near, maxSlope);
		double[] best = plain;
		if (dx * dx + dz * dz > 1.0E-8 && stepHeight > 0.0 && grounded) {
			double[] stepped = step(start, dx, dy, dz, near, stepHeight, maxSlope);
			if (stepped != null && run(stepped) > run(plain) + 1.0E-9) {
				best = stepped;
			}
		}
		double[] after = start.clone();
		move(after, best[0], best[1], best[2]);
		boolean standing = grounded && best[1] > dy - 1.0E-9 || grounded(after, near, maxSlope);
		double ex = escape == null ? 0.0 : escape[0];
		double ey = escape == null ? 0.0 : escape[1];
		double ez = escape == null ? 0.0 : escape[2];
		return new Result(snap(best[0]), snap(best[1]), snap(best[2]), standing, ex, ey, ez);
	}

	private static double run(double[] movement) {
		return movement[0] * movement[0] + movement[2] * movement[2];
	}

	private static List<Oriented> sortedAround(double[] box, List<Oriented> boxes) {
		double cx = (box[0] + box[3]) * 0.5;
		double cy = (box[1] + box[4]) * 0.5;
		double cz = (box[2] + box[5]) * 0.5;
		List<Oriented> sorted = new ArrayList<>(boxes);
		sorted.sort(Comparator.comparingDouble(o -> o.distanceSqTo(cx, cy, cz)));
		return sorted;
	}

	private static double[] correct(double[] box, List<Oriented> boxes, double dx, double dy, double dz, double maxSlope, boolean verticalOnly) {
		double[] m = {dx, dy, dz};
		for (Oriented obb : boxes) {
			if (!sweptMeets(box, m, obb)) {
				continue;
			}
			Contact contact = timeOfImpact(box, m, obb);
			if (contact == null) {
				continue;
			}
			double time = contact.time();
			double[] consumed = {m[0] * time, m[1] * time, m[2] * time};
			double[] rest = {m[0] - consumed[0], m[1] - consumed[1], m[2] - consumed[2]};
			double[] moved = box.clone();
			move(moved, consumed[0], consumed[1], consumed[2]);
			if (!verticalOnly && grounded(moved, boxes, maxSlope)) {
				double[] lift = response(moved, obb, contact, rest, maxSlope, true);
				boolean sensible = rest[1] < 0.0 && lift[1] > rest[1] && lift[1] < MAX_VERTICAL_BEND
					|| rest[1] > 0.0 && lift[1] < rest[1] && lift[1] > -MAX_VERTICAL_BEND;
				if (sensible) {
					m[0] = consumed[0] + lift[0];
					m[1] = consumed[1] + lift[1];
					m[2] = consumed[2] + lift[2];
					continue;
				}
			}
			double[] response = response(moved, obb, contact, rest, maxSlope, verticalOnly);
			m[0] = consumed[0] + response[0];
			m[1] = consumed[1] + response[1];
			m[2] = consumed[2] + response[2];
		}
		return m;
	}

	private static double[] response(double[] box, Oriented obb, Contact contact, double[] rest, double maxSlope, boolean verticalOnly) {
		double ax = contact.ax();
		double ay = contact.ay();
		double az = contact.az();
		double along = rest[0] * ax + rest[1] * ay + rest[2] * az;
		double depth = penetrationAfter(box, obb, ax, ay, az, along);
		if (depth == 0.0) {
			return rest;
		}
		if (verticalOnly) {
			if (Math.abs(ay) < 1.0E-6) {
				return rest;
			}
			return new double[]{rest[0], rest[1] + depth / ay, rest[2]};
		}
		if (Math.abs(ay) >= Math.sin(Math.toRadians(maxSlope))) {
			double[] up = {rest[0], rest[1] + depth / ay, rest[2]};
			double horizontal = Math.sqrt(up[0] * up[0] + up[2] * up[2]);
			boolean tooSteep = horizontal > 1.0E-6 && Math.abs(up[1]) / horizontal >= Math.tan(Math.toRadians(maxSlope));
			if (!tooSteep && Math.abs(up[1]) <= MAX_VERTICAL_BEND) {
				return up;
			}
		}
		double hl = Math.sqrt(ax * ax + az * az);
		if (hl < 1.0E-9) {
			return new double[]{rest[0], 0.0, rest[2]};
		}
		double hx = ax / hl;
		double hz = az / hl;
		double k = ax * hx + az * hz;
		return new double[]{rest[0] + hx * depth / k, rest[1], rest[2] + hz * depth / k};
	}

	private static double penetrationAfter(double[] box, Oriented obb, double ax, double ay, double az, double along) {
		double ac = centreOn(box, ax, ay, az);
		double ar = reachOf(box, ax, ay, az);
		double bc = obb.centreOn(ax, ay, az);
		double br = obb.reach(ax, ay, az);
		double lead = bc - br - (ac + ar);
		double trail = bc + br - (ac - ar);
		if (Math.abs(trail) < Math.abs(lead)) {
			double left = trail - along;
			return left > 0.0 ? left : 0.0;
		}
		double left = lead - along;
		return left < 0.0 ? left : 0.0;
	}

	private record Contact(double time, double ax, double ay, double az) {
	}

	private static @Nullable Contact timeOfImpact(double[] box, double[] m, Oriented obb) {
		double[] axes = axesFor(obb);
		double latest = 0.0;
		double bx = 0.0;
		double by = 0.0;
		double bz = 0.0;
		boolean anyApart = false;
		for (int i = 0; i < axes.length; i += 3) {
			double ax = axes[i];
			double ay = axes[i + 1];
			double az = axes[i + 2];
			if (ax == 0.0 && ay == 0.0 && az == 0.0) {
				continue;
			}
			double time = axisTime(box, obb, ax, ay, az, m[0] * ax + m[1] * ay + m[2] * az);
			if (Double.isNaN(time)) {
				continue;
			}
			if (Double.isInfinite(time)) {
				return null;
			}
			anyApart = true;
			if (time >= latest) {
				latest = time;
				bx = ax;
				by = ay;
				bz = az;
			}
		}
		if (!anyApart) {
			double[] push = minimumPush(box, obb);
			if (push == null) {
				return null;
			}
			double len = Math.sqrt(push[0] * push[0] + push[1] * push[1] + push[2] * push[2]);
			if (len < 1.0E-12) {
				return null;
			}
			return new Contact(0.0, push[0] / len, push[1] / len, push[2] / len);
		}
		return latest < 1.0 ? new Contact(latest, bx, by, bz) : null;
	}

	private static double axisTime(double[] box, Oriented obb, double ax, double ay, double az, double speed) {
		double ac = centreOn(box, ax, ay, az);
		double ar = reachOf(box, ax, ay, az);
		double bc = obb.centreOn(ax, ay, az);
		double br = obb.reach(ax, ay, az);
		double lead = bc - br - (ac + ar);
		double trail = bc + br - (ac - ar);
		if (trail > 0.0 && lead < 0.0) {
			return Double.NaN;
		}
		if (Math.abs(speed) < 1.0E-8) {
			return Double.POSITIVE_INFINITY;
		}
		if (speed > 0.0) {
			return lead >= 0.0 ? Math.abs(lead / speed) : Double.POSITIVE_INFINITY;
		}
		return trail <= 0.0 ? Math.abs(trail / speed) : Double.POSITIVE_INFINITY;
	}

	private static double @Nullable [] step(double[] from, double dx, double dy, double dz, List<Oriented> boxes, double stepHeight, double maxSlope) {
		double[] box = from.clone();
		double[] up = correct(box, boxes, 0.0, stepHeight, 0.0, maxSlope, true);
		if (up[1] <= 1.0E-6) {
			return null;
		}
		move(box, 0.0, up[1], 0.0);
		double[] across = correct(box, boxes, dx, 0.0, dz, maxSlope, false);
		if (run(across) < 1.0E-10) {
			return null;
		}
		move(box, across[0], 0.0, across[2]);
		double[] down = correct(box, boxes, 0.0, -up[1] + Math.min(dy, 0.0), 0.0, maxSlope, true);
		double rise = up[1] + down[1];
		if (rise < -1.0E-6 || rise > stepHeight + 1.0E-6) {
			return null;
		}
		move(box, 0.0, down[1], 0.0);
		if (pushOut(box, boxes) != null) {
			return null;
		}
		return new double[]{across[0], rise, across[2]};
	}

	public static boolean grounded(double[] box, List<Oriented> boxes, double maxSlope) {
		double[] down = {0.0, -0.02, 0.0};
		for (Oriented obb : boxes) {
			if (!sweptMeets(box, down, obb)) {
				continue;
			}
			Contact contact = timeOfImpact(box, down, obb);
			if (contact == null) {
				continue;
			}
			double[] rest = {0.0, down[1] * (1.0 - contact.time()), 0.0};
			double[] moved = box.clone();
			move(moved, 0.0, down[1] * contact.time(), 0.0);
			if (response(moved, obb, contact, rest, maxSlope, false)[1] > rest[1] + 1.0E-9) {
				return true;
			}
		}
		return false;
	}

	public static double allowedAlong(double[] box, int axis, double amount, List<Oriented> boxes, double maxSlope) {
		double[] m = new double[3];
		m[axis] = amount;
		double[] clipped = correct(box, sortedAround(box, boxes), m[0], m[1], m[2], maxSlope, false);
		return clipped[axis];
	}

	public static double @Nullable [] pushOut(double[] box, List<Oriented> boxes) {
		double[] work = box.clone();
		double outX = 0.0;
		double outY = 0.0;
		double outZ = 0.0;
		boolean any = false;
		for (int step = 0; step < PUSH_STEPS; step++) {
			double bestDepth = Double.MAX_VALUE;
			double bx = 0.0;
			double by = 0.0;
			double bz = 0.0;
			for (Oriented obb : boxes) {
				if (!overlapsAabb(work, obb)) {
					continue;
				}
				double[] mtv = minimumPush(work, obb);
				if (mtv != null && mtv[3] < bestDepth) {
					bestDepth = mtv[3];
					bx = mtv[0];
					by = mtv[1];
					bz = mtv[2];
				}
			}
			if (bestDepth == Double.MAX_VALUE) {
				break;
			}
			any = true;
			outX += bx;
			outY += by;
			outZ += bz;
			move(work, bx, by, bz);
		}
		return any ? new double[]{outX, outY, outZ} : null;
	}

	private static double @Nullable [] minimumPush(double[] box, Oriented obb) {
		double[] axes = axesFor(obb);
		double bestDepth = Double.MAX_VALUE;
		double bx = 0.0;
		double by = 0.0;
		double bz = 0.0;
		for (int i = 0; i < axes.length; i += 3) {
			double nx = axes[i];
			double ny = axes[i + 1];
			double nz = axes[i + 2];
			if (nx == 0.0 && ny == 0.0 && nz == 0.0) {
				continue;
			}
			double ac = centreOn(box, nx, ny, nz);
			double ar = reachOf(box, nx, ny, nz);
			double bc = obb.centreOn(nx, ny, nz);
			double br = obb.reach(nx, ny, nz);
			double gap = Math.abs(bc - ac) - (ar + br);
			if (gap >= -SKIN) {
				return null;
			}
			double depth = -gap;
			if (depth < bestDepth) {
				bestDepth = depth;
				double sign = ac < bc ? -1.0 : 1.0;
				bx = nx * sign;
				by = ny * sign;
				bz = nz * sign;
			}
		}
		if (bestDepth == Double.MAX_VALUE) {
			return null;
		}
		return new double[]{bx * (bestDepth + SKIN), by * (bestDepth + SKIN), bz * (bestDepth + SKIN), bestDepth};
	}

	private static boolean sweptMeets(double[] box, double[] m, Oriented obb) {
		double minX = Math.min(box[0], box[0] + m[0]);
		double minY = Math.min(box[1], box[1] + m[1]);
		double minZ = Math.min(box[2], box[2] + m[2]);
		double maxX = Math.max(box[3], box[3] + m[0]);
		double maxY = Math.max(box[4], box[4] + m[1]);
		double maxZ = Math.max(box[5], box[5] + m[2]);
		return obb.intersects(minX - SKIN, minY - SKIN, minZ - SKIN, maxX + SKIN, maxY + SKIN, maxZ + SKIN);
	}

	private static double[] axesFor(Oriented obb) {
		double[] out = new double[45];
		out[0] = 1.0;
		out[4] = 1.0;
		out[8] = 1.0;
		System.arraycopy(obb.axes, 0, out, 9, 9);
		int at = 18;
		for (int w = 0; w < 3; w++) {
			double wx = w == 0 ? 1.0 : 0.0;
			double wy = w == 1 ? 1.0 : 0.0;
			double wz = w == 2 ? 1.0 : 0.0;
			for (int b = 0; b < 3; b++) {
				double bx = obb.axes[b * 3];
				double by = obb.axes[b * 3 + 1];
				double bz = obb.axes[b * 3 + 2];
				double cx = wy * bz - wz * by;
				double cy = wz * bx - wx * bz;
				double cz = wx * by - wy * bx;
				double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
				if (len > 1.0E-6) {
					out[at] = cx / len;
					out[at + 1] = cy / len;
					out[at + 2] = cz / len;
				}
				at += 3;
			}
		}
		return out;
	}

	private static boolean overlapsAabb(double[] box, Oriented obb) {
		return obb.intersects(box[0] - SKIN, box[1] - SKIN, box[2] - SKIN, box[3] + SKIN, box[4] + SKIN, box[5] + SKIN);
	}

	private static double centreOn(double[] box, double nx, double ny, double nz) {
		return (box[0] + box[3]) * 0.5 * nx + (box[1] + box[4]) * 0.5 * ny + (box[2] + box[5]) * 0.5 * nz;
	}

	private static double reachOf(double[] box, double nx, double ny, double nz) {
		return (box[3] - box[0]) * 0.5 * Math.abs(nx) + (box[4] - box[1]) * 0.5 * Math.abs(ny) + (box[5] - box[2]) * 0.5 * Math.abs(nz);
	}

	private static double snap(double value) {
		return Math.abs(value) < 1.0E-10 ? 0.0 : value;
	}

	private static void move(double[] box, double dx, double dy, double dz) {
		box[0] += dx;
		box[1] += dy;
		box[2] += dz;
		box[3] += dx;
		box[4] += dy;
		box[5] += dz;
	}
}
