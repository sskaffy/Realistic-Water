package me.skaffy.client.ice;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

final class IceBodies {
	static final int FLOATS = 12;
	private static final int[][] DIRS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
	private static final double TRAIL_LIFE = 400.0;
	private static final double TRAIL_FADE = 160.0;
	private static final int MAX_TRAILS = 512;

	private record Trail(Vec3 a, Vec3 b, long tick, int light) {
	}

	private record Last(Vec3 point, long tick) {
	}

	private final ArrayDeque<Trail> trails = new ArrayDeque<>();
	private final Map<Long, Last> lastPoints = new HashMap<>();
	private final Map<Integer, double[]> lastVel = new HashMap<>();
	private float[] verts = new float[FLOATS * 6 * 256];
	private int vertexCount;
	private int bodiesNearby;

	void clear() {
		this.trails.clear();
		this.lastPoints.clear();
		this.lastVel.clear();
		this.bodiesNearby = 0;
	}

	boolean wanted() {
		return this.bodiesNearby > 0 || !this.trails.isEmpty();
	}

	static boolean hasIce(RealisticBlockEntity e) {
		for (RigidBody.Cell c : e.body.cells) {
			if (c.payload instanceof BlockState s && IceWorld.isIce(s)) {
				return true;
			}
		}
		return false;
	}

	private static double reach(Minecraft mc) {
		return (mc.options.renderDistance().get() + 1) * 16.0;
	}

	void tick(Minecraft mc, ClientLevel level, Vec3 cam) {
		long now = level.getGameTime();
		while (!this.trails.isEmpty() && now - this.trails.getFirst().tick > TRAIL_LIFE) {
			this.trails.removeFirst();
		}
		this.lastPoints.values().removeIf(p -> now - p.tick > 20L);
		this.bodiesNearby = 0;
		Map<Integer, double[]> seen = new HashMap<>();
		Vector3d w = new Vector3d();
		BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
		for (RealisticBlockEntity e : RealisticBlockEntity.near(level, new AABB(cam, cam).inflate(reach(mc)))) {
			if (!hasIce(e)) {
				continue;
			}
			this.bodiesNearby++;
			RigidBody body = e.body;
			Vector3d vel = e.clientVelocity();
			this.knock(level, e, vel, seen);
			if (e.distanceToSqr(cam) > 48.0 * 48.0) {
				continue;
			}
			for (int i = 0; i < body.cells.size(); i++) {
				RigidBody.Cell c = body.cells.get(i);
				if (!(c.payload instanceof BlockState s) || !IceWorld.isIce(s) || body.cellAt(c.x, c.y - 1, c.z) != null) {
					continue;
				}
				body.toWorld(c.x + 0.5, c.y, c.z + 0.5, w);
				Vec3 p = new Vec3(w.x, w.y, w.z);
				long key = (long) e.getId() << 20 ^ i;
				Last old = this.lastPoints.put(key, new Last(p, now));
				below.set(Mth.floor(p.x), Mth.floor(p.y - 0.05), Mth.floor(p.z));
				boolean grounded = !level.getBlockState(below).getCollisionShape(level, below).isEmpty() && p.y - Math.floor(p.y) < 0.12;
				if (old == null || !grounded) {
					continue;
				}
				Vec3 d = p.subtract(old.point);
				double length = d.horizontalDistance();
				if (length < 0.015 || length > 1.5 || Math.abs(d.y) > 0.12) {
					continue;
				}
				if (this.trails.size() >= MAX_TRAILS) {
					this.trails.removeFirst();
				}
				BlockPos above = BlockPos.containing(p.x, p.y + 0.1, p.z);
				this.trails.addLast(new Trail(old.point, p, now, lightOf(level, above)));
			}
		}
		this.lastVel.keySet().retainAll(seen.keySet());
		this.lastVel.putAll(seen);
	}

	private void knock(ClientLevel level, RealisticBlockEntity e, Vector3d vel, Map<Integer, double[]> seen) {
		double[] last = this.lastVel.get(e.getId());
		double cooldown = last == null ? 0.0 : Math.max(last[3] - 1.0, 0.0);
		if (last != null && cooldown <= 0.0) {
			double dx = vel.x - last[0];
			double dy = vel.y - last[1];
			double dz = vel.z - last[2];
			double change = Math.sqrt(dx * dx + dy * dy + dz * dz);
			double before = Math.sqrt(last[0] * last[0] + last[1] * last[1] + last[2] * last[2]);
			double after = vel.length();
			if (change > 2.0 && after < before - 1.0) {
				float hit = (float) Math.min(change, 12.0);
				float pitch = 0.7F + level.getRandom().nextFloat() * 0.15F;
				level.playLocalSound(e.getX(), e.getY(), e.getZ(), SoundEvents.GLASS_HIT, SoundSource.BLOCKS, Math.clamp(0.3F + hit * 0.06F, 0.3F, 0.9F),
					pitch, false);
				if (hit > 5.0F) {
					level.playLocalSound(e.getX(), e.getY(), e.getZ(), SoundEvents.GLASS_FALL, SoundSource.BLOCKS, Math.clamp(hit * 0.05F, 0.2F, 0.6F),
						pitch * 0.85F, false);
				}
				cooldown = 8.0;
			}
		}
		seen.put(e.getId(), new double[]{vel.x, vel.y, vel.z, cooldown});
	}

	private static int lightOf(ClientLevel lvl, BlockPos pos) {
		int a = LightCoordsUtil.getLightCoords(lvl, pos);
		return ((a >> 4) & 15) * 16 + ((a >> 20) & 15);
	}

