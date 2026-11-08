package me.skaffy.client.jelly;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.RealisticWater;
import me.skaffy.block.WobblyBlocks;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.water.WaterSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class JellyWorld {
	private static final LongOpenHashSet blocks = new LongOpenHashSet();
	private static final JellyMesher mesher = new JellyMesher();
	private static @Nullable ClientLevel level;
	private static @Nullable JellyRenderer renderer;
	private static boolean wasOn = true;
	private static boolean failedSaid;

	private JellyWorld() {
	}

	public static boolean on() {
		return WaterSettings.wobblyBlocks;
	}

	private static void use(ClientLevel current) {
		if (level != current) {
			clear();
			level = current;
		}
	}

	public static void clear() {
		blocks.clear();
		JellyField.clear();
		level = null;
	}

	public static void onChunkLoad(ClientLevel current, LevelChunk chunk) {
		use(current);
		LevelChunkSection[] sections = chunk.getSections();
		for (int s = 0; s < sections.length; s++) {
			LevelChunkSection section = sections[s];
			if (!section.maybeHas(WobblyBlocks::isWobbly)) {
				continue;
			}
			int y0 = chunk.getMinY() + s * 16;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (WobblyBlocks.isWobbly(section.getBlockState(x, y, z))) {
							blocks.add(BlockPos.asLong(chunk.getPos().getMinBlockX() + x, y0 + y, chunk.getPos().getMinBlockZ() + z));
						}
					}
				}
			}
		}
	}

	public static void onChunkUnload(ClientLevel current, LevelChunk chunk) {
		if (current != level) {
			return;
		}
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		blocks.removeIf(p -> BlockPos.getX(p) >> 4 == cx && BlockPos.getZ(p) >> 4 == cz);
	}

	public static void onBlockChanged(ClientLevel current, BlockPos pos, BlockState state) {
		use(current);
		if (WobblyBlocks.isWobbly(state)) {
			blocks.add(pos.asLong());
		} else {
			blocks.remove(pos.asLong());
		}
		JellyField.onBlockChanged(current, pos);
	}

	public static void tick(Minecraft mc) {
		boolean on = on();
		if (on != wasOn) {
			wasOn = on;
			for (long key : blocks) {
				int x = BlockPos.getX(key);
				int y = BlockPos.getY(key);
				int z = BlockPos.getZ(key);
				mc.levelExtractor.setBlocksDirty(x, y, z, x, y, z);
			}
		}
		if (mc.level == null) {
			if (level != null) {
				clear();
			}
			return;
		}
		use(mc.level);
		if (!on || mc.isPaused()) {
			return;
		}
		Entity view = mc.getCameraEntity();
		if (view == null) {
			return;
		}
		if (!blocks.isEmpty() || JellyField.pointCount() > 0) {
			JellyField.tick(mc.level, view.position());
		}
		bodiesNearby = 0;
		AABB around = new AABB(view.position(), view.position()).inflate(Math.max(8, WaterSettings.wobblyRange));
		for (me.skaffy.realistic.RealisticBlockEntity e : me.skaffy.realistic.RealisticBlockEntity.near(mc.level, around)) {
			for (me.skaffy.physics.RigidBody.Cell c : e.body.cells) {
				if (c.payload instanceof BlockState s && WobblyBlocks.isWobbly(s)) {
					bodiesNearby++;
					break;
				}
			}
		}
		jiggles.keySet().removeIf(id -> mc.level.getEntity(id) == null);
		detectLandings(mc.level, view.position());
		shots(mc.level, view.position());
	}


	private static final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<double[]> shots = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
	private static final java.util.Map<Integer, double[]> kicks = new java.util.HashMap<>();

	private static double hitWeight(Entity e) {
		if (e instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow) {
			return 1.0;
		}
		if (e instanceof net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile) {
			return 0.45;
		}
		return 0.7;
	}

	private static void shots(ClientLevel lvl, Vec3 cam) {
		if (!on() || blocks.isEmpty() && bodiesNearby == 0) {
			shots.clear();
			return;
		}
		double range = Math.max(8, WaterSettings.wobblyRange);
		it.unimi.dsi.fastutil.ints.IntOpenHashSet seen = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
		for (Entity e : lvl.getEntities((Entity) null, new AABB(cam, cam).inflate(range), e -> e instanceof net.minecraft.world.entity.projectile.Projectile)) {
			seen.add(e.getId());
			Vec3 now = e.position();
			Vec3 v = e.getDeltaMovement();
			double[] s = shots.get(e.getId());
			if (s == null) {
				shots.put(e.getId(), new double[]{now.x, now.y, now.z, v.x, v.y, v.z, hitWeight(e), 0.0});
				continue;
			}
			if (s[7] == 0.0) {
				Vec3 prev = new Vec3(s[0], s[1], s[2]);
				Vec3 flight = now.subtract(prev);
				if (flight.lengthSqr() < 1.0E-6) {
					flight = new Vec3(s[3], s[4], s[5]);
				}
				if (flight.lengthSqr() > 1.0E-6 && strike(lvl, prev, now.add(flight.normalize().scale(0.35)), new Vec3(s[3], s[4], s[5]), s[6])) {
					s[7] = 1.0;
				}
			}
			s[0] = now.x;
			s[1] = now.y;
			s[2] = now.z;
			if (v.lengthSqr() > 1.0E-6) {
				s[3] = v.x;
				s[4] = v.y;
				s[5] = v.z;
			}
		}
		shots.int2ObjectEntrySet().removeIf(entry -> {
			if (seen.contains(entry.getIntKey())) {
				return false;
			}
			double[] s = entry.getValue();
			if (s[7] == 0.0) {
				Vec3 prev = new Vec3(s[0], s[1], s[2]);
				Vec3 vel = new Vec3(s[3], s[4], s[5]);
				strike(lvl, prev, prev.add(vel.scale(1.5)), vel, s[6]);
			}
			return true;
		});
	}

	private static boolean strike(ClientLevel lvl, Vec3 from, Vec3 to, Vec3 velocity, double weight) {
		double speed = velocity.length() * 20.0;
		if (speed < 0.5) {
			return false;
		}
		Vec3 dir = velocity.normalize();
		float strength = (float) Math.min(speed * weight * 0.25, 15.0);
		Vec3 d = to.subtract(from);
		int steps = Math.clamp((int) Math.ceil(d.length() / 0.05), 1, 120);
		List<me.skaffy.realistic.RealisticBlockEntity> bodies = bodiesNearby > 0
			? me.skaffy.realistic.RealisticBlockEntity.near(lvl, new AABB(from, to).inflate(1.0)) : List.of();
		org.joml.Vector3d g = new org.joml.Vector3d();
		for (int i = 0; i <= steps; i++) {
			Vec3 p = from.add(d.scale(i / (double) steps));
			BlockPos at = BlockPos.containing(p);
			JellyKind kind = JellyKind.of(lvl.getBlockState(at));
			if (kind != null && p.y - at.getY() <= kind.top && Math.min(p.x - at.getX(), at.getX() + 1 - p.x) >= kind.inset
				&& Math.min(p.z - at.getZ(), at.getZ() + 1 - p.z) >= kind.inset) {
				JellyField.poke(lvl, p, dir, strength);
				return true;
			}
			for (me.skaffy.realistic.RealisticBlockEntity e : bodies) {
				e.body.toGrid(p.x, p.y, p.z, g);
				var cell = e.body.cellAt(Mth.floor(g.x), Mth.floor(g.y), Mth.floor(g.z));
				if (cell != null && cell.payload instanceof BlockState s && JellyKind.of(s) != null) {
					kickBody(e.getId(), dir.scale(strength * 0.12));
					return true;
				}
			}
		}
		return false;
	}

	private static void kickBody(int id, Vec3 v) {
		kicks.merge(id, new double[]{v.x, v.y, v.z}, (a, b) -> new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
	}

	private static final java.util.Map<Integer, Double> landings = new java.util.HashMap<>();
	private static final java.util.Map<Integer, Double> lastVy = new java.util.HashMap<>();

	private static void detectLandings(ClientLevel lvl, Vec3 cam) {
		if (bodiesNearby == 0) {
			lastVy.clear();
			return;
		}
		AABB around = new AABB(cam, cam).inflate(Math.max(8, WaterSettings.wobblyRange));
		java.util.Map<Integer, Double> now = new java.util.HashMap<>();
		org.joml.Vector3d g = new org.joml.Vector3d();
		for (me.skaffy.realistic.RealisticBlockEntity e : me.skaffy.realistic.RealisticBlockEntity.near(lvl, around)) {
			for (Entity other : lvl.getEntities(e, e.getBoundingBox().inflate(1.0), o -> !(o instanceof me.skaffy.realistic.RealisticBlockEntity) && !o.isSpectator())) {
				double vy = other.getDeltaMovement().y;
				now.put(other.getId(), vy);
				Double before = lastVy.get(other.getId());
				if (before == null || before > -0.25 || vy < before + 0.1) {
					continue;
				}
				e.body.toGrid(other.getX(), other.getBoundingBox().minY - 0.05, other.getZ(), g);
				var cell = e.body.cellAt(Mth.floor(g.x), Mth.floor(g.y), Mth.floor(g.z));
				if (cell != null && cell.payload instanceof BlockState s) {
					JellyKind kind = JellyKind.of(s);
					if (kind != null) {
						AABB box = other.getBoundingBox();
						double heavy = Math.sqrt(Math.clamp(box.getXsize() * box.getYsize() * box.getZsize() / 0.648, 0.1, 3.0));
						landings.merge(e.getId(), Math.min(-before * 20.0, 12.0) * kind.impact * heavy * 0.12, Double::sum);
					}
				}
			}
		}
		lastVy.clear();
		lastVy.putAll(now);
	}

	private static java.util.List<double[]> feetOn(ClientLevel lvl, me.skaffy.realistic.RealisticBlockEntity e) {
		java.util.List<double[]> feet = new java.util.ArrayList<>();
		org.joml.Vector3d g = new org.joml.Vector3d();
		for (Entity other : lvl.getEntities(e, e.getBoundingBox().inflate(1.0), o -> !(o instanceof me.skaffy.realistic.RealisticBlockEntity) && !o.isSpectator())) {
			AABB box = other.getBoundingBox();
			e.body.toGrid(other.getX(), box.minY, other.getZ(), g);
			var cell = e.body.cellAt(Mth.floor(g.x), Mth.floor(g.y - 0.05), Mth.floor(g.z));
			if (cell == null || !(cell.payload instanceof BlockState s)) {
				continue;
			}
			JellyKind kind = JellyKind.of(s);
			if (kind == null || Math.abs(g.y - (cell.y + 1.0)) > 0.2) {
				continue;
			}
			double heavy = Math.sqrt(Math.clamp(box.getXsize() * box.getYsize() * box.getZsize() / 0.648, 0.1, 3.0));
			feet.add(new double[]{g.x, g.y, g.z, Math.min(kind.press * heavy, kind.maxDisplacement), Math.max(box.getXsize(), box.getZsize()) * 0.5});
		}
		return feet;
	}

	private static int bodiesNearby;

	private static final java.util.Map<Integer, double[]> jiggles = new java.util.HashMap<>();

	public static boolean drawsBodyCell(BlockState state) {
		return on() && WobblyBlocks.isWobbly(state);
	}

	private static double[] jiggle(me.skaffy.realistic.RealisticBlockEntity e) {
		org.joml.Vector3d vel = e.clientVelocity();
		long now = System.nanoTime();
		double[] j = jiggles.computeIfAbsent(e.getId(), k -> new double[]{vel.x, vel.y, vel.z, 0, 0, 0, 0, 0, 0, now, 0, 0});
		double dt = Math.clamp((now - (long) j[9]) / 1.0e9, 0.0, 0.05);
		j[9] = now;
		if (dt <= 0.0) {
			return j;
		}
		double soft = Math.max(WaterSettings.wobblySoftness, 0.3);
		double k = 140.0 / soft;
		double c = 7.0;
		double[] v = {vel.x, vel.y, vel.z};
		for (int a = 0; a < 3; a++) {
			double acc = (v[a] - j[a]) / dt;
			j[a] = v[a];
			double force = -k * j[3 + a] - c * j[6 + a] - acc * 0.9;
			j[6 + a] += force * dt;
			j[3 + a] = Math.clamp(j[3 + a] + j[6 + a] * dt, -0.18, 0.18);
		}
		double[] kick = kicks.remove(e.getId());
		if (kick != null) {
			j[6] += kick[0];
			j[7] += kick[1];
			j[8] += kick[2];
			j[11] += Math.sqrt(kick[0] * kick[0] + kick[1] * kick[1] + kick[2] * kick[2]) * 0.6;
		}
		Double hit = landings.remove(e.getId());
		if (hit != null) {
			j[11] += hit;
		}
		double ks = 180.0 / soft;
		j[11] += (-ks * j[10] - 5.0 * j[11]) * dt;
		j[10] = Math.clamp(j[10] + j[11] * dt, -0.25, 0.3);
		return j;
	}

	private static void bodyCells(ClientLevel lvl, Vec3 cam, double reach, float partial, int bx, int by, int bz) {
		AABB around = new AABB(cam, cam).inflate(reach);
		org.joml.Vector3d w = new org.joml.Vector3d();
		org.joml.Vector3d n = new org.joml.Vector3d();
		for (me.skaffy.realistic.RealisticBlockEntity e : me.skaffy.realistic.RealisticBlockEntity.near(lvl, around)) {
			me.skaffy.physics.RigidBody body = e.body;
			boolean any = false;
			for (me.skaffy.physics.RigidBody.Cell c : body.cells) {
				if (c.payload instanceof BlockState s && WobblyBlocks.isWobbly(s)) {
					any = true;
					break;
				}
			}
			if (!any) {
				continue;
			}
			e.syncClientBody(e.renderPosition(partial), e.renderRotation(partial));
			double[] lag = jiggle(e);
			java.util.List<double[]> feet = feetOn(lvl, e);
			for (me.skaffy.physics.RigidBody.Cell c : body.cells) {
				if (!(c.payload instanceof BlockState s)) {
					continue;
				}
				JellyKind kind = JellyKind.of(s);
				if (kind == null) {
					continue;
				}
				int exposed = 0;
				int[][] dirs = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
				for (int d = 0; d < 6; d++) {
					me.skaffy.physics.RigidBody.Cell o = body.cellAt(c.x + dirs[d][0], c.y + dirs[d][1], c.z + dirs[d][2]);
					if (o == null || !(o.payload instanceof BlockState os) || JellyKind.of(os) != kind) {
						exposed |= 1 << d;
					}
				}
				body.toWorld(c.x + 0.5, c.y + 0.5, c.z + 0.5, w);
				BlockPos at = BlockPos.containing(w.x, w.y, w.z);
				int first = mesher.vertexCount;
				mesher.block(new BlockPos(c.x, c.y, c.z), kind, exposed, false, true, 0.0F, 0.0F, 0.0F, lightOf(lvl, at), 0.0F, null);
				float give = kind == JellyKind.HONEY ? 0.6F : 1.0F;
				for (int vtx = first; vtx < mesher.vertexCount; vtx++) {
					int o = vtx * JellyMesher.FLOATS;
					float[] v = mesher.vertices;
					float ly = v[o + 1];
					double gx = c.x + v[o];
					double gy = c.y + ly;
					double gz = c.z + v[o + 2];
					double up = Math.clamp(ly, 0.0F, 1.0F);
					double dent = 0.0;
					for (double[] f : feet) {
						if (Math.floor(f[1] - 0.05) != c.y) {
							continue;
						}
						double dx = Math.max(Math.abs(gx - f[0]) - f[4], 0.0);
						double dz = Math.max(Math.abs(gz - f[2]) - f[4], 0.0);
						double near = 1.0 - Math.sqrt(dx * dx + dz * dz) / 0.35;
						if (near > 0.0) {
							dent = Math.max(dent, f[3] * near * near * (3.0 - 2.0 * near));
						}
					}
					double squash = lag[10] * give;
					double sink = (dent + squash) * up * up;
					double swell = (dent * kind.bulge + squash * 0.5) * Math.sin(Math.PI * up);
					gy -= sink;
					gx += (v[o] - 0.5) * 2.0 * swell * Math.abs(v[o + 3]);
					gz += (v[o + 2] - 0.5) * 2.0 * swell * Math.abs(v[o + 5]);
					body.toWorld(gx, gy, gz, w);
					body.dirToWorld(v[o + 3], v[o + 4], v[o + 5], n);
					double sway = Math.clamp((c.y + ly - body.com.y + 1.0) * 0.5, 0.0, 1.5) * give;
					v[o] = (float) (w.x + lag[3] * sway - bx);
					v[o + 1] = (float) (w.y + lag[4] * sway * 0.4 - by);
					v[o + 2] = (float) (w.z + lag[5] * sway - bz);
					v[o + 3] = (float) n.x;
					v[o + 4] = (float) n.y;
					v[o + 5] = (float) n.z;
				}
			}
		}
	}

	public static void blast(ClientLevel current, Vec3 centre, float power) {
		if (on() && current == level && !blocks.isEmpty()) {
			JellyField.blast(current, centre, power);
		}
		if (on() && current == level && bodiesNearby > 0) {
			double reach = power * 2.0 + 1.0;
			for (me.skaffy.realistic.RealisticBlockEntity e : me.skaffy.realistic.RealisticBlockEntity.near(current,
				new AABB(centre, centre).inflate(reach))) {
				Vec3 away = e.position().subtract(centre);
				double dist = Math.max(away.length(), 0.5);
				if (dist > reach) {
					continue;
				}
				kickBody(e.getId(), away.normalize().scale((1.0 - dist / reach) * power * 0.6));
			}
		}
	}

	public static boolean hidesModel(net.minecraft.client.renderer.block.BlockAndTintGetter at, BlockPos pos, BlockState state) {
		if (!on() || !WobblyBlocks.isWobbly(state) || at instanceof net.minecraft.client.renderer.block.MovingBlockRenderState) {
			return false;
		}
		return WobblyBlocks.isWobbly(at.getBlockState(pos));
	}

	public static boolean wanted() {
		return on() && level != null && (!blocks.isEmpty() || bodiesNearby > 0 || GelItems.hasWorld());
	}

	private static int lightOf(ClientLevel lvl, BlockPos pos) {
		int a = LightCoordsUtil.getLightCoords(lvl, pos);
		int b = LightCoordsUtil.getLightCoords(lvl, pos.above());
		int block = Math.max((a >> 4) & 15, (b >> 4) & 15);
		int sky = Math.max((a >> 20) & 15, (b >> 20) & 15);
		return block * 16 + sky;
	}

	private static boolean build(Minecraft mc, Vec3 cam, float partial) {
		ClientLevel lvl = level;
		mesher.reset();
		if (lvl == null) {
			return false;
		}
		int bx = Mth.floor(cam.x);
		int by = Mth.floor(cam.y);
		int bz = Mth.floor(cam.z);
		double range = Math.max(8, WaterSettings.wobblyRange);
		double reach = Math.min(range, (mc.options.renderDistance().get() + 1) * 16.0);
		mesher.partial(partial);
		BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
		for (long key : blocks) {
			int x = BlockPos.getX(key);
			int y = BlockPos.getY(key);
			int z = BlockPos.getZ(key);
			double dx = x + 0.5 - cam.x;
			double dy = y + 0.5 - cam.y;
			double dz = z + 0.5 - cam.z;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 > reach * reach) {
				continue;
			}
			BlockPos pos = BlockPos.of(key);
			BlockState state = lvl.getBlockState(pos);
			JellyKind kind = JellyKind.of(state);
			if (kind == null) {
				continue;
			}
			int exposed = 0;
			for (Direction d : Direction.values()) {
				if (JellyKind.of(lvl.getBlockState(next.setWithOffset(pos, d))) != kind) {
					exposed |= 1 << d.ordinal();
				}
			}
			float sag = state.getValue(WobblyBlocks.HANGING) ? (kind == JellyKind.HONEY ? 0.22F : 0.14F) : 0.0F;
			mesher.block(pos, kind, exposed, JellyField.isAwake(key), d2 < 40.0 * 40.0, x - bx, y - by, z - bz, lightOf(lvl, pos), sag, null);
		}
		bodyCells(lvl, cam, reach, partial, bx, by, bz);
		GelItems.appendWorld(mesher, cam, bx, by, bz);
		return mesher.indexCount > 0;
	}

	public static void record(VkCommandBuffer cb, Desc ubo, int width, int height, int colorFormat, long colorImage, long colorView, long depthImage,
		long depthView, long lightmap) {
		Minecraft mc = Minecraft.getInstance();
		var state = mc.gameRenderer.gameRenderState().levelRenderState;
		try {
			if (!build(mc, state.cameraRenderState.pos, state.worldPartialTicks)) {
				return;
			}
			if (renderer == null) {
				renderer = new JellyRenderer();
			}
			renderer.record(cb, ubo, mesher, width, height, colorFormat, colorImage, colorView, depthImage, depthView, lightmap);
		} catch (RuntimeException e) {
			if (!failedSaid) {
				failedSaid = true;
				RealisticWater.LOGGER.error("Wobbly blocks couldn't be drawn", e);
			}
		}
	}

	public static void submitShadows(net.minecraft.client.renderer.SubmitNodeCollector collector, Vec3 camera) {
		if (!wanted()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (!build(mc, camera, mc.gameRenderer.gameRenderState().levelRenderState.worldPartialTicks)) {
			return;
		}
		float ox = (float) (Mth.floor(camera.x) - camera.x);
		float oy = (float) (Mth.floor(camera.y) - camera.y);
		float oz = (float) (Mth.floor(camera.z) - camera.z);
		float[] v = java.util.Arrays.copyOf(mesher.vertices, mesher.vertexCount * JellyMesher.FLOATS);
		int[] idx = java.util.Arrays.copyOf(mesher.indices, mesher.indexCount);
		collector.submitCustomGeometry(new com.mojang.blaze3d.vertex.PoseStack(),
			net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(me.skaffy.client.water.PackMaterials.WHITE), (p, buf) -> {
				for (int q = 0; q + 5 < idx.length; q += 6) {
					for (int k : new int[]{idx[q], idx[q + 1], idx[q + 2], idx[q + 5]}) {
						int o = k * JellyMesher.FLOATS;
						buf.addVertex(p, v[o] + ox, v[o + 1] + oy, v[o + 2] + oz).setColor(-1).setUv(0.0F, 0.0F)
							.setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(0xF000F0)
							.setNormal(p, v[o + 3], v[o + 4], v[o + 5]);
					}
				}
			});
	}

	public static void shutdown() {
		GelItems.shutdown();
		if (renderer != null) {
			renderer.close();
			renderer = null;
		}
		clear();
	}

	static List<Long> debugBlocks() {
		return new ArrayList<>(blocks);
	}
}
