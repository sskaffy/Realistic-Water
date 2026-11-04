package me.skaffy.client.kelp;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.client.water.WaterSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RealisticKelp {
	private static final RealisticKelp INSTANCE = new RealisticKelp();
	private static final Identifier KELP = Identifier.withDefaultNamespace("block/kelp");
	private static final Identifier KELP_PLANT = Identifier.withDefaultNamespace("block/kelp_plant");
	private static final double SWAY_RADIUS = 16.0;

	private static volatile LongSet hidden = new LongOpenHashSet();

	private final Long2ObjectOpenHashMap<KelpStrand> strands = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet active = new LongOpenHashSet();
	private final LongOpenHashSet dirtyColumns = new LongOpenHashSet();
	private final KelpSim sim = new KelpSim();
	private final Long2IntOpenHashMap mediumCache = new Long2IntOpenHashMap();
	private final Long2ObjectOpenHashMap<List<AABB>> shapeCache = new Long2ObjectOpenHashMap<>();
	private ClientLevel level;
	private int centreX = Integer.MIN_VALUE;
	private int centreZ;
	private int radius;
	private float time;

	private RealisticKelp() {
		this.mediumCache.defaultReturnValue(-1);
	}

	public static RealisticKelp get() {
		return INSTANCE;
	}

	public static void init() {
		KelpModel.register();
	}

	public boolean hasStrands() {
		return !this.strands.isEmpty();
	}

	public void onChunkLoad(ClientLevel lvl, LevelChunk chunk) {
		long key = chunk.getPos().pack();
		if (lvl == this.level && this.active.contains(key)) {
			this.dropChunk(key);
			this.scanChunk(key);
		}
	}

	public static boolean hides(BlockPos pos) {
		LongSet h = hidden;
		return !h.isEmpty() && h.contains(ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
	}

	static boolean isKelp(BlockState state) {
		return state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT);
	}

	public void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		boolean on = WaterSettings.realisticKelp && lvl != null && mc.player != null;
		if (!on || lvl != this.level) {
			this.clear(mc);
			this.level = on ? lvl : null;
			if (!on) {
				return;
			}
		}
		Vec3 cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		this.updateActive(mc, cam);
		this.rebuildDirty();
		this.mediumCache.clear();
		this.shapeCache.clear();

		LocalPlayer player = mc.player;
		if (this.sim.grabbed != null) {
			if (!mc.options.keyUse.isDown() || !this.strands.containsValue(this.sim.grabbed)) {
				this.sim.grabbed = null;
			} else {
				Vec3 eye = player.getEyePosition();
				Vec3 look = player.getViewVector(1.0F);
				this.sim.grabX = eye.x + look.x * this.grabDistance;
				this.sim.grabY = eye.y + look.y * this.grabDistance;
				this.sim.grabZ = eye.z + look.z * this.grabDistance;
				this.sim.grabbed.awake = true;
				this.sim.grabbed.calmTicks = 0;
			}
		}

		List<KelpSim.Body> bodies = new ArrayList<>();
		AABB area = new AABB(cam.x - this.radius, lvl.getMinY(), cam.z - this.radius, cam.x + this.radius, lvl.getMaxY(), cam.z + this.radius);
		for (Entity e : lvl.getEntities((Entity) null, area, e -> !e.isSpectator() && e.isPickable() || e == player)) {
			AABB b = e.getBoundingBox();
			bodies.add(new KelpSim.Body(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, e.getX() - e.xOld, e.getY() - e.yOld, e.getZ() - e.zOld));
		}

		List<KelpStrand> awake = new ArrayList<>();
		for (KelpStrand s : this.strands.values()) {
			if (!s.awake) {
				boolean near = WaterSettings.kelpSway > 0.0F && distSq(s, cam) < SWAY_RADIUS * SWAY_RADIUS;
				boolean touched = false;
				for (KelpSim.Body b : bodies) {
					if (Math.abs(b.vx()) + Math.abs(b.vy()) + Math.abs(b.vz()) > 0.003 && s.overlaps(b.x0() - 0.2, b.y0() - 0.2, b.z0() - 0.2, b.x1() + 0.2, b.y1() + 0.2, b.z1() + 0.2)) {
						touched = true;
						break;
					}
				}
				if (near || touched) {
					s.awake = true;
					s.calmTicks = 0;
				}
			}
			if (s.awake) {
				awake.add(s);
			}
		}
		if (awake.isEmpty()) {
			return;
		}
		List<KelpStrand> involved = new ArrayList<>(awake);
		if (WaterSettings.kelpSelfCollision) {
			for (KelpStrand s : this.strands.values()) {
				if (!s.awake) {
					for (KelpStrand a : awake) {
						if (s.overlaps(a.minX - 0.3, a.minY - 0.3, a.minZ - 0.3, a.maxX + 0.3, a.maxY + 0.3, a.maxZ + 0.3)) {
							involved.add(s);
							break;
						}
					}
				}
			}
		}
		KelpSim.Params params = new KelpSim.Params(WaterSettings.kelpBuoyancy, 24.0F, WaterSettings.kelpWaterDrag, 0.4F, WaterSettings.kelpSway,
			WaterSettings.kelpStiffness, WaterSettings.kelpSelfCollision);
		this.sim.tick(awake, involved, this.env, bodies, this.time, params);
		this.time += 0.05F;
		for (KelpStrand s : awake) {
			float moved = 0.0F;
			for (int i = 0; i < s.count; i++) {
				moved = Math.max(moved, Math.abs(s.x[i] - s.rx[i]) + Math.abs(s.y[i] - s.ry[i]) + Math.abs(s.z[i] - s.rz[i]));
			}
			boolean keepAwake = s == this.sim.grabbed || WaterSettings.kelpSway > 0.0F && distSq(s, cam) < SWAY_RADIUS * SWAY_RADIUS;
			s.calmTicks = moved < 0.004F && !keepAwake ? s.calmTicks + 1 : 0;
			if (s.calmTicks > 30) {
				s.awake = false;
				s.snapshot();
			}
		}
	}

	private double grabDistance;

	public boolean tryGrab(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (!WaterSettings.realisticKelp || player == null || this.strands.isEmpty() || !player.getMainHandItem().isEmpty() || player.isSpectator()) {
			return false;
		}
		if (this.sim.grabbed != null) {
			return true;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		double reach = player.blockInteractionRange();
		HitResult hit = mc.hitResult;
		if (hit != null && hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult bh && !isKelp(mc.level.getBlockState(bh.getBlockPos()))) {
			reach = Math.min(reach, hit.getLocation().distanceTo(eye) + 0.2);
		}
		KelpStrand best = null;
		int bestNode = -1;
		double bestT = Double.MAX_VALUE;
		AABB ray = new AABB(eye, eye.add(look.scale(reach))).inflate(0.4);
		for (KelpStrand s : this.strands.values()) {
			if (!s.overlaps(ray.minX, ray.minY, ray.minZ, ray.maxX, ray.maxY, ray.maxZ)) {
				continue;
			}
			for (int i = 1; i < s.count; i++) {
				double rx = s.rootX + s.x[i] - eye.x;
				double ry = s.rootY + s.y[i] - eye.y;
				double rz = s.rootZ + s.z[i] - eye.z;
				double t = rx * look.x + ry * look.y + rz * look.z;
				if (t < 0.0 || t > reach) {
					continue;
				}
				double ox = rx - look.x * t;
				double oy = ry - look.y * t;
				double oz = rz - look.z * t;
				if (ox * ox + oy * oy + oz * oz < 0.35 * 0.35 && t < bestT) {
					best = s;
					bestNode = i;
					bestT = t;
				}
			}
		}
		if (best == null) {
			return false;
		}
		best.awake = true;
		best.calmTicks = 0;
		this.sim.grabbed = best;
		this.sim.grabbedNode = bestNode;
		this.grabDistance = bestT;
		this.sim.grabX = eye.x + look.x * bestT;
		this.sim.grabY = eye.y + look.y * bestT;
		this.sim.grabZ = eye.z + look.z * bestT;
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		return true;
	}

	public void onBlockChanged(ClientLevel lvl, BlockPos pos, BlockState oldState, BlockState newState) {
		if (lvl != this.level || this.active.isEmpty() || isKelp(oldState) == isKelp(newState) && !isKelp(newState)) {
			return;
		}
		if (this.active.contains(ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())))) {
			this.dirtyColumns.add(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
		}
	}

	private void updateActive(Minecraft mc, Vec3 cam) {
		int r = Math.max(16, WaterSettings.kelpRadius);
		int cx = SectionPos.blockToSectionCoord(cam.x);
		int cz = SectionPos.blockToSectionCoord(cam.z);
		if (cx == this.centreX && cz == this.centreZ && r == this.radius) {
			return;
		}
		this.centreX = cx;
		this.centreZ = cz;
		this.radius = r;
		int chunks = Math.ceilDiv(r, 16);
		LongOpenHashSet next = new LongOpenHashSet();
		for (int dz = -chunks; dz <= chunks; dz++) {
			for (int dx = -chunks; dx <= chunks; dx++) {
				if (dx * dx + dz * dz <= chunks * chunks + 1) {
					next.add(ChunkPos.pack(cx + dx, cz + dz));
				}
			}
		}
		List<Long> changed = new ArrayList<>();
		for (long key : this.active) {
			if (!next.contains(key)) {
				changed.add(key);
				this.dropChunk(key);
			}
		}
		for (long key : next) {
			if (!this.active.contains(key)) {
				changed.add(key);
				this.scanChunk(key);
			}
		}
		this.active.clear();
		this.active.addAll(next);
		hidden = new LongOpenHashSet(next);
		for (long key : changed) {
			markChunkDirty(mc, key);
		}
	}

	private static void markChunkDirty(Minecraft mc, long key) {
		ClientLevel lvl = mc.level;
		if (lvl == null) {
			return;
		}
		int x = ChunkPos.getX(key) << 4;
		int z = ChunkPos.getZ(key) << 4;
		mc.levelExtractor.setBlocksDirty(x, lvl.getMinY(), z, x + 15, lvl.getMaxY(), z + 15);
	}

	private void dropChunk(long key) {
		int x = ChunkPos.getX(key);
		int z = ChunkPos.getZ(key);
		this.strands.values().removeIf(s -> {
			boolean gone = SectionPos.blockToSectionCoord(s.rootX) == x && SectionPos.blockToSectionCoord(s.rootZ) == z;
			if (gone && s == this.sim.grabbed) {
				this.sim.grabbed = null;
			}
			return gone;
		});
	}

	private void scanChunk(long key) {
		int cx = ChunkPos.getX(key);
		int cz = ChunkPos.getZ(key);
		if (!this.level.hasChunk(cx, cz)) {
			return;
		}
		LevelChunk chunk = this.level.getChunk(cx, cz);
		LevelChunkSection[] sections = chunk.getSections();
		for (int si = 0; si < sections.length; si++) {
			LevelChunkSection section = sections[si];
			if (section.hasOnlyAir() || !section.maybeHas(RealisticKelp::isKelp)) {
				continue;
			}
			int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(si));
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (isKelp(section.getBlockState(x, y, z))) {
							int wx = (cx << 4) + x;
							int wz = (cz << 4) + z;
							int wy = baseY + y;
							if (!isKelp(this.level.getBlockState(new BlockPos(wx, wy - 1, wz)))) {
								this.addStrand(wx, wy, wz, null);
							}
						}
					}
				}
			}
		}
	}

	private void rebuildDirty() {
		if (this.dirtyColumns.isEmpty()) {
			return;
		}
		for (long col : this.dirtyColumns) {
			int x = BlockPos.getX(col);
			int z = BlockPos.getZ(col);
			List<KelpStrand> old = new ArrayList<>();
			this.strands.values().removeIf(s -> {
				if (s.rootX == x && s.rootZ == z) {
					old.add(s);
					return true;
				}
				return false;
			});
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			boolean below = false;
			for (int y = this.level.getMinY(); y < this.level.getMaxY(); y++) {
				boolean here = isKelp(this.level.getBlockState(pos.set(x, y, z)));
				if (here && !below) {
					KelpStrand previous = null;
					for (KelpStrand s : old) {
						if (s.rootY == y) {
							previous = s;
						}
					}
					this.addStrand(x, y, z, previous);
				}
				below = here;
			}
			for (KelpStrand s : old) {
				if (s == this.sim.grabbed && !this.strands.containsKey(s.root)) {
					this.sim.grabbed = null;
				}
			}
		}
		this.dirtyColumns.clear();
	}

	private void addStrand(int x, int y, int z, KelpStrand previous) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
		int blocks = 0;
		while (isKelp(this.level.getBlockState(pos))) {
			blocks++;
			pos.move(0, 1, 0);
		}
		if (blocks == 0) {
			return;
		}
		long key = BlockPos.asLong(x, y, z);
		KelpStrand s = new KelpStrand(key, x, y, z, blocks);
		if (previous != null) {
			s.copyFrom(previous);
			if (previous == this.sim.grabbed) {
				this.sim.grabbed = this.sim.grabbedNode < s.count ? s : null;
			}
		}
		this.strands.put(key, s);
	}

	private void clear(Minecraft mc) {
		if (!this.active.isEmpty() && mc.level != null && mc.level == this.level) {
			for (long key : this.active) {
				markChunkDirty(mc, key);
			}
		}
		this.strands.clear();
		this.active.clear();
		this.dirtyColumns.clear();
		this.sim.grabbed = null;
		this.centreX = Integer.MIN_VALUE;
		hidden = new LongOpenHashSet();
	}

	private static double distSq(KelpStrand s, Vec3 cam) {
		double dx = s.rootX + 0.5 - cam.x;
		double dz = s.rootZ + 0.5 - cam.z;
		return dx * dx + dz * dz;
	}

	private final KelpSim.Env env = new KelpSim.Env() {
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		@Override
		public boolean water(double x, double y, double z) {
			int bx = (int) Math.floor(x);
			int by = (int) Math.floor(y);
			int bz = (int) Math.floor(z);
			long key = BlockPos.asLong(bx, by, bz);
			int height = RealisticKelp.this.mediumCache.get(key);
			if (height == -1) {
				FluidState fluid = RealisticKelp.this.level.getFluidState(this.pos.set(bx, by, bz));
				height = fluid.is(FluidTags.WATER) ? Math.round(fluid.getHeight(RealisticKelp.this.level, this.pos) * 1000.0F) : 0;
				RealisticKelp.this.mediumCache.put(key, height);
			}
			return height > 0 && y - by <= height / 1000.0;
		}

		@Override
		public boolean collide(double[] p, float radius) {
			int bx = (int) Math.floor(p[0]);
			int by = (int) Math.floor(p[1]);
			int bz = (int) Math.floor(p[2]);
			List<AABB> boxes = this.shapes(bx, by, bz);
			if (boxes.isEmpty()) {
				return false;
			}
			for (AABB b : boxes) {
				double x0 = bx + b.minX - radius;
				double y0 = by + b.minY - radius;
				double z0 = bz + b.minZ - radius;
				double x1 = bx + b.maxX + radius;
				double y1 = by + b.maxY + radius;
				double z1 = bz + b.maxZ + radius;
				if (p[0] <= x0 || p[0] >= x1 || p[1] <= y0 || p[1] >= y1 || p[2] <= z0 || p[2] >= z1) {
					continue;
				}
				double[] d = {p[0] - x0, x1 - p[0], p[1] - y0, y1 - p[1], p[2] - z0, z1 - p[2]};
				int[][] off = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
				int bestFace = -1;
				for (int f = 0; f < 6; f++) {
					if ((bestFace < 0 || d[f] < d[bestFace]) && !this.full(bx + off[f][0], by + off[f][1], bz + off[f][2])) {
						bestFace = f;
					}
				}
				if (bestFace < 0) {
					bestFace = 3;
				}
				int axis = bestFace >> 1;
				p[axis] += (bestFace & 1) == 0 ? -d[bestFace] : d[bestFace];
				return true;
			}
			return false;
		}

		private boolean full(int x, int y, int z) {
			List<AABB> s = this.shapes(x, y, z);
			return s.size() == 1 && s.get(0).getXsize() >= 1.0 && s.get(0).getYsize() >= 1.0 && s.get(0).getZsize() >= 1.0;
		}

		private List<AABB> shapes(int x, int y, int z) {
			long key = BlockPos.asLong(x, y, z);
			List<AABB> boxes = RealisticKelp.this.shapeCache.get(key);
			if (boxes == null) {
				BlockState state = RealisticKelp.this.level.getBlockState(this.pos.set(x, y, z));
				VoxelShape shape = state.getCollisionShape(RealisticKelp.this.level, this.pos);
				boxes = shape.isEmpty() ? List.of() : shape.toAabbs();
				RealisticKelp.this.shapeCache.put(key, boxes);
			}
			return boxes;
		}
	};

	public void submit(SubmitNodeCollector collector, Vec3 camera) {
		if (this.strands.isEmpty() || this.level == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		TextureAtlas atlas = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
		TextureAtlasSprite top = atlas.getSprite(KELP);
		TextureAtlasSprite plant = atlas.getSprite(KELP_PLANT);
		float partial = mc.gameRenderer.gameRenderState().levelRenderState.worldPartialTicks;
		Long2IntOpenHashMap light = new Long2IntOpenHashMap();
		light.defaultReturnValue(-1);
		List<KelpStrand> visible = new ArrayList<>(this.strands.values());
		collector.submitCustomGeometry(new PoseStack(), RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS),
			(p, buf) -> {
				for (KelpStrand s : visible) {
					this.emit(p, buf, s, camera, partial, top, plant, light);
				}
			});
	}

	private static final float HALF_WIDTH = 0.7F;

	private void emit(PoseStack.Pose p, VertexConsumer buf, KelpStrand s, Vec3 cam, float partial, TextureAtlasSprite top, TextureAtlasSprite plant,
		Long2IntOpenHashMap light) {
		int n = s.count;
		float[] px = new float[n];
		float[] py = new float[n];
		float[] pz = new float[n];
		for (int i = 0; i < n; i++) {
			px[i] = (float) (s.rootX - cam.x) + s.rx[i] + (s.x[i] - s.rx[i]) * partial;
			py[i] = (float) (s.rootY - cam.y) + s.ry[i] + (s.y[i] - s.ry[i]) * partial;
			pz[i] = (float) (s.rootZ - cam.z) + s.rz[i] + (s.z[i] - s.rz[i]) * partial;
		}
		float ax = 0.70710677F;
		float ay = 0.0F;
		float az = 0.70710677F;
		float[] sa = new float[n * 3];
		float[] sb = new float[n * 3];
		for (int i = 0; i < n; i++) {
			int i0 = Math.max(i - 1, 0);
			int i1 = Math.min(i + 1, n - 1);
			float tx = px[i1] - px[i0];
			float ty = py[i1] - py[i0];
			float tz = pz[i1] - pz[i0];
			float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
			if (tl < 1.0e-5F) {
				tx = 0.0F;
				ty = 1.0F;
				tz = 0.0F;
			} else {
				tx /= tl;
				ty /= tl;
				tz /= tl;
			}
			float dot = ax * tx + ay * ty + az * tz;
			ax -= tx * dot;
			ay -= ty * dot;
			az -= tz * dot;
			float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);
			if (al < 1.0e-4F) {
				ax = ty;
				ay = -tx;
				az = 0.0F;
				al = Math.max((float) Math.sqrt(ax * ax + ay * ay), 1.0e-4F);
			}
			ax /= al;
			ay /= al;
			az /= al;
			sa[i * 3] = ax * HALF_WIDTH;
			sa[i * 3 + 1] = ay * HALF_WIDTH;
			sa[i * 3 + 2] = az * HALF_WIDTH;
			sb[i * 3] = (ty * az - tz * ay) * HALF_WIDTH;
			sb[i * 3 + 1] = (tz * ax - tx * az) * HALF_WIDTH;
			sb[i * 3 + 2] = (tx * ay - ty * ax) * HALF_WIDTH;
		}
		for (int i = 0; i + 1 < n; i++) {
			int block = i / KelpStrand.SEGMENTS;
			TextureAtlasSprite sprite = block == s.blocks - 1 ? top : plant;
			float f0 = (float) (i - block * KelpStrand.SEGMENTS) / KelpStrand.SEGMENTS;
			float f1 = f0 + 1.0F / KelpStrand.SEGMENTS;
			float v0 = sprite.getV(1.0F - f0);
			float v1 = sprite.getV(1.0F - f1);
			int l0 = this.smoothLight(light, s.rootX + s.x[i], s.rootY + s.y[i], s.rootZ + s.z[i]);
			int l1 = this.smoothLight(light, s.rootX + s.x[i + 1], s.rootY + s.y[i + 1], s.rootZ + s.z[i + 1]);
			for (float[] side : new float[][]{sa, sb}) {
				int a = i * 3;
				int b = (i + 1) * 3;
				vertex(buf, p, px[i] - side[a], py[i] - side[a + 1], pz[i] - side[a + 2], sprite.getU(0.0F), v0, l0);
				vertex(buf, p, px[i] + side[a], py[i] + side[a + 1], pz[i] + side[a + 2], sprite.getU(1.0F), v0, l0);
				vertex(buf, p, px[i + 1] + side[b], py[i + 1] + side[b + 1], pz[i + 1] + side[b + 2], sprite.getU(1.0F), v1, l1);
				vertex(buf, p, px[i + 1] - side[b], py[i + 1] - side[b + 1], pz[i + 1] - side[b + 2], sprite.getU(0.0F), v1, l1);
			}
		}
	}

	private static void vertex(VertexConsumer buf, PoseStack.Pose p, float x, float y, float z, float u, float v, int light) {
		buf.addVertex(p, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, 0.0F, 1.0F, 0.0F);
	}

	private int smoothLight(Long2IntOpenHashMap cache, double x, double y, double z) {
		double fx = x - 0.5;
		double fy = y - 0.5;
		double fz = z - 0.5;
		int x0 = (int) Math.floor(fx);
		int y0 = (int) Math.floor(fy);
		int z0 = (int) Math.floor(fz);
		double tx = fx - x0;
		double ty = fy - y0;
		double tz = fz - z0;
		double block = 0.0;
		double sky = 0.0;
		double weight = 0.0;
		for (int k = 0; k < 8; k++) {
			int bx = x0 + (k & 1);
			int by = y0 + (k >> 1 & 1);
			int bz = z0 + (k >> 2);
			int coords = this.blockLight(cache, bx, by, bz);
			if (coords == OPAQUE) {
				continue;
			}
			double w = ((k & 1) == 0 ? 1.0 - tx : tx) * ((k >> 1 & 1) == 0 ? 1.0 - ty : ty) * ((k >> 2) == 0 ? 1.0 - tz : tz);
			block += LightCoordsUtil.block(coords) * w;
			sky += LightCoordsUtil.sky(coords) * w;
			weight += w;
		}
		if (weight < 1.0e-4) {
			return this.blockLight(cache, (int) Math.floor(x), (int) Math.floor(y + 1.0), (int) Math.floor(z)) & 0xFFFFFF;
		}
		return LightCoordsUtil.smoothPack((int) Math.round(block / weight * 16.0), (int) Math.round(sky / weight * 16.0));
	}

	private static final int OPAQUE = Integer.MIN_VALUE;

	private int blockLight(Long2IntOpenHashMap cache, int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		int known = cache.get(key);
		if (known != -1) {
			return known;
		}
		BlockPos pos = BlockPos.of(key);
		int coords = this.level.getBlockState(pos).isSolidRender() ? OPAQUE : LightCoordsUtil.getLightCoords(this.level, pos);
		cache.put(key, coords);
		return coords;
	}
}
