package me.skaffy.client.trees;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.skaffy.client.water.WaterSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public final class TreeGrab {
	private static final TreeGrab INSTANCE = new TreeGrab();
	private static final int MAX_HELD = 64;
	private static final float ATTACHED_REACH = 0.3F;
	private static final float REGROW_SECONDS = 10.0F;
	static final int STICK = 2048;

	private static final class Held {
		final long pos;
		final int leaf;
		final double ox;
		final double oy;
		final double oz;
		Vec3 rest;
		boolean stick;
		float stickT;
		boolean out;
		int overTicks;
		Vec3 shown;
		Vec3 velocity = Vec3.ZERO;

		Held(long pos, int leaf, double ox, double oy, double oz, Vec3 rest) {
			this.pos = pos;
			this.leaf = leaf;
			this.ox = ox;
			this.oy = oy;
			this.oz = oz;
			this.rest = rest;
			this.shown = rest;
		}
	}

	private final List<Held> held = new ArrayList<>();
	private double holdDistance;
	private boolean holding;
	private long grabbedAt;
	private final Long2ObjectOpenHashMap<Int2LongOpenHashMap> removed = new Long2ObjectOpenHashMap<>();
	private final TreeLeafGen.Leaf scratch = new TreeLeafGen.Leaf();
	private int[] interest = new int[0];
	private final it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<float[]> motion = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();

	int[] interest() {
		return this.interest;
	}

	void motionRead(it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<float[]> read) {
		this.motion.putAll(read);
	}

	private void move(TreeLeafGen.Leaf l, int slot) {
		float[] m = this.motion.get(slot);
		if (m == null) {
			return;
		}
		int[] s = {TreeLeafGen.hash(l.seed ^ 0x51ED270B)};
		float omega = Mth.lerp(TreeLeafGen.rand(s), 5.0F, 9.0F);
		float angle = m[3] * 0.22F * (float) Math.sin(TreeRenderer.currentTime * omega + TreeLeafGen.rand(s) * 6.2831853F);
		float half = l.size * 0.5F;
		float sx = l.cx - l.ax * half;
		float sy = l.cy - l.ay * half;
		float sz = l.cz - l.az * half;
		float c = (float) Math.cos(angle);
		float sn = (float) Math.sin(angle);
		float ax = l.ax * c + l.nx * sn;
		float ay = l.ay * c + l.ny * sn;
		float az = l.az * c + l.nz * sn;
		l.nx = l.nx * c - l.ax * sn;
		l.ny = l.ny * c - l.ay * sn;
		l.nz = l.nz * c - l.az * sn;
		l.ax = ax;
		l.ay = ay;
		l.az = az;
		l.cx = sx + ax * half + m[0];
		l.cy = sy + ay * half + m[1];
		l.cz = sz + az * half + m[2];
	}

	private TreeLeafGen.Leaf leafNow(RealisticTrees trees, int slot, int leaf, int count) {
		long pos = trees.posOf(slot);
		int sides = trees.sidesOf(slot);
		TreeLeafGen.Leaf l = TreeLeafGen.leaf(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), sides & 63, sides >> 6 & 63, leaf, count,
			this.scratch);
		this.move(l, slot);
		return l;
	}

	private LongOpenHashSet blocksOnRay(ClientLevel level, RealisticTrees trees, Vec3 eye, Vec3 look, double[] reach) {
		LongOpenHashSet seen = new LongOpenHashSet();
		LongOpenHashSet blocks = new LongOpenHashSet();
		Vec3 end = eye.add(look.scale(reach[0]));
		for (double d = 0.0; d <= reach[0]; d += 0.05) {
			BlockPos pos = BlockPos.containing(eye.add(look.scale(d)));
			if (!seen.add(pos.asLong())) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof LeavesBlock) {
				if (trees.slotAt(pos.asLong()) >= 0) {
					blocks.add(pos.asLong());
					for (Direction dir : Direction.values()) {
						long n = BlockPos.offset(pos.asLong(), dir);
						if (trees.slotAt(n) >= 0) {
							blocks.add(n);
						}
					}
				}
				continue;
			}
			VoxelShape shape = state.getCollisionShape(level, pos, CollisionContext.empty());
			if (!shape.isEmpty()) {
				BlockHitResult hit = shape.clip(eye, end, pos);
				if (hit != null) {
					reach[0] = Math.min(reach[0], hit.getLocation().distanceTo(eye));
					break;
				}
			}
		}
		return blocks;
	}

	public static TreeGrab get() {
		return INSTANCE;
	}

	public void clear() {
		this.held.clear();
		this.holding = false;
		this.removed.clear();
	}

	private static long lifeMillis() {
		return (long) (Math.max(WaterSettings.treeFallenLeafLife, 1.0F) * 1000.0F);
	}

	private boolean isRemoved(long pos, int leaf) {
		Int2LongOpenHashMap m = this.removed.get(pos);
		return m != null && m.containsKey(leaf);
	}

	private Vec3 restOf(RealisticTrees trees, int slot, int leaf, int count) {
		long pos = trees.posOf(slot);
		TreeLeafGen.Leaf l = this.leafNow(trees, slot, leaf, count);
		return new Vec3(BlockPos.getX(pos) + l.cx, BlockPos.getY(pos) + l.cy, BlockPos.getZ(pos) + l.cz);
	}

	public boolean tryGrab(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (this.holding) {
			return true;
		}
		RealisticTrees trees = RealisticTrees.get();
		ClientLevel level = mc.level;
		if (player == null || level == null || !trees.wanted() || !player.getMainHandItem().isEmpty() || player.isSpectator()) {
			return false;
		}
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		double reach = player.blockInteractionRange();
		double[] reachBox = {reach};
		LongOpenHashSet blocks = this.blocksOnRay(level, trees, eye, look, reachBox);
		reach = reachBox[0];
		if (blocks.isEmpty()) {
			return false;
		}
		double bestT = reach;
		long bestPos = 0L;
		int bestLeaf = -1;
		for (long pos : blocks) {
			int slot = trees.slotAt(pos);
			int x = BlockPos.getX(pos);
			int y = BlockPos.getY(pos);
			int z = BlockPos.getZ(pos);
			int count = TreeLeafGen.count((float) eye.distanceTo(new Vec3(x + 0.5, y + 0.5, z + 0.5)));
			int sides = trees.sidesOf(slot);
			for (int i = 0; i < count; i++) {
				if (this.isRemoved(pos, i)) {
					continue;
				}
				TreeLeafGen.Leaf l = TreeLeafGen.leaf(x, y, z, sides & 63, sides >> 6 & 63, i, count, this.scratch);
				this.move(l, slot);
				double t = hitLeaf(l, x, y, z, eye, look);
				if (t >= 0.0 && t < bestT) {
					bestT = t;
					bestPos = pos;
					bestLeaf = i;
				}
			}
		}
		float bestStickT = 0.0F;
		boolean stickWins = false;
		for (long pos : blocks) {
			int slot = trees.slotAt(pos);
			int flags = trees.flagsOf(slot);
			for (int k = 0; k < TreeLeafGen.stickCount(flags); k++) {
				if (this.isRemoved(pos, STICK + k)) {
					continue;
				}
				double[] hit = this.hitStick(trees, slot, k, eye, look);
				if (hit != null && hit[0] < bestT) {
					bestT = hit[0];
					bestPos = pos;
					bestLeaf = STICK + k;
					bestStickT = (float) hit[1];
					stickWins = true;
				}
			}
		}
		if (bestLeaf < 0) {
			return false;
		}
		Vec3 hitPoint = eye.add(look.scale(bestT));
		this.held.clear();
		if (stickWins) {
			Vec3 rest = this.stickPoint(trees, trees.slotAt(bestPos), bestLeaf - STICK, bestStickT);
			Held h = new Held(bestPos, bestLeaf, rest.x - hitPoint.x, rest.y - hitPoint.y, rest.z - hitPoint.z, rest);
			h.stick = true;
			h.stickT = bestStickT;
			this.held.add(h);
			this.holdDistance = bestT;
			this.holding = true;
			this.grabbedAt = System.currentTimeMillis();
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
			return true;
		}
		int mainSlot = trees.slotAt(bestPos);
		int mainCount = TreeLeafGen.count((float) eye.distanceTo(Vec3.atCenterOf(BlockPos.of(bestPos))));
		Vec3 mainRest = this.restOf(trees, mainSlot, bestLeaf, mainCount);
		this.held.add(new Held(bestPos, bestLeaf, mainRest.x - hitPoint.x, mainRest.y - hitPoint.y, mainRest.z - hitPoint.z, mainRest));
		if (player.isShiftKeyDown()) {
			double radius = Mth.clamp(WaterSettings.treeGrabRadius, 0.01F, 2.0F);
			List<Object[]> near = new ArrayList<>();
			for (long pos : blocks) {
				int slot = trees.slotAt(pos);
				int count = TreeLeafGen.count((float) eye.distanceTo(Vec3.atCenterOf(BlockPos.of(pos))));
				for (int i = 0; i < count; i++) {
					if (pos == bestPos && i == bestLeaf || this.isRemoved(pos, i)) {
						continue;
					}
					Vec3 rest = this.restOf(trees, slot, i, count);
					double d = rest.distanceTo(hitPoint);
					if (d <= radius) {
						near.add(new Object[]{d, pos, i, rest});
					}
				}
			}
			near.sort(Comparator.comparingDouble(o -> (double) o[0]));
			for (int k = 0; k < near.size() && this.held.size() < MAX_HELD; k++) {
				Object[] o = near.get(k);
				Vec3 rest = (Vec3) o[3];
				this.held.add(new Held((long) o[1], (int) o[2], rest.x - hitPoint.x, rest.y - hitPoint.y, rest.z - hitPoint.z, rest));
			}
		}
		this.holdDistance = bestT;
		this.holding = true;
		this.grabbedAt = System.currentTimeMillis();
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		return true;
	}

	private Vec3 stickPoint(RealisticTrees trees, int slot, int k, float t) {
		long pos = trees.posOf(slot);
		TreeLeafGen.Stick st = TreeLeafGen.stick(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), trees.flagsOf(slot), trees.carriedOf(slot), k);
		float[] p = st.at(t);
		float[] m = this.motion.get(slot);
		double sx = m != null ? m[0] : 0.0;
		double sy = m != null ? m[1] : 0.0;
		double sz = m != null ? m[2] : 0.0;
		return new Vec3(BlockPos.getX(pos) + p[0] + sx, BlockPos.getY(pos) + p[1] + sy, BlockPos.getZ(pos) + p[2] + sz);
	}

	private double @Nullable [] hitStick(RealisticTrees trees, int slot, int k, Vec3 eye, Vec3 look) {
		long pos = trees.posOf(slot);
		TreeLeafGen.Stick st = TreeLeafGen.stick(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos), trees.flagsOf(slot), trees.carriedOf(slot), k);
		float[] m = this.motion.get(slot);
		double ox = BlockPos.getX(pos) + (m != null ? m[0] : 0.0);
		double oy = BlockPos.getY(pos) + (m != null ? m[1] : 0.0);
		double oz = BlockPos.getZ(pos) + (m != null ? m[2] : 0.0);
		final int pieces = 4;
		double[] best = null;
		for (int i = 0; i < pieces; i++) {
			float t0 = i / (float) pieces;
			float t1 = (i + 1) / (float) pieces;
			float[] a = st.at(t0);
			float[] b = st.at(t1);
			Vec3 pa = new Vec3(ox + a[0], oy + a[1], oz + a[2]);
			Vec3 pb = new Vec3(ox + b[0], oy + b[1], oz + b[2]);
			Vec3 u = look;
			Vec3 v = pb.subtract(pa);
			Vec3 w = eye.subtract(pa);
			double aa = u.dot(u);
			double bb = u.dot(v);
			double cc = v.dot(v);
			double dd = u.dot(w);
			double ee = v.dot(w);
			double den = aa * cc - bb * bb;
			double sRay;
			double sSeg;
			if (den < 1e-9) {
				sRay = 0.0;
				sSeg = cc > 0.0 ? Mth.clamp(ee / cc, 0.0, 1.0) : 0.0;
			} else {
				sSeg = Mth.clamp((aa * ee - bb * dd) / den, 0.0, 1.0);
				sRay = Math.max((bb * sSeg - dd) / aa, 0.0);
			}
			Vec3 onRay = eye.add(u.scale(sRay));
			Vec3 onSeg = pa.add(v.scale(sSeg));
			double radius = Math.max(Mth.lerp((float) ((t0 + (t1 - t0) * sSeg)), st.ra, st.rb), 0.03) + 0.03;
			if (onRay.distanceTo(onSeg) <= radius && (best == null || sRay < best[0])) {
				best = new double[]{sRay, t0 + (t1 - t0) * sSeg};
			}
		}
		return best;
	}

	private static double hitLeaf(TreeLeafGen.Leaf l, int x, int y, int z, Vec3 eye, Vec3 look) {
		double cx = x + l.cx;
		double cy = y + l.cy;
		double cz = z + l.cz;
		double denom = l.nx * look.x + l.ny * look.y + l.nz * look.z;
		if (Math.abs(denom) < 1e-6) {
			return -1.0;
		}
		double t = (l.nx * (cx - eye.x) + l.ny * (cy - eye.y) + l.nz * (cz - eye.z)) / denom;
		if (t < 0.0) {
			return -1.0;
		}
		double qx = eye.x + look.x * t - cx;
		double qy = eye.y + look.y * t - cy;
		double qz = eye.z + look.z * t - cz;
		double u = (qx * l.bx + qy * l.by + qz * l.bz) / (l.size * 0.5);
		double v = (qx * l.ax + qy * l.ay + qz * l.az) / (l.size * 0.6);
		return u * u + v * v <= 1.0 ? t : -1.0;
	}

	private Vec3 target(Held h, Vec3 eye, Vec3 look) {
		return eye.add(look.scale(this.holdDistance)).add(h.ox, h.oy, h.oz);
	}

	private static Vec3 attached(Held h, Vec3 target) {
		Vec3 d = target.subtract(h.rest);
		double len = d.length();
		return len > ATTACHED_REACH ? h.rest.add(d.scale(ATTACHED_REACH / len)) : target;
	}

	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		if (level == null || player == null) {
			this.clear();
			return;
		}
		long now = System.currentTimeMillis();
		long life = lifeMillis() + (long) (REGROW_SECONDS * 1000.0F);
		this.removed.long2ObjectEntrySet().removeIf(e -> {
			if (!(level.getBlockState(BlockPos.of(e.getLongKey())).getBlock() instanceof LeavesBlock)) {
				return true;
			}
			e.getValue().values().removeIf(t -> now - t > life);
			return e.getValue().isEmpty();
		});
		RealisticTrees trees = RealisticTrees.get();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		it.unimi.dsi.fastutil.ints.IntArrayList want = new it.unimi.dsi.fastutil.ints.IntArrayList();
		if (trees.wanted() && player.getMainHandItem().isEmpty()) {
			for (long b : this.blocksOnRay(level, trees, eye, look, new double[]{player.blockInteractionRange()})) {
				int slot = trees.slotAt(b);
				if (slot >= 0 && want.size() < 64) {
					want.add(slot);
				}
			}
		}
		for (Held h : this.held) {
			int slot = trees.slotAt(h.pos);
			if (slot >= 0 && !want.contains(slot) && want.size() < 64) {
				want.add(slot);
			}
		}
		this.interest = want.toIntArray();
		this.motion.keySet().retainAll(want);
		if (!this.holding) {
			return;
		}
		if (!mc.options.keyUse.isDown() || !trees.wanted() || !player.getMainHandItem().isEmpty()) {
			this.release(mc);
			return;
		}
		float pull = Math.max(WaterSettings.treeLeafPullDistance, 0.05F);
		for (Held h : this.held) {
			Vec3 target = this.target(h, eye, look);
			int slot = trees.slotAt(h.pos);
			if (!h.out && slot >= 0) {
				h.rest = h.stick ? this.stickPoint(trees, slot, h.leaf - STICK, h.stickT)
					: this.restOf(trees, slot, h.leaf, TreeLeafGen.count((float) eye.distanceTo(Vec3.atCenterOf(BlockPos.of(h.pos)))));
			}
			if (!h.out) {
				h.overTicks = target.distanceTo(h.rest) > pull ? h.overTicks + 1 : 0;
				if (h.overTicks >= 5) {
					h.out = true;
					this.removed.computeIfAbsent(h.pos, k -> new Int2LongOpenHashMap()).put(h.leaf, now);
				}
			}
			Vec3 shown = h.out ? target : attached(h, target);
			h.velocity = h.velocity.scale(0.5).add(shown.subtract(h.shown).scale(20.0 * 0.5));
			h.shown = shown;
		}
	}

	private void release(Minecraft mc) {
		RealisticTrees trees = RealisticTrees.get();
		for (Held h : this.held) {
			if (!h.out) {
				continue;
			}
			int slot = trees.slotAt(h.pos);
			BlockState state = trees.stateOf(slot);
			if (state != null && h.stick) {
				Vec3 v = h.velocity.scale(0.5);
				double speed = v.length();
				if (speed > 3.0) {
					v = v.scale(3.0 / speed);
				}
				FallenLeaves.get().spawnStick(h.shown, v, state);
			} else if (state != null) {
				Vec3 v = h.velocity.scale(0.5);
				double speed = v.length();
				if (speed > 3.0) {
					v = v.scale(3.0 / speed);
				}
				long p = h.pos;
				int sides = trees.sidesOf(slot);
				int count = TreeLeafGen.count((float) mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos
					.distanceTo(Vec3.atCenterOf(BlockPos.of(p))));
				TreeLeafGen.Leaf leaf = TreeLeafGen.leaf(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), sides & 63, sides >> 6 & 63, h.leaf, count,
					new TreeLeafGen.Leaf());
				FallenLeaves.get().spawn(h.shown, v, state, trees.tintOf(slot), leaf);
			}
		}
		this.held.clear();
		this.holding = false;
	}

	float[] prepare(RealisticTrees trees) {
		trees.modPointers.clear();
		if (this.removed.isEmpty() && this.held.isEmpty()) {
			return new float[4];
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		float partial = mc.gameRenderer.gameRenderState().levelRenderState.worldPartialTicks;
		var camState = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		int bx = Mth.floor(camState.pos.x);
		int by = Mth.floor(camState.pos.y);
		int bz = Mth.floor(camState.pos.z);
		Vec3 eye = player != null ? player.getEyePosition(partial) : Vec3.ZERO;
		Vec3 look = player != null ? player.getViewVector(partial) : new Vec3(0.0, 0.0, 1.0);
		long now = System.currentTimeMillis();
		long life = lifeMillis();
		Long2ObjectOpenHashMap<FloatArrayList> perBlock = new Long2ObjectOpenHashMap<>();
		for (Long2ObjectOpenHashMap.Entry<Int2LongOpenHashMap> e : this.removed.long2ObjectEntrySet()) {
			for (Int2LongOpenHashMap.Entry r : e.getValue().int2LongEntrySet()) {
				float age = (now - r.getLongValue()) / 1000.0F;
				float growth = (now - r.getLongValue() - life) / (REGROW_SECONDS * 1000.0F);
				int kind = growth > 0.0F ? 3 : 1;
				perBlock.computeIfAbsent(e.getLongKey(), k -> new FloatArrayList())
					.addAll(FloatArrayList.wrap(new float[]{r.getIntKey(), kind, Mth.clamp(growth, 0.0F, 1.0F), age, 0.0F, 0.0F, 0.0F, 0.0F}));
			}
		}
		float blend = Mth.clamp((now - this.grabbedAt) / 120.0F, 0.0F, 1.0F);
		for (Held h : this.held) {
			Vec3 target = this.target(h, eye, look);
			Vec3 shown = h.out ? target : attached(h, target);
			FloatArrayList list = perBlock.computeIfAbsent(h.pos, k -> new FloatArrayList());
			for (int k = 0; k < list.size(); k += 8) {
				if ((int) list.getFloat(k) == h.leaf) {
					list.removeElements(k, k + 8);
					break;
				}
			}
			if (h.stick) {
				list.addAll(FloatArrayList.wrap(new float[]{h.leaf, 2, 1.0F, 0.0F, (float) (shown.x - h.rest.x), (float) (shown.y - h.rest.y),
					(float) (shown.z - h.rest.z), blend}));
			} else {
				list.addAll(FloatArrayList.wrap(new float[]{h.leaf, 2, 1.0F, 0.0F, (float) (shown.x - bx), (float) (shown.y - by), (float) (shown.z - bz),
					blend}));
			}
		}
		FloatArrayList out = new FloatArrayList();
		for (Long2ObjectOpenHashMap.Entry<FloatArrayList> e : perBlock.long2ObjectEntrySet()) {
			int slot = trees.slotAt(e.getLongKey());
			FloatArrayList list = e.getValue();
			if (slot < 0 || list.isEmpty()) {
				continue;
			}
			int start = out.size() / 4;
			if (start + 1 >= 0xFFFF) {
				break;
			}
			out.add(list.size() / 8.0F);
			out.add(0.0F);
			out.add(0.0F);
			out.add(0.0F);
			out.addAll(list);
			trees.modPointers.put(slot, start + 1);
		}
		if (out.isEmpty()) {
			return new float[4];
		}
		return out.toFloatArray();
	}

	public boolean holding() {
		return this.holding;
	}

	@Nullable Vec3 debugTarget() {
		return this.held.isEmpty() ? null : this.held.get(0).shown;
	}
}
