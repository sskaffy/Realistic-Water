package me.skaffy.client.trees;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.block.FallenLeafHooks;
import me.skaffy.block.LeafPileBlockEntity;
import me.skaffy.block.RealisticLeavesBlock;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterWorld;
import me.skaffy.net.FallenLeavesPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class FallenLeaves {
	private static final FallenLeaves INSTANCE = new FallenLeaves();
	private static final int LOOKS = 12;
	private static final int SAVE_TICKS = 200;

	private record Look(Block block, int tint, boolean vanilla, boolean stick, String key) {
	}

	private final Look[] looks = new Look[LOOKS];
	private final long[] lookUsed = new long[LOOKS];
	private long useClock;
	private final VkBuf[] lookBufs = new VkBuf[Vk.FRAME_RING];
	private @Nullable VkBuf emptyAtlas;

	private final Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<FloatArrayList>> timers = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<List<Long>> pending = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private final LongOpenHashSet restored = new LongOpenHashSet();
	private int saveTimer;
	private @Nullable ClientLevel level;

	public static FallenLeaves get() {
		return INSTANCE;
	}

	public static void init() {
		FallenLeafHooks.client = pile -> Minecraft.getInstance().execute(() -> INSTANCE.onPileData(pile));
	}

	public void clear() {
		this.timers.clear();
		this.pending.clear();
		this.dirty.clear();
		this.restored.clear();
		java.util.Arrays.fill(this.looks, null);
	}

	private static float life() {
		return Math.max(WaterSettings.treeFallenLeafLife, 1.0F);
	}

	private static long pendingKey(long block, int kind) {
		return block * 31L + kind;
	}


	int kindFor(Block block, int tint, boolean vanilla) {
		return this.kindFor(block, tint, vanilla, false);
	}

	private static final float STICK_LENGTH = 0.35F;
	private static final float STICK_ASPECT = 0.14F;

	int kindFor(Block block, int tint, boolean vanilla, boolean stick) {
		int best = -1;
		float bestDiff = Float.MAX_VALUE;
		int free = -1;
		int oldest = -1;
		for (int i = 0; i < LOOKS; i++) {
			Look l = this.looks[i];
			if (l == null) {
				if (free < 0) {
					free = i;
				}
				continue;
			}
			if (oldest < 0 || this.lookUsed[i] < this.lookUsed[oldest]) {
				oldest = i;
			}
			if (l.block == block && l.vanilla == vanilla && l.stick == stick) {
				float diff = Math.abs((l.tint >> 16 & 255) - (tint >> 16 & 255)) + Math.abs((l.tint >> 8 & 255) - (tint >> 8 & 255))
					+ Math.abs((l.tint & 255) - (tint & 255));
				if (diff < bestDiff) {
					bestDiff = diff;
					best = i;
				}
			}
		}
		int i;
		if (best >= 0 && bestDiff <= 18.0F) {
			i = best;
		} else if (free >= 0 || best < 0) {
			i = free >= 0 ? free : oldest;
			Identifier id = BuiltInRegistries.BLOCK.getKey(block);
			this.looks[i] = new Look(block, tint, vanilla, stick, id + "|" + tint + "|" + (vanilla ? 1 : 0) + "|" + (stick ? 1 : 0));
		} else {
			i = best;
		}
		this.lookUsed[i] = ++this.useClock;
		return 4 + i;
	}

	private @Nullable Look look(int kind) {
		return kind >= 4 && kind < 4 + LOOKS ? this.looks[kind - 4] : null;
	}

	private int kindForKey(String key) {
		String[] parts = key.split("\\|");
		if (parts.length < 3) {
			return 4;
		}
		Block block = BuiltInRegistries.BLOCK.getValue(Identifier.tryParse(parts[0]));
		if (block == Blocks.AIR) {
			block = Blocks.OAK_LEAVES;
		}
		int tint;
		try {
			tint = Integer.parseInt(parts[1]);
		} catch (NumberFormatException e) {
			tint = -1;
		}
		return this.kindFor(block, tint, "1".equals(parts[2]), parts.length > 3 && "1".equals(parts[3]));
	}

	public Desc looksDesc() {
		int slot = Vk.ringSlot();
		if (this.lookBufs[slot] == null) {
			this.lookBufs[slot] = VkBuf.upload(LOOKS * 16L, VkBuf.STORAGE);
		}
		VkBuf buf = this.lookBufs[slot];
		java.nio.ByteBuffer m = buf.mapped();
		TreeAtlas atlas = RealisticTrees.get().atlas();
		for (int i = 0; i < LOOKS; i++) {
			Look l = this.looks[i];
			int cell = -1;
			int color = 0xFF6A8F3A;
			int piece = 0;
			if (l != null && atlas != null) {
				TreeAtlas.Kind kind = atlas.kind(l.block);
				if (l.stick) {
					cell = l.vanilla ? kind.barkCell() : TreeAtlas.WHITE;
					color = l.vanilla ? 0xFFB8B8B8 : scaleRgb(kind.barkColor(), 0.85F * 0.72F);
					piece = -Math.round(STICK_ASPECT * 1000.0F);
				} else if (l.vanilla) {
					cell = kind.leafCell();
					color = l.tint;
					piece = Math.round(Math.min(baseSize(true), 1.0F) * 1000.0F);
				} else {
					int avg = kind.leafColor();
					int r = Math.min(255, Math.round((avg >> 16 & 255) * (l.tint >> 16 & 255) / 255.0F * 1.38F));
					int g = Math.min(255, Math.round((avg >> 8 & 255) * (l.tint >> 8 & 255) / 255.0F * 1.38F));
					int b = Math.min(255, Math.round((avg & 255) * (l.tint & 255) / 255.0F * 1.38F));
					color = 0xFF000000 | r << 16 | g << 8 | b;
				}
			}
			int size = l == null ? 0 : l.stick ? Math.round(STICK_LENGTH * 1000.0F) : Math.round(baseSize(l.vanilla) * 1000.0F);
			m.putInt(i * 16, cell).putInt(i * 16 + 4, color).putInt(i * 16 + 8, piece).putInt(i * 16 + 12, size);
		}
		buf.flush(0, LOOKS * 16L);
		return Desc.ssbo(buf, 0, LOOKS * 16L);
	}

	public record PackLeaf(float u0, float v0, float u1, float v1, int color, float size, float aspect) {
	}

	public static net.minecraft.resources.Identifier atlasTexture(boolean shadow) {
		return TreeAtlas.FALLEN_ID;
	}

	public PackLeaf packLeaf(int kind, int yawBits) {
		Look l = this.look(kind);
		TreeAtlas atlas = RealisticTrees.get().atlas();
		int h = TreeLeafGen.hash(yawBits * (int) 2654435761L ^ kind);
		int cell = h & 3;
		float u0 = 0.0F;
		float v0 = 0.0F;
		float w = 1.0F;
		int color = 0xFF6A8F3A;
		if (l != null && atlas != null) {
			TreeAtlas.Kind k = atlas.kind(l.block);
			if (l.stick) {
				cell = l.vanilla ? k.barkCell() : TreeAtlas.WHITE;
				color = l.vanilla ? 0xFFB8B8B8 : scaleRgb(k.barkColor(), 0.85F * 0.72F);
			} else if (l.vanilla) {
				cell = k.leafCell();
				w = Math.min(baseSize(true), 1.0F);
				u0 = ((h >>> 4) & 255) / 255.0F * (1.0F - w);
				v0 = ((h >>> 12) & 255) / 255.0F * (1.0F - w);
				color = l.tint;
			} else {
				int h2 = TreeLeafGen.hash(h ^ 0x68E31DA4);
				float variation = Mth.lerp((h2 & 255) / 255.0F, 0.82F, 1.15F);
				float t = ((h2 >>> 8) & 255) / 255.0F;
				float hr = Mth.lerp(t, 1.05F, 0.92F);
				float hg = Mth.lerp(t, 0.98F, 1.03F);
				float hb = Mth.lerp(t, 1.35F, 1.06F);
				int avg = k.leafColor();
				int r = Math.min(255, Math.round((avg >> 16 & 255) * (l.tint >> 16 & 255) / 255.0F * 1.44F * variation * hr));
				int g = Math.min(255, Math.round((avg >> 8 & 255) * (l.tint >> 8 & 255) / 255.0F * 1.44F * variation * hg));
				int b = Math.min(255, Math.round((avg & 255) * (l.tint & 255) / 255.0F * 1.44F * variation * hb));
				color = 0xFF000000 | r << 16 | g << 8 | b;
			}
		}
		float cx = cell % TreeAtlas.CELLS;
		float cy = cell / TreeAtlas.CELLS;
		float inv = 1.0F / TreeAtlas.CELLS;
		int h2 = TreeLeafGen.hash(h ^ 0x68E31DA4);
		float base = l == null ? 0.25F : l.stick ? STICK_LENGTH : baseSize(l.vanilla);
		float size = base * Mth.lerp(((h2 >>> 16) & 255) / 255.0F, 0.8F, 1.2F);
		return new PackLeaf((cx + u0 + 0.002F) * inv, (cy + v0 + 0.002F) * inv, (cx + u0 + w - 0.002F) * inv, (cy + v0 + w - 0.002F) * inv, color,
			size, l != null && l.stick ? STICK_ASPECT : 1.0F);
	}

	private static int scaleRgb(int argb, float f) {
		int r = Math.min(255, Math.round((argb >> 16 & 255) * f));
		int g = Math.min(255, Math.round((argb >> 8 & 255) * f));
		int b = Math.min(255, Math.round((argb & 255) * f));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	public Desc atlasDesc() {
		TreeAtlas atlas = RealisticTrees.get().atlas();
		if (atlas != null) {
			return Desc.ssbo(atlas.pixels);
		}
		if (this.emptyAtlas == null) {
			this.emptyAtlas = VkBuf.device(16L, 0);
		}
		return Desc.ssbo(this.emptyAtlas);
	}


	private static float baseSize(boolean vanilla) {
		return (vanilla ? 0.3125F : 0.21F) * TreeLeafGen.sizeScale();
	}

	private static int shaded(int tint, float ao) {
		float q = ao > 0.93F ? 1.0F : ao > 0.78F ? 0.85F : ao > 0.63F ? 0.7F : 0.55F;
		int r = Math.round((tint >> 16 & 255) * q);
		int g = Math.round((tint >> 8 & 255) * q);
		int b = Math.round((tint & 255) * q);
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	private static int yawFor(int kind, TreeLeafGen.Leaf leaf, boolean vanilla) {
		int[] s = {TreeLeafGen.hash(leaf.seed ^ 0x68E31DA4)};
		float a = TreeLeafGen.rand(s);
		float b = TreeLeafGen.rand(s);
		int cell = leaf.seed & 3;
		float size = Mth.clamp((leaf.size / baseSize(vanilla) - 0.8F) / 0.4F, 0.0F, 1.0F);
		int best = 0;
		float bestScore = Float.MAX_VALUE;
		for (int yaw = 0; yaw < 64; yaw++) {
			int h = TreeLeafGen.hash(yaw * (int) 2654435761L ^ kind);
			int h2 = TreeLeafGen.hash(h ^ 0x68E31DA4);
			float score;
			if (vanilla) {
				float ca = ((h >>> 4) & 255) / 255.0F - a;
				float cb = ((h >>> 12) & 255) / 255.0F - b;
				score = ca * ca + cb * cb;
			} else {
				if ((h & 3) != cell) {
					continue;
				}
				float va = (h2 & 255) / 255.0F - a;
				float hu = ((h2 >>> 8) & 255) / 255.0F - b;
				score = va * va + hu * hu;
			}
			float sz = ((h2 >>> 16) & 255) / 255.0F - size;
			score += 0.5F * sz * sz;
			if (score < bestScore) {
				bestScore = score;
				best = yaw;
			}
		}
		return best;
	}

	public void spawnStick(Vec3 at, Vec3 velocity, BlockState leaves) {
		int kind = this.kindFor(leaves.getBlock(), 0xFFFFFFFF, WaterSettings.treeVanillaTextures, true);
		WaterWorld.get().spawnLeaf(at.x, at.y, at.z, (float) velocity.x, (float) velocity.y, (float) velocity.z, kind);
	}

	public void spawn(Vec3 at, Vec3 velocity, BlockState leaves, int tint, TreeLeafGen.Leaf leaf) {
		boolean vanilla = WaterSettings.treeVanillaTextures;
		int kind = this.kindFor(leaves.getBlock(), shaded(tint, leaf.ao), vanilla);
		int code = kind + 16 * (yawFor(kind, leaf, vanilla) + 1);
		WaterWorld.get().spawnLeaf(at.x, at.y, at.z, (float) velocity.x, (float) velocity.y, (float) velocity.z, code);
	}

	public int restore(ClientLevel lvl, BlockPos pos, int totalLeaves) {
		if (!(lvl.getBlockEntity(pos) instanceof LeafPileBlockEntity pile) || pile.leaves().length == 0) {
			return 0;
		}
		String[] keys = pile.looks().isEmpty() ? new String[0] : pile.looks().split(";");
		int[] kinds = new int[keys.length];
		for (int i = 0; i < keys.length; i++) {
			kinds[i] = this.kindForKey(keys[i]);
		}
		int[] leaves = pile.leaves();
		int n = Math.min(leaves.length, totalLeaves);
		long key = pos.asLong();
		boolean takeTimers = WaterWorld.isHostClient() && !this.timers.containsKey(key);
		Int2ObjectOpenHashMap<FloatArrayList> perKind = new Int2ObjectOpenHashMap<>();
		java.util.Random random = new java.util.Random(key);
		for (int i = 0; i < n; i++) {
			int look = leaves[i] >>> 16;
			int kind = look < kinds.length ? kinds[look] : 4;
			float remaining = leaves[i] & 0xFFFF;
			if (takeTimers) {
				perKind.computeIfAbsent(kind, k -> new FloatArrayList()).add(remaining);
			}
			double x = pos.getX() + 0.1 + random.nextDouble() * 0.8;
			double z = pos.getZ() + 0.1 + random.nextDouble() * 0.8;
			double y = pos.getY() + 0.02 + random.nextDouble() * 0.2;
			WaterWorld.get().spawnLeaf(x, y, z, 0.0F, 0.0F, 0.0F, kind);
		}
		if (takeTimers && !perKind.isEmpty()) {
			this.timers.put(key, perKind);
		}
		this.restored.add(key);
		return n;
	}

	private void onPileData(LeafPileBlockEntity pile) {
	}


	public void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		if (lvl != this.level) {
			this.clear();
			this.level = lvl;
		}
		if (lvl == null || !WaterWorld.isHostClient() || mc.isPaused()) {
			return;
		}
		this.reconcile(lvl);
		this.age(mc, lvl);
		if (++this.saveTimer >= SAVE_TICKS) {
			this.saveTimer = 0;
			this.save(lvl);
		}
	}

	private void reconcile(ClientLevel lvl) {
		for (WaterWorld.LeafTally t : WaterWorld.get().takeLeafTallies()) {
			int[] w = t.window();
			Long2ObjectOpenHashMap<Int2IntOpenHashMap> seen = new Long2ObjectOpenHashMap<>();
			for (int[] leaf : t.leaves()) {
				long block = BlockPos.asLong(leaf[0], leaf[1], leaf[2]);
				seen.computeIfAbsent(block, k -> new Int2IntOpenHashMap()).addTo(leaf[3], 1);
			}
			if (!t.complete()) {
				continue;
			}
			LongOpenHashSet blocks = new LongOpenHashSet(seen.keySet());
			for (long b : this.timers.keySet()) {
				int x = BlockPos.getX(b);
				int y = BlockPos.getY(b);
				int z = BlockPos.getZ(b);
				if (x >= w[0] && y >= w[1] && z >= w[2] && x < w[3] && y < w[4] && z < w[5]) {
					blocks.add(b);
				}
			}
			for (long block : blocks) {
				Int2IntOpenHashMap found = seen.getOrDefault(block, new Int2IntOpenHashMap());
				Int2ObjectOpenHashMap<FloatArrayList> mine = this.timers.computeIfAbsent(block, k -> new Int2ObjectOpenHashMap<>());
				it.unimi.dsi.fastutil.ints.IntOpenHashSet kinds = new it.unimi.dsi.fastutil.ints.IntOpenHashSet(found.keySet());
				kinds.addAll(mine.keySet());
				for (int kind : kinds) {
					int tally = found.get(kind);
					List<Long> frames = this.pending.get(pendingKey(block, kind));
					if (frames != null) {
						frames.removeIf(f -> f + 8L < t.frame());
						for (long f : frames) {
							if (f > t.frame()) {
								tally--;
							}
						}
						if (frames.isEmpty()) {
							this.pending.remove(pendingKey(block, kind));
						}
					}
					tally = Math.max(tally, 0);
					FloatArrayList list = mine.computeIfAbsent(kind, k -> new FloatArrayList());
					if (tally > list.size()) {
						for (int k = list.size(); k < tally; k++) {
							list.add(life());
						}
						this.dirty.add(block);
					} else if (tally < list.size()) {
						list.sort(null);
						list.size(tally);
						this.dirty.add(block);
					}
					if (list.isEmpty()) {
						mine.remove(kind);
					}
				}
				if (mine.isEmpty()) {
					this.timers.remove(block);
				}
			}
		}
	}

	private void age(Minecraft mc, ClientLevel lvl) {
		Entity view = mc.getCameraEntity();
		if (view == null) {
			return;
		}
		double range = Math.max(8, WaterSettings.treeLeafRadius);
		List<int[]> expired = new ArrayList<>();
		for (Long2ObjectOpenHashMap.Entry<Int2ObjectOpenHashMap<FloatArrayList>> e : this.timers.long2ObjectEntrySet()) {
			long block = e.getLongKey();
			int x = BlockPos.getX(block);
			int y = BlockPos.getY(block);
			int z = BlockPos.getZ(block);
			double dx = x + 0.5 - view.getX();
			double dz = z + 0.5 - view.getZ();
			if (dx * dx + dz * dz > range * range || !lvl.hasChunk(x >> 4, z >> 4) || !WaterWorld.get().leavesSimulated(BlockPos.of(block))) {
				continue;
			}
			for (Int2ObjectOpenHashMap.Entry<FloatArrayList> k : e.getValue().int2ObjectEntrySet()) {
				FloatArrayList list = k.getValue();
				int done = 0;
				for (int i = 0; i < list.size(); i++) {
					float left = list.getFloat(i) - 0.05F;
					list.set(i, left);
					if (left <= 0.0F) {
						done++;
					}
				}
				if (done > 0) {
					list.sort(null);
					list.removeElements(0, done);
					expired.add(new int[]{x, y, z, k.getIntKey(), done});
					this.dirty.add(block);
				}
			}
		}
		long frame = Vk.frame();
		for (int[] r : expired) {
			if (WaterWorld.get().expireLeaves(new BlockPos(r[0], r[1], r[2]), r[3], r[4])) {
				List<Long> frames = this.pending.computeIfAbsent(pendingKey(BlockPos.asLong(r[0], r[1], r[2]), r[3]), k -> new ArrayList<>());
				for (int i = 0; i < r[4]; i++) {
					frames.add(frame);
				}
			}
		}
		this.timers.long2ObjectEntrySet().removeIf(e -> {
			e.getValue().int2ObjectEntrySet().removeIf(k -> k.getValue().isEmpty());
			return e.getValue().isEmpty();
		});
	}

	private void save(ClientLevel lvl) {
		if (this.dirty.isEmpty() || !ClientPlayNetworking.canSend(FallenLeavesPayload.TYPE)) {
			return;
		}
		List<FallenLeavesPayload.Entry> out = new ArrayList<>();
		LongOpenHashSet keep = new LongOpenHashSet();
		for (long block : this.dirty) {
			BlockPos pos = BlockPos.of(block);
			if (!(lvl.getBlockState(pos).getBlock() instanceof RealisticLeavesBlock)) {
				if (this.timers.containsKey(block)) {
					keep.add(block);
				}
				continue;
			}
			Int2ObjectOpenHashMap<FloatArrayList> mine = this.timers.get(block);
			StringBuilder keys = new StringBuilder();
			it.unimi.dsi.fastutil.ints.IntArrayList leaves = new it.unimi.dsi.fastutil.ints.IntArrayList();
			if (mine != null) {
				int index = 0;
				for (Int2ObjectOpenHashMap.Entry<FloatArrayList> k : mine.int2ObjectEntrySet()) {
					Look look = this.look(k.getIntKey());
					if (look == null) {
						continue;
					}
					if (index > 0) {
						keys.append(';');
					}
					keys.append(look.key);
					for (int i = 0; i < k.getValue().size(); i++) {
						int seconds = Mth.clamp(Mth.ceil(k.getValue().getFloat(i)), 1, 0xFFFF);
						leaves.add(seconds | index << 16);
					}
					index++;
				}
			}
			out.add(new FallenLeavesPayload.Entry(block, keys.toString(), leaves.toIntArray()));
			if (out.size() >= 512) {
				break;
			}
		}
		for (FallenLeavesPayload.Entry e : out) {
			this.dirty.remove(e.pos());
		}
		this.dirty.removeIf(b -> !keep.contains(b) && !this.timers.containsKey(b) && out.stream().noneMatch(e -> e.pos() == b));
		if (!out.isEmpty()) {
			ClientPlayNetworking.send(new FallenLeavesPayload(out));
		}
	}

	public void close() {
		for (VkBuf b : this.lookBufs) {
			if (b != null) {
				Vk.destroyLater(b);
			}
		}
		if (this.emptyAtlas != null) {
			Vk.destroyLater(this.emptyAtlas);
			this.emptyAtlas = null;
		}
		java.util.Arrays.fill(this.lookBufs, null);
	}

	String debugLine() {
		int n = 0;
		float soonest = Float.MAX_VALUE;
		for (Int2ObjectOpenHashMap<FloatArrayList> m : this.timers.values()) {
			for (FloatArrayList l : m.values()) {
				n += l.size();
				for (int i = 0; i < l.size(); i++) {
					soonest = Math.min(soonest, l.getFloat(i));
				}
			}
		}
		return n + " fallen leaves, next in " + (n > 0 ? Math.round(soonest) + " s" : "-");
	}
}
