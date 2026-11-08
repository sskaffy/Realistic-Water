package me.skaffy.client.grass;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class RealisticGrass {
	public static final int SHORT = 0;
	public static final int TALL = 1;
	public static final int SHORT_DRY = 2;
	public static final int TALL_DRY = 3;
	static final int MAX_COLLIDERS = 128;

	private static final RealisticGrass INSTANCE = new RealisticGrass();
	private static volatile LongSet hidden = new LongOpenHashSet();

	private @Nullable ClientLevel level;
	private int centreX = Integer.MIN_VALUE;
	private int centreZ;
	private int radius;
	private int builtStyle = -1;
	private int builtBlades = -1;
	private final LongOpenHashSet active = new LongOpenHashSet();
	private final Long2ObjectOpenHashMap<LongArrayList> columns = new Long2ObjectOpenHashMap<>();
	private final Long2IntOpenHashMap slots = new Long2IntOpenHashMap();
	private final IntArrayList free = new IntArrayList();
	int slotCount;
	private long[] slotPos = new long[0];
	private int[] slotType = new int[0];
	private int[] slotGen = new int[0];
	private float[] slotLook = new float[0];
	private int nextGen = 1;

	boolean grabPending;
	boolean grabHeld;
	boolean grabMulti;
	final int[] grabSlots = new int[12];
	private int lookCursor;
	float time;

	private RealisticGrass() {
		this.slots.defaultReturnValue(-1);
	}

	public static RealisticGrass get() {
		return INSTANCE;
	}

	public static void init() {
		GrassModel.register();
	}

	public static boolean hides(BlockPos pos) {
		LongSet h = hidden;
		return !h.isEmpty() && h.contains(ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
	}

	public static int typeOf(BlockState state) {
		if (state.is(Blocks.SHORT_GRASS)) {
			return SHORT;
		}
		if (state.is(Blocks.TALL_GRASS)) {
			return state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? TALL : -1;
		}
		if (state.is(Blocks.SHORT_DRY_GRASS)) {
			return SHORT_DRY;
		}
		if (state.is(Blocks.TALL_DRY_GRASS)) {
			return TALL_DRY;
		}
		return -1;
	}

	static boolean isGrass(BlockState state) {
		return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS) || state.is(Blocks.SHORT_DRY_GRASS) || state.is(Blocks.TALL_DRY_GRASS);
	}

	public boolean wanted() {
		return WaterSettings.realisticGrass && this.level != null && this.slotCount > 0;
	}

	static int unitsPerBlock() {
		return switch (WaterSettings.grassStyle) {
			case 1 -> Math.max(1, Math.round(Math.max(4, WaterSettings.grassBlades) / 5.0F));
			case 2 -> 2;
			default -> Math.max(4, WaterSettings.grassBlades);
		};
	}

	public void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		boolean on = WaterSettings.realisticGrass && lvl != null && mc.player != null;
		int style = Mth.clamp(WaterSettings.grassStyle, 0, 2);
		if (!on || lvl != this.level || style != this.builtStyle || WaterSettings.grassBlades != this.builtBlades) {
			this.clear(mc);
			this.level = on ? lvl : null;
			this.builtStyle = style;
			this.builtBlades = WaterSettings.grassBlades;
			if (!on) {
				return;
			}
		}
		Vec3 cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		this.updateActive(mc, cam);
		this.refreshLooks(512);
		if (this.grabHeld && !mc.options.keyUse.isDown()) {
			this.grabHeld = false;
			this.grabPending = false;
		}
	}

	public boolean tryGrab(Minecraft mc) {
		net.minecraft.client.player.LocalPlayer player = mc.player;
		if (this.grabHeld) {
			return true;
		}
		if (!WaterSettings.realisticGrass || player == null || this.level == null || this.slotCount == 0 || !player.getMainHandItem().isEmpty()
			|| player.isSpectator()) {
			return false;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		double reach = player.blockInteractionRange();
		net.minecraft.world.phys.BlockHitResult wall = this.level.clip(new net.minecraft.world.level.ClipContext(eye, eye.add(look.scale(reach)),
			net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
		if (wall.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
			reach = Math.min(reach, wall.getLocation().distanceTo(eye) + 0.05);
		}
		java.util.Arrays.fill(this.grabSlots, -1);
		List<double[]> found = new ArrayList<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		Vec3 end = eye.add(look.scale(reach));
		for (double d = 0.0; d <= reach; d += 0.25) {
			Vec3 at = eye.add(look.scale(d));
			int bx = Mth.floor(at.x);
			int by = Mth.floor(at.y);
			int bz = Mth.floor(at.z);
			for (int dy = -2; dy <= 0; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						long key = BlockPos.asLong(bx + dx, by + dy, bz + dz);
						if (!seen.add(key)) {
							continue;
						}
						int slot = this.slots.get(key);
						if (slot < 0) {
							continue;
						}
						int type = this.slotType[slot];
						double height = (type == TALL ? 2.0 : type == TALL_DRY ? 1.05 : 0.95) * Math.max(WaterSettings.grassHeight, 0.05F);
						AABB box = new AABB(bx + dx - 0.35, by + dy, bz + dz - 0.35, bx + dx + 1.35, by + dy + height + 0.1, bz + dz + 1.35);
						java.util.Optional<Vec3> hit = box.clip(eye, end);
						if (box.contains(eye)) {
							hit = java.util.Optional.of(eye);
						}
						hit.ifPresent(h -> found.add(new double[]{h.distanceTo(eye), slot}));
					}
				}
			}
		}
		if (found.isEmpty()) {
			return false;
		}
		found.sort(Comparator.comparingDouble(f -> f[0]));
		for (int i = 0; i < Math.min(12, found.size()); i++) {
			this.grabSlots[i] = (int) found.get(i)[1];
		}
		this.grabMulti = player.isShiftKeyDown();
		this.grabPending = true;
		this.grabHeld = true;
		player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		return true;
	}

	public void onChunkLoad(ClientLevel lvl, LevelChunk chunk) {
		if (lvl == this.level && this.floorValid) {
			this.fillFloor(chunk);
		}
		long key = chunk.getPos().pack();
		if (lvl == this.level && this.active.contains(key)) {
			this.dropColumn(key);
			this.scanColumn(key);
		}
	}

	public void onChunkUnload(ClientLevel lvl, LevelChunk chunk) {
		if (lvl == this.level) {
			this.dropColumn(chunk.getPos().pack());
		}
	}

	static final int FLOOR = 64;
	final int[] floorBits = new int[FLOOR * FLOOR * (FLOOR / 32)];
	int floorX;
	int floorY;
	int floorZ;
	boolean floorValid;

	private static final java.util.Map<BlockState, Boolean> SOLID = new java.util.concurrent.ConcurrentHashMap<>();

	private static boolean solid(BlockState s) {
		if (s.isAir()) {
			return false;
		}
		return SOLID.computeIfAbsent(s, st -> !st.getCollisionShape(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty());
	}

	void updateFloor(ClientLevel lvl, int cx, int cy, int cz) {
		int half = FLOOR / 2;
		boolean near = this.floorValid && Math.abs(cx - (this.floorX + half)) < 8 && Math.abs(cy - (this.floorY + half)) < 8
			&& Math.abs(cz - (this.floorZ + half)) < 8;
		if (near) {
			return;
		}
		this.floorX = cx - half;
		this.floorY = cy - half;
		this.floorZ = cz - half;
		java.util.Arrays.fill(this.floorBits, 0);
		for (int chunkZ = this.floorZ >> 4; chunkZ <= (this.floorZ + FLOOR - 1) >> 4; chunkZ++) {
			for (int chunkX = this.floorX >> 4; chunkX <= (this.floorX + FLOOR - 1) >> 4; chunkX++) {
				LevelChunk chunk = lvl.getChunkSource().getChunk(chunkX, chunkZ, false);
				if (chunk != null) {
					this.fillFloor(chunk);
				}
			}
		}
		this.floorValid = true;
	}

	private void fillFloor(LevelChunk chunk) {
		int x0 = Math.max(chunk.getPos().getMinBlockX(), this.floorX);
		int x1 = Math.min(chunk.getPos().getMinBlockX() + 16, this.floorX + FLOOR);
		int z0 = Math.max(chunk.getPos().getMinBlockZ(), this.floorZ);
		int z1 = Math.min(chunk.getPos().getMinBlockZ() + 16, this.floorZ + FLOOR);
		if (x0 >= x1 || z0 >= z1) {
			return;
		}
		int words = FLOOR / 32;
		for (int z = z0; z < z1; z++) {
			for (int x = x0; x < x1; x++) {
				java.util.Arrays.fill(this.floorBits, ((x - this.floorX) + (z - this.floorZ) * FLOOR) * words,
					((x - this.floorX) + (z - this.floorZ) * FLOOR + 1) * words, 0);
			}
		}
		LevelChunkSection[] sections = chunk.getSections();
		for (int si = 0; si < sections.length; si++) {
			LevelChunkSection section = sections[si];
			int sy = chunk.getMinY() + si * 16;
			int y0 = Math.max(sy, this.floorY);
			int y1 = Math.min(sy + 16, this.floorY + FLOOR);
			if (y0 >= y1 || section.hasOnlyAir()) {
				continue;
			}
			for (int z = z0; z < z1; z++) {
				for (int x = x0; x < x1; x++) {
					int col = ((x - this.floorX) + (z - this.floorZ) * FLOOR) * words;
					for (int y = y0; y < y1; y++) {
						if (solid(section.getBlockState(x & 15, y & 15, z & 15))) {
							int ly = y - this.floorY;
							this.floorBits[col + (ly >> 5)] |= 1 << (ly & 31);
						}
					}
				}
			}
		}
	}

	private void patchFloor(ClientLevel lvl, BlockPos pos, BlockState state) {
		int x = pos.getX() - this.floorX;
		int y = pos.getY() - this.floorY;
		int z = pos.getZ() - this.floorZ;
		if (!this.floorValid || x < 0 || y < 0 || z < 0 || x >= FLOOR || y >= FLOOR || z >= FLOOR) {
			return;
		}
		int i = (x + z * FLOOR) * (FLOOR / 32) + (y >> 5);
		if (solid(state)) {
			this.floorBits[i] |= 1 << (y & 31);
		} else {
			this.floorBits[i] &= ~(1 << (y & 31));
		}
	}

	public void onBlockChanged(ClientLevel lvl, BlockPos pos, BlockState oldState, BlockState newState) {
		if (lvl != this.level) {
			return;
		}
		this.patchFloor(lvl, pos, newState);
		long column = ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
		if (!this.active.contains(column)) {
			return;
		}
		int before = typeOf(oldState);
		int after = typeOf(newState);
		if (before == after) {
			return;
		}
		long key = pos.asLong();
		if (before >= 0) {
			this.removeBlock(key, column);
		}
		if (after >= 0) {
			this.addBlock(key, column, after);
		}
	}

	private void updateActive(Minecraft mc, Vec3 cam) {
		int r = Math.max(8, WaterSettings.grassRadius);
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
				this.dropColumn(key);
			}
		}
		for (long key : next) {
			if (!this.active.contains(key)) {
				changed.add(key);
				this.scanColumn(key);
			}
		}
		this.active.clear();
		this.active.addAll(next);
		hidden = new LongOpenHashSet(next);
		for (long key : changed) {
			markColumnDirty(mc, key);
		}
	}

	private void scanColumn(long key) {
		ClientLevel lvl = this.level;
		int x = ChunkPos.getX(key);
		int z = ChunkPos.getZ(key);
		if (lvl == null || !lvl.hasChunk(x, z)) {
			return;
		}
		LevelChunk chunk = lvl.getChunk(x, z);
		LevelChunkSection[] sections = chunk.getSections();
		for (int s = 0; s < sections.length; s++) {
			LevelChunkSection section = sections[s];
			if (section.hasOnlyAir() || !section.maybeHas(RealisticGrass::isGrass)) {
				continue;
			}
			int baseY = (lvl.getMinSectionY() + s) << 4;
			for (int i = 0; i < 4096; i++) {
				int type = typeOf(section.getBlockState(i & 15, (i >> 4) & 15, i >> 8));
				if (type >= 0) {
					this.addBlock(BlockPos.asLong((x << 4) + (i & 15), baseY + ((i >> 4) & 15), (z << 4) + (i >> 8)), key, type);
				}
			}
		}
	}

	private void dropColumn(long key) {
		LongArrayList list = this.columns.remove(key);
		if (list == null) {
			return;
		}
		for (int i = 0; i < list.size(); i++) {
			int slot = this.slots.remove(list.getLong(i));
			if (slot >= 0) {
				this.slotType[slot] = -1;
				this.free.add(slot);
			}
		}
		this.trimSlots();
	}

	private void addBlock(long pos, long column, int type) {
		if (this.slots.containsKey(pos)) {
			return;
		}
		int slot;
		if (this.free.isEmpty()) {
			slot = this.slotCount++;
			this.ensureCapacity(this.slotCount);
		} else {
			this.free.sort(null);
			slot = this.free.removeInt(0);
		}
		this.slotPos[slot] = pos;
		this.slotType[slot] = type;
		this.slotGen[slot] = this.nextGen++ & 0xFFFFFF;
		if (this.slotGen[slot] == 0) {
			this.slotGen[slot] = this.nextGen++ & 0xFFFFFF;
		}
		this.refreshLook(slot);
		this.slots.put(pos, slot);
		this.columns.computeIfAbsent(column, k -> new LongArrayList()).add(pos);
	}

	private void removeBlock(long pos, long column) {
		int slot = this.slots.remove(pos);
		if (slot < 0) {
			return;
		}
		this.slotType[slot] = -1;
		this.free.add(slot);
		LongArrayList list = this.columns.get(column);
		if (list != null) {
			list.rem(pos);
		}
		this.trimSlots();
	}

	private void trimSlots() {
		while (this.slotCount > 0 && this.slotType[this.slotCount - 1] < 0) {
			this.slotCount--;
			this.free.rem(this.slotCount);
		}
	}

	private void ensureCapacity(int n) {
		if (n <= this.slotPos.length) {
			return;
		}
		int cap = Math.max(n, Math.max(1024, this.slotPos.length * 2));
		this.slotPos = java.util.Arrays.copyOf(this.slotPos, cap);
		int old = this.slotType.length;
		this.slotType = java.util.Arrays.copyOf(this.slotType, cap);
		java.util.Arrays.fill(this.slotType, old, cap, -1);
		this.slotGen = java.util.Arrays.copyOf(this.slotGen, cap);
		this.slotLook = java.util.Arrays.copyOf(this.slotLook, cap * 4);
	}

	private void refreshLooks(int budget) {
		for (int k = 0; k < budget && this.slotCount > 0; k++) {
			this.lookCursor = (this.lookCursor + 1) % this.slotCount;
			if (this.slotType[this.lookCursor] >= 0) {
				this.refreshLook(this.lookCursor);
			}
		}
	}

	private void refreshLook(int slot) {
		ClientLevel lvl = this.level;
		if (lvl == null) {
			return;
		}
		BlockPos p = BlockPos.of(this.slotPos[slot]);
		int type = this.slotType[slot];
		int o = slot * 4;
		if (type == SHORT || type == TALL) {
			int c = BiomeColors.getAverageGrassColor(lvl, p);
			this.slotLook[o] = ((c >> 16) & 0xFF) / 255.0F;
			this.slotLook[o + 1] = ((c >> 8) & 0xFF) / 255.0F;
			this.slotLook[o + 2] = (c & 0xFF) / 255.0F;
		} else {
			this.slotLook[o] = 1.0F;
			this.slotLook[o + 1] = 1.0F;
			this.slotLook[o + 2] = 1.0F;
		}
		int block = lvl.getBrightness(LightLayer.BLOCK, p);
		int sky = lvl.getBrightness(LightLayer.SKY, p);
		this.slotLook[o + 3] = block * 16 + sky;
	}

	void writeBlocks(ByteBuffer b, long offset) {
		for (int slot = 0; slot < this.slotCount; slot++) {
			int o = (int) offset + slot * 32;
			int type = this.slotType[slot];
			if (type < 0) {
				b.putInt(o, 0).putInt(o + 4, 0).putInt(o + 8, 0).putInt(o + 12, 0);
				continue;
			}
			long pos = this.slotPos[slot];
			b.putInt(o, BlockPos.getX(pos)).putInt(o + 4, BlockPos.getY(pos)).putInt(o + 8, BlockPos.getZ(pos))
				.putInt(o + 12, type | 4 | this.slotGen[slot] << 8);
			int l = slot * 4;
			b.putFloat(o + 16, this.slotLook[l]).putFloat(o + 20, this.slotLook[l + 1]).putFloat(o + 24, this.slotLook[l + 2])
				.putFloat(o + 28, this.slotLook[l + 3]);
		}
	}

	private static void bodyBoxes(List<float[]> out, RealisticBlockEntity e, int bx, int by, int bz, float partialTick, Vec3 cam) {
		Vec3 now = e.renderPosition(partialTick);
		org.joml.Quaternionf q = new org.joml.Quaternionf(e.renderRotation(partialTick));
		if (q.w < 0.0F) {
			q.set(-q.x, -q.y, -q.z, -q.w);
		}
		e.syncClientBody(now, q);
		org.joml.Vector3d vel = e.clientVelocity();
		org.joml.Vector3d spin = e.clientSpin();
		float packed = Float.intBitsToFloat(packRotation(q));
		org.joml.Vector3d c = new org.joml.Vector3d();
		List<float[]> mine = new ArrayList<>();
		for (me.skaffy.physics.RigidBody.Cell cell : e.body.cells) {
			if (!(cell.payload instanceof net.minecraft.world.level.block.state.BlockState state)) {
				continue;
			}
			for (double[] w : me.skaffy.client.realistic.BodyShapes.boxes(state)) {
				e.body.toWorld(cell.x + (w[0] + w[3]) * 0.5, cell.y + (w[1] + w[4]) * 0.5, cell.z + (w[2] + w[5]) * 0.5, c);
				double rx = c.x - now.x;
				double ry = c.y - now.y;
				double rz = c.z - now.z;
				float hx = (float) (w[3] - w[0]) * 0.5F;
				float hy = (float) (w[4] - w[1]) * 0.5F;
				float hz = (float) (w[5] - w[2]) * 0.5F;
				float round = Math.min(0.03F, 0.5F * Math.min(hx, Math.min(hy, hz)));
				mine.add(new float[]{
					(float) (c.x - bx), (float) (c.y - by), (float) (c.z - bz), packed,
					hx, hy, hz, 2.0F,
					(float) (vel.x + spin.y * rz - spin.z * ry), (float) (vel.y + spin.z * rx - spin.x * rz), (float) (vel.z + spin.x * ry - spin.y * rx),
					round});
			}
		}
		double ox = cam.x - bx;
		double oy = cam.y - by;
		double oz = cam.z - bz;
		mine.sort(Comparator.comparingDouble(f -> (f[0] - ox) * (f[0] - ox) + (f[1] - oy) * (f[1] - oy) + (f[2] - oz) * (f[2] - oz)));
		out.addAll(mine);
	}

	static int packRotation(org.joml.Quaternionf q) {
		int x = Math.clamp(Math.round((q.x * 0.5F + 0.5F) * 1023.0F), 0, 1023);
		int y = Math.clamp(Math.round((q.y * 0.5F + 0.5F) * 1023.0F), 0, 1023);
		int z = Math.clamp(Math.round((q.z * 0.5F + 0.5F) * 511.0F), 0, 511);
		return x | y << 10 | z << 20 | 1 << 30;
	}

	List<float[]> colliders(ClientLevel lvl, Vec3 cam, int bx, int by, int bz, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		double r = Math.max(8, WaterSettings.grassRadius) + 4.0;
		AABB area = new AABB(cam.x - r, cam.y - 48.0, cam.z - r, cam.x + r, cam.y + 48.0, cam.z + r);
		List<Entity> entities = new ArrayList<>(lvl.getEntities((Entity) null, area, e -> !e.isSpectator() && !e.isPassenger()));
		for (RealisticBlockEntity body : RealisticBlockEntity.near(lvl, area)) {
			if (!entities.contains(body)) {
				entities.add(body);
			}
		}
		entities.sort(Comparator.comparingDouble(e -> e.distanceToSqr(cam)));
		List<float[]> out = new ArrayList<>();
		for (Entity e : entities) {
			if (out.size() >= MAX_COLLIDERS) {
				break;
			}
			double ix = Mth.lerp(partialTick, e.xo, e.getX());
			double iy = Mth.lerp(partialTick, e.yo, e.getY());
			double iz = Mth.lerp(partialTick, e.zo, e.getZ());
			float vx = (float) (e.getX() - e.xo) * 20.0F;
			float vy = (float) (e.getY() - e.yo) * 20.0F;
			float vz = (float) (e.getZ() - e.zo) * 20.0F;
			if (e instanceof net.minecraft.world.entity.LivingEntity living && humanoid(mc, living)) {
				this.limbs(out, living, ix - bx, iy - by, iz - bz, partialTick, vx, vy, vz);
				continue;
			}
			if (e instanceof RealisticBlockEntity body) {
				bodyBoxes(out, body, bx, by, bz, partialTick, cam);
				continue;
			}
			AABB box = e.getBoundingBox().move(ix - e.getX(), iy - e.getY(), iz - e.getZ());
			float round = (float) Math.min(0.08, 0.25 * Math.min(box.getXsize(), Math.min(box.getYsize(), box.getZsize())));
			out.add(new float[]{
				(float) (box.minX - bx), (float) (box.minY - by), (float) (box.minZ - bz), round,
				(float) (box.maxX - bx), (float) (box.maxY - by), (float) (box.maxZ - bz), 1.0F,
				vx, vy, vz, 0.0F});
		}
		while (out.size() > MAX_COLLIDERS) {
			out.removeLast();
		}
		return out;
	}

	private static boolean humanoid(Minecraft mc, net.minecraft.world.entity.LivingEntity e) {
		net.minecraft.world.entity.Pose pose = e.getPose();
		if (pose != net.minecraft.world.entity.Pose.STANDING && pose != net.minecraft.world.entity.Pose.CROUCHING) {
			return false;
		}
		if (e instanceof net.minecraft.world.entity.player.Player) {
			return true;
		}
		return mc.getEntityRenderDispatcher().getRenderer(e) instanceof net.minecraft.client.renderer.entity.HumanoidMobRenderer<?, ?, ?>;
	}

	private void limbs(List<float[]> out, net.minecraft.world.entity.LivingEntity e, double x, double y, double z, float partialTick,
		float vx, float vy, float vz) {
		float s = e.getBbHeight() * 1.04F / 2.0F;
		float yaw = (float) Math.toRadians(Mth.rotLerp(partialTick, e.yBodyRotO, e.yBodyRot));
		float fx = -Mth.sin(yaw);
		float fz = Mth.cos(yaw);
		float sx = Mth.cos(yaw);
		float sz = Mth.sin(yaw);
		float pos = e.walkAnimation.position(partialTick);
		float speed = Math.min(e.walkAnimation.speed(partialTick), 1.0F);
		float legSwing = Mth.cos(pos * 0.6662F) * 1.4F * speed;
		float armSwing = Mth.cos(pos * 0.6662F) * speed;
		float hip = 0.75F * s;
		float leg = 0.75F * s;
		float limbRadius = 0.11F * s;
		for (int sideSign = -1; sideSign <= 1; sideSign += 2) {
			float a = sideSign < 0 ? legSwing : -legSwing;
			double hx = x + sx * sideSign * 0.119F * s;
			double hz = z + sz * sideSign * 0.119F * s;
			double hy = y + hip;
			double footX = hx + fx * Mth.sin(a) * leg;
			double footY = hy - Mth.cos(a) * leg + limbRadius * 0.6F;
			double footZ = hz + fz * Mth.sin(a) * leg;
			out.add(new float[]{(float) hx, (float) hy, (float) hz, limbRadius, (float) footX, (float) footY, (float) footZ, 0.0F, vx, vy, vz, 0.0F});
			double soleBottom = footY - limbRadius;
			if (soleBottom < y + 0.12 && e.onGround()) {
				double alongX = Math.abs(fx) * 0.16 * s + Math.abs(sx) * 0.08 * s;
				double alongZ = Math.abs(fz) * 0.16 * s + Math.abs(sz) * 0.08 * s;
				double cx = footX + fx * 0.04 * s;
				double cz = footZ + fz * 0.04 * s;
				out.add(new float[]{(float) (cx - alongX), (float) (y - 0.05), (float) (cz - alongZ), 0.02F,
					(float) (cx + alongX), (float) (soleBottom + 0.2 * s), (float) (cz + alongZ), 1.0F, vx, vy, vz, 0.0F});
			}
		}
		out.add(new float[]{(float) x, (float) (y + 0.82F * s), (float) z, 0.2F * s, (float) x, (float) (y + 1.33F * s), (float) z, 0.0F,
			vx, vy, vz, 0.0F});
		for (int sideSign = -1; sideSign <= 1; sideSign += 2) {
			float a = sideSign < 0 ? -armSwing : armSwing;
			double shx = x + sx * sideSign * 0.375F * s;
			double shz = z + sz * sideSign * 0.375F * s;
			double shy = y + 1.375F * s;
			double handX = shx + fx * Mth.sin(a) * 0.6F * s;
			double handY = shy - Mth.cos(a) * 0.6F * s;
			double handZ = shz + fz * Mth.sin(a) * 0.6F * s;
			out.add(new float[]{(float) shx, (float) shy, (float) shz, limbRadius, (float) handX, (float) handY, (float) handZ, 0.0F, vx, vy, vz, 0.0F});
		}
	}

	public void clear(Minecraft mc) {
		LongOpenHashSet was = new LongOpenHashSet(this.active);
		this.active.clear();
		this.columns.clear();
		this.slots.clear();
		this.free.clear();
		this.slotCount = 0;
		this.grabHeld = false;
		this.grabPending = false;
		this.centreX = Integer.MIN_VALUE;
		this.level = null;
		this.floorValid = false;
		hidden = new LongOpenHashSet();
		for (long key : was) {
			markColumnDirty(mc, key);
		}
	}

	private static void markColumnDirty(Minecraft mc, long key) {
		ClientLevel lvl = mc.level;
		if (lvl == null) {
			return;
		}
		int x = ChunkPos.getX(key) << 4;
		int z = ChunkPos.getZ(key) << 4;
		mc.levelExtractor.setBlocksDirty(x, lvl.getMinY(), z, x + 15, lvl.getMaxY(), z + 15);
	}
}
