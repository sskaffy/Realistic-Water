package me.skaffy.client.water;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.ocean.OceanWaves;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class OceanSim {
	private static final int QUANTUM = 2;
	private static final int MAX_ZONES = 4;
	private static final double ENTITY_RANGE = 48.0;
	private static final float MAX_LINGER = 4.0F;
	private static final double TNT_REACH = 4.0;
	private static final int FOAM_LEFT = 150;
	private static final float FOAM_LINGER = 15.0F;

	private record Disturbance(double x0, double z0, double x1, double z1, int seaY, int floorY, int topY, double until) {
	}

	private final WaterWorld world;
	private final OceanSurfaces surfaces;
	private final List<Disturbance> disturbances = new ArrayList<>();
	final List<WaterRegion> zones = new ArrayList<>();

	OceanSim(WaterWorld world, OceanSurfaces surfaces) {
		this.world = world;
		this.surfaces = surfaces;
	}

	static double now() {
		return System.nanoTime() / 1.0e9;
	}

	void clear() {
		this.disturbances.clear();
		this.zones.clear();
	}

	private boolean disturb(double x0, double z0, double x1, double z1, int seaY, int floorY, int topY) {
		float r = Math.max(WaterSettings.oceanSimRadius, 1);
		this.disturbances.add(new Disturbance(x0 - r, z0 - r, x1 + r, z1 + r, seaY, floorY, topY, now() + Math.max(WaterSettings.oceanSettleTime, 0.0F)));
		return true;
	}

	boolean disturbAt(AABB box) {
		return this.disturbAt(box, false);
	}

	boolean disturbAt(AABB box, boolean followDown) {
		int top = this.surfaces.restTop(Mth.floor((box.minX + box.maxX) * 0.5), Mth.floor((box.minZ + box.maxZ) * 0.5));
		return this.disturbAt(box, top, followDown, Integer.MIN_VALUE);
	}

	private boolean disturbAt(AABB box, int top, boolean followDown, int ceiling) {
		if (!OceanSurfaces.hasTop(top)) {
			return false;
		}
		int depth = Math.max(WaterSettings.oceanSimDepth, 1);
		double sea = top + OceanWaves.REST_HEIGHT;
		if (box.minY > sea + 1.5 || !followDown && box.maxY < sea - depth) {
			return false;
		}
		int floor = top - depth + 1;
		if (followDown) {
			floor = Math.min(floor, Math.floorDiv(Mth.floor(box.minY) - 2, QUANTUM) * QUANTUM);
		}
		int topY = Math.max(top + Math.max(WaterSettings.oceanSimHeadroom, 1), ceiling);
		return this.disturb(box.minX, box.minZ, box.maxX, box.maxZ, top, floor, topY);
	}

	private void disturbPump(WaterWorld.Pump pump) {
		Vec3 c = pump.center();
		Vec3 d = pump.dir();
		double speed = WaterWorld.pumpSpeed(pump);
		double reach = 2.0 + speed * 0.5;
		AABB box = new AABB(c, c).inflate(1.0).expandTowards(d.x * reach, 0.0, d.z * reach).expandTowards(-d.x, -d.y, -d.z);
		int ceiling = Integer.MIN_VALUE;
		if (d.y > 0.5) {
			double rise = speed * speed / (2.0 * Math.max(WaterSettings.gravity, 1.0F));
			ceiling = Mth.ceil(pump.outlet().y + rise) + 1;
		}
		int top = this.surfaces.restTop(Mth.floor(c.x), Mth.floor(c.z));
		if (!OceanSurfaces.hasTop(top)) {
			double best = Double.MAX_VALUE;
			for (int z = Mth.floor(box.minZ) - 1; z <= Mth.floor(box.maxZ) + 1; z++) {
				for (int x = Mth.floor(box.minX) - 1; x <= Mth.floor(box.maxX) + 1; x++) {
					int t = this.surfaces.restTop(x, z);
					double dist = sq(x + 0.5 - c.x) + sq(z + 0.5 - c.z);
					if (OceanSurfaces.hasTop(t) && dist < best) {
						best = dist;
						top = t;
					}
				}
			}
		}
		this.disturbAt(box, top, true, ceiling);
	}

	void tick(Minecraft mc, ClientLevel level) {
		if (mc.player == null) {
			return;
		}
		Vec3 cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		AABB range = new AABB(cam, cam).inflate(ENTITY_RANGE);
		for (Entity e : level.getEntities((Entity) null, range, e -> !e.isSpectator())) {
			if (e instanceof PrimedTnt tnt) {
				this.disturbAt(tnt.getBoundingBox().inflate(TNT_REACH), true);
			} else if (e instanceof Player && !WaterSettings.oceanSimUnderwater) {
				AABB box = e.getBoundingBox();
				int top = this.surfaces.restTop(Mth.floor(e.getX()), Mth.floor(e.getZ()));
				if (OceanSurfaces.hasTop(top) && box.maxY >= top + OceanWaves.REST_HEIGHT - 0.3) {
					this.disturbAt(box, top, false, Integer.MIN_VALUE);
				}
			} else if (this.movesSea(mc, e)) {
				this.disturbAt(e.getBoundingBox(), e instanceof Player);
			}
		}
		for (WaterWorld.Pump pump : this.world.pumps(level)) {
			if (pump.center().distanceToSqr(cam) < ENTITY_RANGE * ENTITY_RANGE) {
				this.disturbPump(pump);
			}
		}
	}

	private boolean movesSea(Minecraft mc, Entity e) {
		if (e instanceof Player || e instanceof AbstractBoat || e instanceof RealisticBlockEntity) {
			return true;
		}
		if (e instanceof WaterAnimal) {
			return false;
		}
		double horizontal = Math.abs(e.getX() - e.xo) + Math.abs(e.getZ() - e.zo);
		return horizontal > 0.15 || Math.abs(e.getY() - e.yo) > 0.3;
	}

	void explosion(Vec3 center, float radius) {
		this.disturbAt(new AABB(center, center).inflate(radius), true);
	}

	private static int[] quantize(Disturbance d) {
		return new int[]{
			Math.floorDiv(Mth.floor(d.x0), QUANTUM) * QUANTUM, Math.floorDiv(Mth.floor(d.z0), QUANTUM) * QUANTUM,
			Math.ceilDiv(Mth.ceil(d.x1), QUANTUM) * QUANTUM, Math.ceilDiv(Mth.ceil(d.z1), QUANTUM) * QUANTUM, d.seaY, d.floorY, d.topY
		};
	}

	private static boolean overlap(int[] a, int[] b) {
		return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3] && Math.abs(a[4] - b[4]) <= 2;
	}

	void update(ClientLevel level, float frameDt, Vec3 cam) {
		double now = now();
		this.disturbances.removeIf(d -> d.until < now);
		List<int[]> clusters = new ArrayList<>();
		for (Disturbance d : this.disturbances) {
			int[] box = quantize(d);
			boolean merged = true;
			while (merged) {
				merged = false;
				for (int k = 0; k < clusters.size(); k++) {
					int[] c = clusters.get(k);
					if (overlap(c, box)) {
						box = new int[]{Math.min(c[0], box[0]), Math.min(c[1], box[1]), Math.max(c[2], box[2]), Math.max(c[3], box[3]), c[4],
							Math.min(c[5], box[5]), Math.max(c[6], box[6])};
						clusters.remove(k);
						merged = true;
						break;
					}
				}
			}
			clusters.add(box);
		}
		clusters.sort(java.util.Comparator.comparingDouble(c -> sq((c[0] + c[2]) * 0.5 - cam.x) + sq((c[1] + c[3]) * 0.5 - cam.z)));
		while (clusters.size() > MAX_ZONES) {
			clusters.removeLast();
		}

		OceanWaves.Waves waves = OceanWaves.at(level.getGameTime(), 0.0F, strength(level));
		List<WaterRegion> claimed = new ArrayList<>();
		for (int[] c : clusters) {
			WaterRegion r = null;
			for (WaterRegion z : this.zones) {
				OceanZone o = z.ocean;
				if (!claimed.contains(z) && o.x0 < c[2] && c[0] < o.x1 && o.z0 < c[3] && c[1] < o.z1 && Math.abs(o.seaY - c[4]) <= 2) {
					r = z;
					break;
				}
			}
			if (r == null) {
				if (this.zones.size() >= MAX_ZONES) {
					continue;
				}
				OceanZone zone = new OceanZone(c[4], c[5], c[6]);
				r = this.world.addOceanRegion(zone);
				this.zones.add(r);
			}
			claimed.add(r);
			r.ocean.quietSeconds = 0.0F;
			if (c[6] > r.ocean.topY) {
				r.ocean.topY = c[6];
				r.ocean.solidsStale = true;
			}
			this.deepen(r, c[5]);
			this.resize(level, r, c[0], c[1], c[2], c[3], waves);
		}

		List<WaterRegion> snapped = new ArrayList<>();
		for (WaterRegion r : this.zones) {
			if (claimed.contains(r)) {
				continue;
			}
			OceanZone o = r.ocean;
			o.quietSeconds += frameDt;
			boolean foamy = r.lastWhitewaterCount > FOAM_LEFT && o.quietSeconds < FOAM_LINGER;
			if (!foamy && (r.lastOceanDeviation <= WaterSettings.oceanSnapDeviation || o.quietSeconds > MAX_LINGER)) {
				snapped.add(r);
			}
		}
		for (WaterRegion r : snapped) {
			this.zones.remove(r);
			this.world.removeOceanRegion(r);
		}
	}

	private static double sq(double v) {
		return v * v;
	}

	static float strength(ClientLevel level) {
		return OceanMode.waveHeight() * OceanWaves.weather(level);
	}

	private void resize(ClientLevel level, WaterRegion r, int x0, int z0, int x1, int z1, OceanWaves.Waves waves) {
		OceanZone o = r.ocean;
		if (o.x0 == x0 && o.z0 == z0 && o.x1 == x1 && o.z1 == z1 && !o.filled.isEmpty()) {
			return;
		}
		LongArrayList left = new LongArrayList();
		for (LongIterator it = o.filled.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			int x = (int) (key >> 32);
			int z = (int) key;
			if (x < x0 || x >= x1 || z < z0 || z >= z1) {
				left.add(key);
			}
		}
		for (long key : left) {
			o.filled.remove(key);
		}
		for (int[] strip : strips(o.x0, o.z0, o.x1, o.z1, x0, z0, x1, z1)) {
			r.removals.add(new WaterRegion.Removal(strip[0], o.floorY - 2, strip[1], strip[2], o.topY + 16, strip[3]));
		}
		o.x0 = x0;
		o.z0 = z0;
		o.x1 = x1;
		o.z1 = z1;
		o.solidsStale = true;
		for (int z = z0; z < z1; z++) {
			for (int x = x0; x < x1; x++) {
				long key = (long) x << 32 | (z & 0xFFFFFFFFL);
				if (!o.filled.add(key)) {
					continue;
				}
				int top = this.surfaces.restTop(x, z);
				if (!OceanSurfaces.hasTop(top) || Math.abs(top - o.seaY) > 2 || top < o.floorY || top > o.topY) {
					continue;
				}
				r.addEmitter(new WaterRegion.Emitter(x, o.floorY, z, x + 1, top + OceanWaves.REST_HEIGHT, z + 1, 0.0F, 0.0F, 0.0F));
			}
		}
		r.wake(0);
	}

	private void deepen(WaterRegion r, int floorY) {
		OceanZone o = r.ocean;
		if (floorY == o.floorY) {
			return;
		}
		if (floorY < o.floorY) {
			for (LongIterator it = o.filled.iterator(); it.hasNext(); ) {
				long key = it.nextLong();
				int x = (int) (key >> 32);
				int z = (int) key;
				int top = this.surfaces.restTop(x, z);
				if (OceanSurfaces.hasTop(top) && Math.abs(top - o.seaY) <= 2 && top >= o.floorY) {
					r.addEmitter(new WaterRegion.Emitter(x, floorY, z, x + 1, o.floorY, z + 1, 0.0F, 0.0F, 0.0F));
				}
			}
		} else {
			r.removals.add(new WaterRegion.Removal(o.x0, o.floorY - 4, o.z0, o.x1, floorY, o.z1));
		}
		o.floorY = floorY;
		o.solidsStale = true;
	}

	private static List<int[]> strips(int ox0, int oz0, int ox1, int oz1, int x0, int z0, int x1, int z1) {
		List<int[]> out = new ArrayList<>();
		if (ox1 <= ox0 || oz1 <= oz0) {
			return out;
		}
		if (ox0 < x0) {
			out.add(new int[]{ox0, oz0, Math.min(ox1, x0), oz1});
		}
		if (ox1 > x1) {
			out.add(new int[]{Math.max(ox0, x1), oz0, ox1, oz1});
		}
		int mx0 = Math.max(ox0, x0);
		int mx1 = Math.min(ox1, x1);
		if (mx1 > mx0) {
			if (oz0 < z0) {
				out.add(new int[]{mx0, oz0, mx1, Math.min(oz1, z0)});
			}
			if (oz1 > z1) {
				out.add(new int[]{mx0, Math.max(oz0, z1), mx1, oz1});
			}
		}
		return out;
	}

	boolean cameraInZone(Vec3 cam) {
		for (WaterRegion r : this.zones) {
			if (r.ocean.containsPoint(cam.x, cam.y, cam.z)) {
				return true;
			}
		}
		return false;
	}
}