	private void put(float x, float y, float z, float nx, float ny, float nz, float px, float py, float pz, int light, float film) {
		if ((this.vertexCount + 1) * FLOATS > this.verts.length) {
			this.verts = java.util.Arrays.copyOf(this.verts, this.verts.length * 2);
		}
		int o = this.vertexCount * FLOATS;
		float[] v = this.verts;
		v[o] = x;
		v[o + 1] = y;
		v[o + 2] = z;
		v[o + 3] = nx;
		v[o + 4] = ny;
		v[o + 5] = nz;
		v[o + 6] = px;
		v[o + 7] = py;
		v[o + 8] = pz;
		v[o + 9] = light;
		v[o + 10] = film;
		v[o + 11] = 0.0F;
		this.vertexCount++;
	}

	float[] build(Minecraft mc, ClientLevel level, Vec3 cam, float partial) {
		this.vertexCount = 0;
		int bx = Mth.floor(cam.x);
		int by = Mth.floor(cam.y);
		int bz = Mth.floor(cam.z);
		if (this.bodiesNearby > 0) {
			this.bodyCells(level, cam, partial, bx, by, bz, reach(mc));
		}
		this.trailQuads(level, cam, bx, by, bz);
		return this.verts;
	}

	int vertexCount() {
		return this.vertexCount;
	}

	private void bodyCells(ClientLevel level, Vec3 cam, float partial, int bx, int by, int bz, double reach) {
		Vector3d[] corner = {new Vector3d(), new Vector3d(), new Vector3d(), new Vector3d()};
		Vector3d n = new Vector3d();
		Vector3d centre = new Vector3d();
		for (RealisticBlockEntity e : RealisticBlockEntity.near(level, new AABB(cam, cam).inflate(reach))) {
			if (!hasIce(e)) {
				continue;
			}
			RigidBody body = e.body;
			e.syncClientBody(e.renderPosition(partial), e.renderRotation(partial));
			for (RigidBody.Cell c : body.cells) {
				if (!(c.payload instanceof BlockState s) || !IceWorld.isIce(s)) {
					continue;
				}
				body.toWorld(c.x + 0.5, c.y + 0.5, c.z + 0.5, centre);
				int light = lightOf(level, BlockPos.containing(centre.x, centre.y, centre.z));
				for (int d = 0; d < 6; d++) {
					int[] dir = DIRS[d];
					RigidBody.Cell o = body.cellAt(c.x + dir[0], c.y + dir[1], c.z + dir[2]);
					if (o != null && o.payload instanceof BlockState os && (IceWorld.isIce(os) || os.isSolidRender())) {
						continue;
					}
					double[][] q = faceCorners(d, c.x, c.y, c.z);
					for (int k = 0; k < 4; k++) {
						body.toWorld(q[k][0], q[k][1], q[k][2], corner[k]);
					}
					body.dirToWorld(dir[0], dir[1], dir[2], n);
					int[] order = {0, 1, 2, 0, 2, 3};
					for (int k : order) {
						Vector3d p = corner[k];
						this.put((float) (p.x - bx), (float) (p.y - by), (float) (p.z - bz), (float) n.x, (float) n.y, (float) n.z,
							0.0F, 0.0F, 0.0F, light, 0.0F);
						int at = (this.vertexCount - 1) * FLOATS;
						this.verts[at + 6] = (float) Math.floorMod((long) Math.floor(q[k][0]), 1024L) + (float) (q[k][0] - Math.floor(q[k][0]));
						this.verts[at + 7] = (float) Math.floorMod((long) Math.floor(q[k][1]), 1024L) + (float) (q[k][1] - Math.floor(q[k][1]));
						this.verts[at + 8] = (float) Math.floorMod((long) Math.floor(q[k][2]), 1024L) + (float) (q[k][2] - Math.floor(q[k][2]));
					}
				}
			}
		}
	}

	private static double[][] faceCorners(int d, int x, int y, int z) {
		double x0 = x;
		double y0 = y;
		double z0 = z;
		double x1 = x + 1.0;
		double y1 = y + 1.0;
		double z1 = z + 1.0;
		return switch (d) {
			case 0 -> new double[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
			case 1 -> new double[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
			case 2 -> new double[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}};
			case 3 -> new double[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
			case 4 -> new double[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
			default -> new double[][]{{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}};
		};
	}

	private void trailQuads(ClientLevel level, Vec3 cam, int bx, int by, int bz) {
		long now = level.getGameTime();
		for (Trail t : this.trails) {
			if (t.a.distanceToSqr(cam) > 32.0 * 32.0) {
				continue;
			}
			Vec3 d = t.b.subtract(t.a);
			double length = d.horizontalDistance();
			if (length < 1.0e-5) {
				continue;
			}
			double sx = -d.z / length * 0.43;
			double sz = d.x / length * 0.43;
			float fade = (float) Math.clamp((TRAIL_LIFE - (now - t.tick)) / TRAIL_FADE, 0.0, 1.0);
			if (fade <= 0.0F) {
				continue;
			}
			Vec3[] q = {t.a.add(-sx, 0.0, -sz), t.a.add(sx, 0.0, sz), t.b.add(sx, 0.0, sz), t.b.add(-sx, 0.0, -sz)};
			for (int k : new int[]{0, 1, 2, 0, 2, 3}) {
				Vec3 p = q[k];
				this.put((float) (p.x - bx), (float) (p.y + 0.004 - by), (float) (p.z - bz), 0.0F, 1.0F, 0.0F,
					(float) Math.floorMod((long) Math.floor(p.x), 1024L) + (float) (p.x - Math.floor(p.x)), 0.0F,
					(float) Math.floorMod((long) Math.floor(p.z), 1024L) + (float) (p.z - Math.floor(p.z)), t.light, fade);
			}
		}
	}
}
