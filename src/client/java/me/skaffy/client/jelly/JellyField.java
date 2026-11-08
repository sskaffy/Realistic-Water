package me.skaffy.client.jelly;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.client.water.WaterSettings;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class JellyField {
	static final int LATTICE = 4;
	private static final int SUBSTEPS = 6;
	private static final float DT = 1.0F / 120.0F;
	private static final float PRESS_SPRING = 6000.0F;
	private static final float PRESS_DAMP = 90.0F;
	private static final double FOOT_REACH = 0.3;
	private static final byte JELLY_SLIME = 1;
	private static final byte JELLY_HONEY = 2;
	private static final byte PINNED = 4;
	private static final int[][] STEPS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

	private static final Long2ObjectOpenHashMap<Point> points = new Long2ObjectOpenHashMap<>();
	private static final Long2ByteOpenHashMap kinds = new Long2ByteOpenHashMap();
	private static final Int2ObjectOpenHashMap<Touch> touches = new Int2ObjectOpenHashMap<>();
	private static final LongOpenHashSet awakeBlocks = new LongOpenHashSet();
	private static long tick;
	private static final BlockPos.MutableBlockPos SCRATCH = new BlockPos.MutableBlockPos();

	static {
		kinds.defaultReturnValue((byte) -1);
	}

	private JellyField() {
	}

	static final class Point {
		final long key;
		final int lx;
		final int ly;
		final int lz;
		JellyKind kind;
		int inGel;
		final Point[] next = new Point[6];
		float dx, dy, dz;
		float vx, vy, vz;
		float px, py, pz;
		float ax, ay, az;
		int pressAxis = -1;
		float press;
		int quiet;

		Point(long key, int lx, int ly, int lz, JellyKind kind) {
			this.key = key;
			this.lx = lx;
			this.ly = ly;
			this.lz = lz;
			this.kind = kind;
		}

		void pressTo(int axis, float target) {
			if (this.pressAxis < 0 || Math.abs(target) > Math.abs(this.press)) {
				this.pressAxis = axis;
				this.press = target;
			}
		}
	}

	private static final class Touch {
		double lastVy;
		boolean onTop;
		@Nullable AABB lastBox;
		@Nullable JellyKind lastKind;
		long seen;
	}

	public static void clear() {
		points.clear();
		kinds.clear();
		touches.clear();
		awakeBlocks.clear();
	}

	static boolean isAwake(long block) {
		return awakeBlocks.contains(block);
	}

	public static int pointCount() {
		return points.size();
	}


	private static byte kindAt(ClientLevel level, int lx, int ly, int lz) {
		long key = BlockPos.asLong(lx, ly, lz);
		byte known = kinds.get(key);
		if (known != -1) {
			return known;
		}
		byte k = 0;
		BlockPos.MutableBlockPos at = SCRATCH;
		for (int x = Math.floorDiv(lx - 1, LATTICE); x <= Math.floorDiv(lx, LATTICE); x++) {
			for (int y = Math.floorDiv(ly - 1, LATTICE); y <= Math.floorDiv(ly, LATTICE); y++) {
				for (int z = Math.floorDiv(lz - 1, LATTICE); z <= Math.floorDiv(lz, LATTICE); z++) {
					BlockState state = level.getBlockState(at.set(x, y, z));
					JellyKind kind = JellyKind.of(state);
					if (kind == JellyKind.SLIME) {
						k |= JELLY_SLIME;
					} else if (kind == JellyKind.HONEY) {
						k |= JELLY_HONEY;
					} else if (!state.getCollisionShape(level, at).isEmpty()) {
						k |= PINNED;
					}
				}
			}
		}
		kinds.put(key, k);
		return k;
	}

	private static boolean inGel(byte k) {
		return (k & (JELLY_SLIME | JELLY_HONEY)) != 0;
	}

	private static @Nullable Point wake(ClientLevel level, int lx, int ly, int lz) {
		long key = BlockPos.asLong(lx, ly, lz);
		Point p = points.get(key);
		if (p != null) {
			return p;
		}
		byte k = kindAt(level, lx, ly, lz);
		if (!inGel(k) || (k & PINNED) != 0) {
			return null;
		}
		p = new Point(key, lx, ly, lz, (k & JELLY_SLIME) != 0 ? JellyKind.SLIME : JellyKind.HONEY);
		link(level, p);
		points.put(key, p);
		return p;
	}

	private static void link(ClientLevel level, Point p) {
		p.inGel = 0;
		for (int i = 0; i < 6; i++) {
			int[] s = STEPS[i];
			if (inGel(kindAt(level, p.lx + s[0], p.ly + s[1], p.lz + s[2]))) {
				p.inGel |= 1 << i;
			}
			Point o = points.get(BlockPos.asLong(p.lx + s[0], p.ly + s[1], p.lz + s[2]));
			p.next[i] = o;
			if (o != null) {
				o.next[i ^ 1] = p;
			}
		}
	}

	private static void unlink(Point p) {
		for (int i = 0; i < 6; i++) {
			Point o = p.next[i];
			if (o != null) {
				o.next[i ^ 1] = null;
				p.next[i] = null;
			}
		}
	}

	public static void onBlockChanged(ClientLevel level, BlockPos pos) {
		if (points.isEmpty() && kinds.isEmpty()) {
			return;
		}
		int x0 = pos.getX() * LATTICE - 1;
		int y0 = pos.getY() * LATTICE - 1;
		int z0 = pos.getZ() * LATTICE - 1;
		for (int i = 0; i <= LATTICE + 2; i++) {
			for (int j = 0; j <= LATTICE + 2; j++) {
				for (int k = 0; k <= LATTICE + 2; k++) {
					kinds.remove(BlockPos.asLong(x0 + i, y0 + j, z0 + k));
				}
			}
		}
		for (int i = 0; i <= LATTICE + 2; i++) {
			for (int j = 0; j <= LATTICE + 2; j++) {
				for (int k = 0; k <= LATTICE + 2; k++) {
					Point p = points.get(BlockPos.asLong(x0 + i, y0 + j, z0 + k));
					if (p == null) {
						continue;
					}
					byte kind = kindAt(level, p.lx, p.ly, p.lz);
					if (inGel(kind) && (kind & PINNED) == 0) {
						p.kind = (kind & JELLY_SLIME) != 0 ? JellyKind.SLIME : JellyKind.HONEY;
						link(level, p);
					} else {
						points.remove(p.key);
						unlink(p);
					}
				}
			}
		}
		refreshAwake();
	}


	public static void tick(ClientLevel level, Vec3 camera) {
		tick++;
		if (tick % 200 == 0 && points.isEmpty()) {
			kinds.clear();
		}
		for (Point p : points.values()) {
			p.px = p.dx;
			p.py = p.dy;
			p.pz = p.dz;
			p.pressAxis = -1;
		}
		touchBodies(level, camera);
		if (points.isEmpty()) {
			return;
		}
		bulge(level);
		float soft = Math.max(WaterSettings.wobblySoftness, 0.3F);
		for (int s = 0; s < SUBSTEPS; s++) {
			substep(soft);
		}
		settle(level);
		refreshAwake();
	}

	private static void substep(float soft) {
		for (Point p : points.values()) {
			JellyKind m = p.kind;
			float lx = 0.0F;
			float ly = 0.0F;
			float lz = 0.0F;
			for (int i = 0; i < 6; i++) {
				if ((p.inGel & (1 << i)) == 0) {
					continue;
				}
				Point o = p.next[i];
				if (o != null) {
					lx += o.dx - p.dx;
					ly += o.dy - p.dy;
					lz += o.dz - p.dz;
				} else {
					lx -= p.dx;
					ly -= p.dy;
					lz -= p.dz;
				}
			}
			float k = m.stiffness / soft;
			float c = m.coupling / soft;
			p.ax = -k * p.dx + c * lx - m.damping * p.vx;
			p.ay = -k * p.dy + c * ly - m.damping * p.vy;
			p.az = -k * p.dz + c * lz - m.damping * p.vz;
			if (p.pressAxis >= 0) {
				float d = p.pressAxis == 0 ? p.dx : p.pressAxis == 1 ? p.dy : p.dz;
				float v = p.pressAxis == 0 ? p.vx : p.pressAxis == 1 ? p.vy : p.vz;
				if (p.press < 0.0F ? d > p.press : d < p.press) {
					float f = PRESS_SPRING * (p.press - d) - PRESS_DAMP * v;
					if (p.pressAxis == 0) {
						p.ax += f;
					} else if (p.pressAxis == 1) {
						p.ay += f;
					} else {
						p.az += f;
					}
				}
			}
		}
		for (Point p : points.values()) {
			p.vx += p.ax * DT;
			p.vy += p.ay * DT;
			p.vz += p.az * DT;
			p.dx += p.vx * DT;
			p.dy += p.vy * DT;
			p.dz += p.vz * DT;
			float len = (float) Math.sqrt(p.dx * p.dx + p.dy * p.dy + p.dz * p.dz);
			float max = p.kind.maxDisplacement;
			if (len > max) {
				float f = max / len;
				p.dx *= f;
				p.dy *= f;
				p.dz *= f;
				p.vx *= 0.5F;
				p.vy *= 0.5F;
				p.vz *= 0.5F;
			}
		}
	}

	private static void settle(ClientLevel level) {
		List<Point> moving = new ArrayList<>();
		List<Point> calm = new ArrayList<>();
		for (Point p : points.values()) {
			float d = Math.abs(p.dx) + Math.abs(p.dy) + Math.abs(p.dz);
			float v = Math.abs(p.vx) + Math.abs(p.vy) + Math.abs(p.vz);
			if (d > 0.002F || v > 0.02F) {
				p.quiet = 0;
				moving.add(p);
			} else if (p.pressAxis < 0 && d < 0.0015F && v < 0.01F && ++p.quiet > 10) {
				calm.add(p);
			}
		}
		for (Point p : moving) {
			for (int i = 0; i < 6; i++) {
				if ((p.inGel & (1 << i)) != 0 && p.next[i] == null) {
					int[] s = STEPS[i];
					wake(level, p.lx + s[0], p.ly + s[1], p.lz + s[2]);
				}
			}
		}
		for (Point p : calm) {
			points.remove(p.key);
			unlink(p);
		}
	}

	private static void refreshAwake() {
		awakeBlocks.clear();
		for (Point p : points.values()) {
			for (int x = Math.floorDiv(p.lx - 1, LATTICE); x <= Math.floorDiv(p.lx, LATTICE); x++) {
				for (int y = Math.floorDiv(p.ly - 1, LATTICE); y <= Math.floorDiv(p.ly, LATTICE); y++) {
					for (int z = Math.floorDiv(p.lz - 1, LATTICE); z <= Math.floorDiv(p.lz, LATTICE); z++) {
						awakeBlocks.add(BlockPos.asLong(x, y, z));
					}
				}
			}
		}
	}


	private static void touchBodies(ClientLevel level, Vec3 camera) {
		double range = Math.max(8, WaterSettings.wobblyRange);
		AABB area = new AABB(camera, camera).inflate(range);
		for (Entity e : level.getEntities((Entity) null, area, e -> !e.isSpectator())) {
			touch(level, e);
		}
		touches.int2ObjectEntrySet().removeIf(entry -> entry.getValue().seen != tick);
	}

	private static void touch(ClientLevel level, Entity e) {
		AABB box = e.getBoundingBox();
		Touch t = touches.get(e.getId());
		boolean fresh = t == null;
		if (fresh) {
			t = new Touch();
			touches.put(e.getId(), t);
		}
		double vy = e.getDeltaMovement().y;
		double weight = Math.clamp(box.getXsize() * box.getYsize() * box.getZsize() / 0.648, 0.1, 3.0);
		float heavy = (float) Math.sqrt(weight);
		boolean onTop = false;
		JellyKind topKind = null;
		boolean landed = !fresh && t.lastVy < -0.15 && vy > t.lastVy + 0.05;
		int y0 = Mth.floor(box.minY - 0.02);
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX - 1.0E-4); x++) {
			for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ - 1.0E-4); z++) {
				for (int y = y0; y >= y0 - 1; y--) {
					BlockPos pos = new BlockPos(x, y, z);
					JellyKind kind = JellyKind.of(level.getBlockState(pos));
					if (kind == null) {
						continue;
					}
					double gap = box.minY - (y + kind.top);
					if (gap < -0.5) {
						continue;
					}
					if (gap <= 0.08) {
						onTop = true;
						topKind = kind;
						float depth = Math.min(kind.press * heavy, kind.maxDisplacement);
						pressFace(level, pos, 1, true, box.minX, box.maxX, box.minZ, box.maxZ, depth, 0.0F);
					}
					if (landed && gap < 0.7) {
						float speed = (float) Math.min(-t.lastVy * 20.0, 12.0) * kind.impact * heavy;
						pressFace(level, pos, 1, true, box.minX, box.maxX, box.minZ, box.maxZ, 0.0F, speed);
					}
					break;
				}
			}
		}
		if (!fresh && t.onTop && !onTop && vy > 0.0 && t.lastBox != null && t.lastKind != null && t.lastKind.lift > 0.0F) {
			AABB was = t.lastBox;
			int y = Mth.floor(was.minY - 0.02);
			for (int x = Mth.floor(was.minX); x <= Mth.floor(was.maxX - 1.0E-4); x++) {
				for (int z = Mth.floor(was.minZ); z <= Mth.floor(was.maxZ - 1.0E-4); z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (JellyKind.of(level.getBlockState(pos)) == JellyKind.HONEY) {
						pressFace(level, pos, 1, true, was.minX, was.maxX, was.minZ, was.maxZ, 0.0F, -t.lastKind.lift * 2.0F);
					}
				}
			}
		}
		touchSides(level, box, heavy);
		t.lastVy = vy;
		t.onTop = onTop;
		t.lastBox = box;
		t.lastKind = topKind;
		t.seen = tick;
	}

	private static void touchSides(ClientLevel level, AABB box, float heavy) {
		for (int axis = 0; axis <= 2; axis += 2) {
			for (int sign = -1; sign <= 1; sign += 2) {
				double edge = sign > 0 ? (axis == 0 ? box.maxX : box.maxZ) : (axis == 0 ? box.minX : box.minZ);
				int column = Mth.floor(edge + sign * 0.08);
				double crossMin = axis == 0 ? box.minZ : box.minX;
				double crossMax = axis == 0 ? box.maxZ : box.maxX;
				for (int c = Mth.floor(crossMin); c <= Mth.floor(crossMax - 1.0E-4); c++) {
					for (int y = Mth.floor(box.minY + 0.01); y <= Mth.floor(box.maxY - 0.01); y++) {
						BlockPos pos = axis == 0 ? new BlockPos(column, y, c) : new BlockPos(c, y, column);
						JellyKind kind = JellyKind.of(level.getBlockState(pos));
						if (kind == null) {
							continue;
						}
						double face = sign > 0 ? column + kind.inset : column + 1 - kind.inset;
						double gap = sign > 0 ? face - edge : edge - face;
						if (gap > 0.08 || gap < -0.3) {
							continue;
						}
						float depth = Math.min(kind.press * heavy * 0.5F, kind.maxDisplacement);
						if (axis == 0) {
							pressFace(level, pos, 0, false == sign > 0, box.minY, box.maxY, box.minZ, box.maxZ, depth, 0.0F);
						} else {
							pressFace(level, pos, 2, false == sign > 0, box.minX, box.maxX, box.minY, box.maxY, depth, 0.0F);
						}
					}
				}
			}
		}
	}

	private static void pressFace(ClientLevel level, BlockPos pos, int axis, boolean positive, double aMin, double aMax, double bMin, double bMax,
		float depth, float speed) {
		int a = axis == 0 ? 1 : 0;
		int b = axis == 2 ? 1 : 2;
		int[] base = {pos.getX() * LATTICE, pos.getY() * LATTICE, pos.getZ() * LATTICE};
		int[] at = new int[3];
		at[axis] = base[axis] + (positive ? LATTICE : 0);
		float inward = positive ? -1.0F : 1.0F;
		for (int i = 0; i <= LATTICE; i++) {
			for (int j = 0; j <= LATTICE; j++) {
				at[a] = base[a] + i;
				at[b] = base[b] + j;
				double ca = at[a] / (double) LATTICE;
				double cb = at[b] / (double) LATTICE;
				double da = Math.max(Math.max(aMin - ca, ca - aMax), 0.0);
				double db = Math.max(Math.max(bMin - cb, cb - bMax), 0.0);
				double out = Math.sqrt(da * da + db * db);
				if (out >= FOOT_REACH) {
					continue;
				}
				float w = (float) (1.0 - out / FOOT_REACH);
				w = w * w * (3.0F - 2.0F * w);
				Point p = wake(level, at[0], at[1], at[2]);
				if (p == null) {
					continue;
				}
				if (depth > 0.0F) {
					p.pressTo(axis, inward * depth * w);
				}
				if (speed != 0.0F) {
					float dv = inward * speed * w;
					if (axis == 0) {
						p.vx += dv;
					} else if (axis == 1) {
						p.vy += dv;
					} else {
						p.vz += dv;
					}
				}
			}
		}
	}

	private static void bulge(ClientLevel level) {
		Long2ObjectOpenHashMap<float[]> dents = new Long2ObjectOpenHashMap<>();
		for (Point p : points.values()) {
			if (Math.floorMod(p.ly, LATTICE) != 0 || p.dy >= 0.0F || Math.floorMod(p.lx, LATTICE) == 0 || Math.floorMod(p.lz, LATTICE) == 0) {
				continue;
			}
			int bx = Math.floorDiv(p.lx, LATTICE);
			int bz = Math.floorDiv(p.lz, LATTICE);
			int by = Math.floorDiv(p.ly, LATTICE) - 1;
			float[] d = dents.computeIfAbsent(BlockPos.asLong(bx, by, bz), k -> new float[2]);
			d[0] += -p.dy;
			d[1] += 1.0F;
		}
		for (var entry : dents.long2ObjectEntrySet()) {
			BlockPos pos = BlockPos.of(entry.getLongKey());
			JellyKind kind = JellyKind.of(level.getBlockState(pos));
			if (kind == null) {
				continue;
			}
			float amount = entry.getValue()[0] / 9.0F * kind.bulge;
			if (amount < 1.0E-4F) {
				continue;
			}
			for (int axis = 0; axis <= 2; axis += 2) {
				for (int sign = -1; sign <= 1; sign += 2) {
					BlockPos side = axis == 0 ? pos.offset(sign, 0, 0) : pos.offset(0, 0, sign);
					if (JellyKind.of(level.getBlockState(side)) != null) {
						continue;
					}
					int cross = axis == 0 ? 2 : 0;
					int[] base = {pos.getX() * LATTICE, pos.getY() * LATTICE, pos.getZ() * LATTICE};
					int[] at = new int[3];
					at[axis] = base[axis] + (sign > 0 ? LATTICE : 0);
					for (int i = 0; i <= LATTICE; i++) {
						for (int j = 1; j < LATTICE; j++) {
							at[cross] = base[cross] + i;
							at[1] = base[1] + j;
							float across = (float) Math.sin(Math.PI * i / LATTICE);
							float height = (float) Math.sin(Math.PI * j / LATTICE);
							Point p = wake(level, at[0], at[1], at[2]);
							if (p != null) {
								p.pressTo(axis, sign * amount * (0.3F + 0.7F * across) * height * 2.2F);
							}
						}
					}
				}
			}
		}
	}

	public static void poke(ClientLevel level, Vec3 at, Vec3 dir, float strength) {
		final double reach = 0.5;
		int x0 = Mth.floor((at.x - reach) * LATTICE);
		int y0 = Mth.floor((at.y - reach) * LATTICE);
		int z0 = Mth.floor((at.z - reach) * LATTICE);
		int x1 = Mth.ceil((at.x + reach) * LATTICE);
		int y1 = Mth.ceil((at.y + reach) * LATTICE);
		int z1 = Mth.ceil((at.z + reach) * LATTICE);
		for (int lx = x0; lx <= x1; lx++) {
			for (int ly = y0; ly <= y1; ly++) {
				for (int lz = z0; lz <= z1; lz++) {
					double dx = lx / (double) LATTICE - at.x;
					double dy = ly / (double) LATTICE - at.y;
					double dz = lz / (double) LATTICE - at.z;
					double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (d > reach) {
						continue;
					}
					Point p = wake(level, lx, ly, lz);
					if (p == null) {
						continue;
					}
					float f = (float) ((1.0 - d / reach) * (1.0 - d / reach)) * strength;
					p.vx += (float) dir.x * f;
					p.vy += (float) dir.y * f;
					p.vz += (float) dir.z * f;
				}
			}
		}
	}

	public static void blast(ClientLevel level, Vec3 centre, float power) {
		int reach = (int) Math.ceil(power * 2.0F + 1.0F);
		BlockPos c = BlockPos.containing(centre);
		for (BlockPos pos : BlockPos.betweenClosed(c.offset(-reach, -reach, -reach), c.offset(reach, reach, reach))) {
			if (JellyKind.of(level.getBlockState(pos)) == null) {
				continue;
			}
			for (int i = 0; i <= LATTICE; i += 2) {
				for (int j = 0; j <= LATTICE; j += 2) {
					for (int k = 0; k <= LATTICE; k += 2) {
						int lx = pos.getX() * LATTICE + i;
						int ly = pos.getY() * LATTICE + j;
						int lz = pos.getZ() * LATTICE + k;
						Vec3 at = new Vec3(lx / (double) LATTICE, ly / (double) LATTICE, lz / (double) LATTICE);
						Vec3 away = at.subtract(centre);
						double dist = away.length();
						if (dist > reach || dist < 1.0E-3) {
							continue;
						}
						Point p = wake(level, lx, ly, lz);
						if (p == null) {
							continue;
						}
						float push = (float) ((1.0 - dist / reach) * power * 3.0 / dist);
						p.vx += (float) away.x * push;
						p.vy += (float) away.y * push;
						p.vz += (float) away.z * push;
					}
				}
			}
		}
	}


	static void sample(BlockPos pos, float partial, float[] out) {
		int n = 0;
		for (int i = 0; i <= LATTICE; i++) {
			for (int j = 0; j <= LATTICE; j++) {
				for (int k = 0; k <= LATTICE; k++) {
					Point p = points.get(BlockPos.asLong(pos.getX() * LATTICE + i, pos.getY() * LATTICE + j, pos.getZ() * LATTICE + k));
					if (p == null) {
						out[n] = 0.0F;
						out[n + 1] = 0.0F;
						out[n + 2] = 0.0F;
					} else {
						out[n] = p.px + (p.dx - p.px) * partial;
						out[n + 1] = p.py + (p.dy - p.py) * partial;
						out[n + 2] = p.pz + (p.dz - p.pz) * partial;
					}
					n += 3;
				}
			}
		}
	}

	public static float sinkAt(ClientLevel level, double x, double feetY, double z, float partial) {
		if (points.isEmpty()) {
			return 0.0F;
		}
		int by = Mth.floor(feetY - 0.02);
		JellyKind kind = JellyKind.of(level.getBlockState(new BlockPos(Mth.floor(x), by, Mth.floor(z))));
		if (kind == null || feetY > by + kind.top + 0.1 || feetY < by + kind.top - kind.maxDisplacement - 0.1) {
			return 0.0F;
		}
		double gx = x * LATTICE;
		double gz = z * LATTICE;
		int ix = Mth.floor(gx);
		int iz = Mth.floor(gz);
		float fx = (float) (gx - ix);
		float fz = (float) (gz - iz);
		int ly = (by + 1) * LATTICE;
		float dy = 0.0F;
		for (int c = 0; c < 4; c++) {
			int cx = c & 1;
			int cz = c >> 1;
			Point p = points.get(BlockPos.asLong(ix + cx, ly, iz + cz));
			if (p != null) {
				float w = (cx == 1 ? fx : 1.0F - fx) * (cz == 1 ? fz : 1.0F - fz);
				dy += (p.py + (p.dy - p.py) * partial) * w;
			}
		}
		return Math.max(-dy, 0.0F);
	}
}
