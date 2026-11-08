package me.skaffy.client.trees;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.RealisticWater;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.water.WaterSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class RealisticTrees {
	private static final RealisticTrees INSTANCE = new RealisticTrees();
	private static volatile LongSet hidden = new LongOpenHashSet();

	private static final int MARGIN = 8;
	private static final int SPAN = 16 + 2 * MARGIN;
	private static final int MAX_PATH = 75;
	static final int NO_PARENT = 31;
	private static final byte AIR = 0;
	private static final byte LEAF = 1;
	private static final byte LOG = 2;
	private static final byte OPAQUE = 3;
	static final int[][] NEIGHBOURS = new int[26][];
	private static final int[] STEP_COST = new int[26];

	static {
		int i = 0;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;
					}
					NEIGHBOURS[i] = new int[]{dx, dy, dz};
					int axes = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
					STEP_COST[i] = axes == 1 ? 10 : axes == 2 ? 14 : 17;
					i++;
				}
			}
		}
	}

	private @Nullable ClientLevel level;
	private @Nullable TreeAtlas atlas;
	private int centreX = Integer.MIN_VALUE;
	private int centreZ;
	private int radius;
	private int builtRule = -1;
	private final LongOpenHashSet active = new LongOpenHashSet();
	private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
	private final LongOpenHashSet queued = new LongOpenHashSet();
	private final Long2ObjectOpenHashMap<LongArrayList> columns = new Long2ObjectOpenHashMap<>();
	private final Long2IntOpenHashMap slots = new Long2IntOpenHashMap();
	private final IntArrayList free = new IntArrayList();
	int slotCount;
	private long[] slotPos = new long[0];
	private BlockState[] slotState = new BlockState[0];
	private int[] slotFlags = new int[0];
	private int[] slotDesc = new int[0];
	private int[] slotGen = new int[0];
	private int nextGen = 1;
	private float[] slotLook = new float[0];
	private int lookCursor;
	int revision;
	private final Reference2ByteOpenHashMap<BlockState> classes = new Reference2ByteOpenHashMap<>();

	private RealisticTrees() {
		this.slots.defaultReturnValue(-1);
	}

	public static RealisticTrees get() {
		return INSTANCE;
	}

	public static void init() {
		TreeModel.register();
	}

	public static boolean hides(BlockPos pos) {
		LongSet h = hidden;
		return !h.isEmpty() && h.contains(ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
	}

	static boolean isLeaves(BlockState state) {
		return state.getBlock() instanceof LeavesBlock;
	}

	public boolean wanted() {
		return WaterSettings.realisticTreeLeaves && this.level != null && this.slotCount > 0 && this.atlas != null;
	}

	@Nullable TreeAtlas atlas() {
		return this.atlas;
	}

	final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap modPointers = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();

	int slotAt(long pos) {
		return this.slots.get(pos);
	}

	int sidesOf(int slot) {
		return this.slotFlags[slot] & 0xFFF;
	}

	int flagsOf(int slot) {
		return this.slotFlags[slot];
	}

	int carriedOf(int slot) {
		return Math.min(this.slotDesc[slot], 0xFFFF);
	}

	long posOf(int slot) {
		return this.slotPos[slot];
	}

	@Nullable BlockState stateOf(int slot) {
		return slot >= 0 && slot < this.slotCount ? this.slotState[slot] : null;
	}

	int tintOf(int slot) {
		int o = slot * 4;
		return 0xFF000000 | Math.round(this.slotLook[o] * 255.0F) << 16 | Math.round(this.slotLook[o + 1] * 255.0F) << 8
			| Math.round(this.slotLook[o + 2] * 255.0F);
	}

	@Nullable ClientLevel level() {
		return this.level;
	}

	public void tick(Minecraft mc) {
		ClientLevel lvl = mc.level;
		boolean on = WaterSettings.realisticTreeLeaves && lvl != null && mc.player != null && Vk.tryDevice() != null;
		int rule = Mth.clamp(WaterSettings.treeStickRule, 0, 1);
		if (on && (this.atlas == null || this.atlas.builtFrom != mc.getModelManager().getBlockStateModelSet())) {
			if (this.atlas != null) {
				this.atlas.close();
				this.atlas = null;
			}
			this.clear(mc);
			try {
				this.atlas = new TreeAtlas(mc);
			} catch (RuntimeException e) {
				RealisticWater.LOGGER.error("Realistic tree leaves couldn't build their textures", e);
				return;
			}
		}
		if (!on || lvl != this.level || rule != this.builtRule) {
			this.clear(mc);
			this.level = on ? lvl : null;
			this.builtRule = rule;
			if (!on) {
				return;
			}
		}
		Vec3 cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		this.updateActive(mc, cam);
		long until = System.nanoTime() + 4_000_000L;
		while (!this.queue.isEmpty() && System.nanoTime() < until) {
			long key = this.queue.dequeueLong();
			this.queued.remove(key);
			if (this.active.contains(key)) {
				this.dropColumn(key);
				this.scanColumn(key);
			}
		}
		this.refreshLooks(512);
	}

	public void onChunkLoad(ClientLevel lvl, LevelChunk chunk) {
		if (lvl != this.level) {
			return;
		}
		int cx = chunk.getPos().x();
		int cz = chunk.getPos().z();
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				this.enqueue(ChunkPos.pack(cx + dx, cz + dz));
			}
		}
	}

	public void onChunkUnload(ClientLevel lvl, LevelChunk chunk) {
		if (lvl == this.level) {
			this.dropColumn(chunk.getPos().pack());
		}
	}

	public void onBlockChanged(ClientLevel lvl, BlockPos pos, BlockState oldState, BlockState newState) {
		if (lvl != this.level || oldState == newState) {
			return;
		}
		byte before = this.classify(oldState);
		byte after = this.classify(newState);
		if (before == after && !(before == LEAF && oldState.getBlock() != newState.getBlock())) {
			return;
		}
		long key = pos.asLong();
		int slot = this.slots.get(key);
		if (slot >= 0 && after != LEAF) {
			this.removeSlot(key, ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
		}
		for (int dz = -MARGIN; dz <= MARGIN; dz += MARGIN) {
			for (int dx = -MARGIN; dx <= MARGIN; dx += MARGIN) {
				this.enqueue(ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX() + dx), SectionPos.blockToSectionCoord(pos.getZ() + dz)));
			}
		}
	}

	private void enqueue(long key) {
		if (this.active.contains(key) && this.queued.add(key)) {
			this.queue.enqueue(key);
		}
	}

	private void updateActive(Minecraft mc, Vec3 cam) {
		int r = Math.max(8, WaterSettings.treeLeafRadius);
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
		LongArrayList added = new LongArrayList();
		for (long key : next) {
			if (!this.active.contains(key)) {
				changed.add(key);
				added.add(key);
			}
		}
		this.active.clear();
		this.active.addAll(next);
		for (int i = 0; i < added.size(); i++) {
			this.scanColumn(added.getLong(i));
		}
		hidden = new LongOpenHashSet(next);
		for (long key : changed) {
			markColumnDirty(mc, key);
		}
	}

	private byte classify(BlockState state) {
		if (state.isAir()) {
			return AIR;
		}
		byte c = this.classes.getOrDefault(state, (byte) -1);
		if (c < 0) {
			if (isLeaves(state)) {
				c = LEAF;
			} else if (state.is(BlockTags.LOGS)) {
				c = LOG;
			} else if (state.canOcclude() && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
				c = OPAQUE;
			} else {
				c = AIR;
			}
			this.classes.put(state, c);
		}
		return c;
	}

	private void scanColumn(long key) {
		ClientLevel lvl = this.level;
		TreeAtlas atlas = this.atlas;
		int cx = ChunkPos.getX(key);
		int cz = ChunkPos.getZ(key);
		if (lvl == null || atlas == null || !lvl.hasChunk(cx, cz)) {
			return;
		}
		LevelChunk centre = lvl.getChunk(cx, cz);
		LevelChunkSection[] sections = centre.getSections();
		int minSection = Integer.MAX_VALUE;
		int maxSection = Integer.MIN_VALUE;
		for (int s = 0; s < sections.length; s++) {
			if (!sections[s].hasOnlyAir() && sections[s].maybeHas(RealisticTrees::isLeaves)) {
				minSection = Math.min(minSection, s);
				maxSection = Math.max(maxSection, s);
			}
		}
		if (minSection > maxSection) {
			return;
		}
		int baseY = lvl.getMinY();
		int y0 = Math.max(baseY, baseY + minSection * 16 - MARGIN);
		int y1 = Math.min(baseY + sections.length * 16, baseY + (maxSection + 1) * 16 + MARGIN);
		int height = y1 - y0;
		int x0 = (cx << 4) - MARGIN;
		int z0 = (cz << 4) - MARGIN;
		byte[] cls = new byte[SPAN * SPAN * height];
		for (int ncz = cz - 1; ncz <= cz + 1; ncz++) {
			for (int ncx = cx - 1; ncx <= cx + 1; ncx++) {
				if (!lvl.hasChunk(ncx, ncz)) {
					continue;
				}
				LevelChunk chunk = lvl.getChunk(ncx, ncz);
				LevelChunkSection[] secs = chunk.getSections();
				int bx0 = Math.max(ncx << 4, x0);
				int bx1 = Math.min((ncx << 4) + 16, x0 + SPAN);
				int bz0 = Math.max(ncz << 4, z0);
				int bz1 = Math.min((ncz << 4) + 16, z0 + SPAN);
				if (bx0 >= bx1 || bz0 >= bz1) {
					continue;
				}
				for (int s = 0; s < secs.length; s++) {
					int sy = baseY + s * 16;
					int sy0 = Math.max(sy, y0);
					int sy1 = Math.min(sy + 16, y1);
					if (sy0 >= sy1 || secs[s].hasOnlyAir()) {
						continue;
					}
					LevelChunkSection sec = secs[s];
					for (int y = sy0; y < sy1; y++) {
						for (int z = bz0; z < bz1; z++) {
							int row = ((y - y0) * SPAN + (z - z0)) * SPAN;
							for (int x = bx0; x < bx1; x++) {
								cls[row + x - x0] = this.classify(sec.getBlockState(x & 15, y & 15, z & 15));
							}
						}
					}
				}
			}
		}

		int cells = cls.length;
		short[] dist = new short[cells];
		java.util.Arrays.fill(dist, Short.MAX_VALUE);
		IntArrayList[] buckets = new IntArrayList[MAX_PATH + 1];
		for (int i = 0; i < cells; i++) {
			if (cls[i] == LOG) {
				dist[i] = 0;
				if (buckets[0] == null) {
					buckets[0] = new IntArrayList();
				}
				buckets[0].add(i);
			}
		}
		IntArrayList order = new IntArrayList();
		for (int d = 0; d <= MAX_PATH; d++) {
			IntArrayList bucket = buckets[d];
			if (bucket == null) {
				continue;
			}
			for (int k = 0; k < bucket.size(); k++) {
				int c = bucket.getInt(k);
				if (dist[c] != d) {
					continue;
				}
				if (cls[c] == LEAF) {
					order.add(c);
				}
				int x = c % SPAN;
				int z = (c / SPAN) % SPAN;
				int y = c / (SPAN * SPAN);
				for (int n = 0; n < 26; n++) {
					int[] o = NEIGHBOURS[n];
					int nx = x + o[0];
					int ny = y + o[1];
					int nz = z + o[2];
					if (nx < 0 || ny < 0 || nz < 0 || nx >= SPAN || nz >= SPAN || ny >= height) {
						continue;
					}
					int ni = (ny * SPAN + nz) * SPAN + nx;
					if (cls[ni] != LEAF) {
						continue;
					}
					int nd = d + STEP_COST[n];
					if (nd <= MAX_PATH && nd < dist[ni]) {
						dist[ni] = (short) nd;
						if (buckets[nd] == null) {
							buckets[nd] = new IntArrayList();
						}
						buckets[nd].add(ni);
					}
				}
			}
		}

		int rule = this.builtRule;
		byte[] parent = new byte[cells];
		java.util.Arrays.fill(parent, (byte) NO_PARENT);
		int[] desc = new int[cells];
		for (int k = 0; k < order.size(); k++) {
			int c = order.getInt(k);
			int x = c % SPAN;
			int z = (c / SPAN) % SPAN;
			int y = c / (SPAN * SPAN);
			int wx = x0 + x;
			int wy = y0 + y;
			int wz = z0 + z;
			int best = -1;
			int bestScore = Integer.MAX_VALUE;
			int count = 0;
			for (int n = 0; n < 26; n++) {
				int[] o = NEIGHBOURS[n];
				int nx = x + o[0];
				int ny = y + o[1];
				int nz = z + o[2];
				if (nx < 0 || ny < 0 || nz < 0 || nx >= SPAN || nz >= SPAN || ny >= height) {
					continue;
				}
				int ni = (ny * SPAN + nz) * SPAN + nx;
				if ((cls[ni] != LEAF && cls[ni] != LOG) || dist[ni] == Short.MAX_VALUE) {
					continue;
				}
				int tie = hash(wx, wy, wz, n) & 0xFF;
				int score;
				if (rule == 1) {
					if (dist[ni] >= dist[c]) {
						continue;
					}
					score = tie;
				} else {
					int through = dist[ni] + STEP_COST[n];
					if (through > dist[c]) {
						continue;
					}
					score = through * 256 + tie;
				}
				count++;
				if (score < bestScore) {
					bestScore = score;
					best = n;
				}
			}
			if (count > 0) {
				parent[c] = (byte) best;
			}
		}
		for (int k = order.size() - 1; k >= 0; k--) {
			int c = order.getInt(k);
			desc[c] += 1;
			int p = parent[c];
			if (p == NO_PARENT) {
				continue;
			}
			int[] o = NEIGHBOURS[p];
			int pi = c + (o[1] * SPAN + o[2]) * SPAN + o[0];
			if (cls[pi] == LEAF) {
				desc[pi] += desc[c];
			}
		}

		for (int y = 0; y < height; y++) {
			for (int z = MARGIN; z < MARGIN + 16; z++) {
				for (int x = MARGIN; x < MARGIN + 16; x++) {
					int c = (y * SPAN + z) * SPAN + x;
					if (cls[c] != LEAF) {
						continue;
					}
					int open = 0;
					int leafy = 0;
					for (int f = 0; f < 6; f++) {
						int fx = x + (f == 0 ? -1 : f == 1 ? 1 : 0);
						int fy = y + (f == 2 ? -1 : f == 3 ? 1 : 0);
						int fz = z + (f == 4 ? -1 : f == 5 ? 1 : 0);
						byte nc = fy < 0 || fy >= height ? AIR : cls[(fy * SPAN + fz) * SPAN + fx];
						if (nc == AIR) {
							open |= 1 << f;
						} else if (nc == LEAF) {
							leafy |= 1 << f;
						}
					}
					int p = parent[c];
					boolean parentLog = false;
					if (p != NO_PARENT) {
						int[] o = NEIGHBOURS[p];
						parentLog = cls[c + (o[1] * SPAN + o[2]) * SPAN + o[0]] == LOG;
					}
					int flags = open | leafy << 6 | p << 12 | (parentLog ? 1 << 17 : 0) | (dist[c] == Short.MAX_VALUE ? 1 << 18 : 0);
					long pos = BlockPos.asLong(x0 + x, y0 + y, z0 + z);
					this.addSlot(pos, key, centre.getBlockState(BlockPos.of(pos)), flags, Math.max(desc[c], 1));
				}
			}
		}
		this.revision++;
	}

	static int hash(int x, int y, int z, int salt) {
		int h = x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ salt * 0x9E3779B9;
		h ^= h >>> 16;
		h *= 0x7FEB352D;
		h ^= h >>> 15;
		h *= 0x846CA68B;
		return h ^ (h >>> 16);
	}

	private void dropColumn(long key) {
		LongArrayList list = this.columns.remove(key);
		if (list == null) {
			return;
		}
		for (int i = 0; i < list.size(); i++) {
			int slot = this.slots.remove(list.getLong(i));
			if (slot >= 0) {
				this.slotState[slot] = null;
				this.free.add(slot);
			}
		}
		this.trimSlots();
		this.revision++;
	}

	private void addSlot(long pos, long column, BlockState state, int flags, int desc) {
		int slot = this.slots.get(pos);
		if (slot < 0) {
			if (this.free.isEmpty()) {
				slot = this.slotCount++;
				this.ensureCapacity(this.slotCount);
			} else {
				this.free.sort(null);
				slot = this.free.removeInt(0);
			}
			this.slots.put(pos, slot);
			this.columns.computeIfAbsent(column, k -> new LongArrayList()).add(pos);
			this.slotGen[slot] = this.nextGen++ & 0xFFF;
		}
		this.slotPos[slot] = pos;
		this.slotState[slot] = state;
		this.slotFlags[slot] = flags;
		this.slotDesc[slot] = desc;
		this.refreshLook(slot);
	}

	private void removeSlot(long pos, long column) {
		int slot = this.slots.remove(pos);
		if (slot < 0) {
			return;
		}
		this.slotState[slot] = null;
		this.free.add(slot);
		LongArrayList list = this.columns.get(column);
		if (list != null) {
			list.rem(pos);
		}
		this.trimSlots();
		this.revision++;
	}

	private void trimSlots() {
		while (this.slotCount > 0 && this.slotState[this.slotCount - 1] == null) {
			this.slotCount--;
			this.free.rem(this.slotCount);
		}
	}

	private void ensureCapacity(int n) {
		if (n <= this.slotPos.length) {
			return;
		}
		int cap = Math.max(n, Math.max(4096, this.slotPos.length * 2));
		this.slotPos = java.util.Arrays.copyOf(this.slotPos, cap);
		this.slotState = java.util.Arrays.copyOf(this.slotState, cap);
		this.slotFlags = java.util.Arrays.copyOf(this.slotFlags, cap);
		this.slotDesc = java.util.Arrays.copyOf(this.slotDesc, cap);
		this.slotGen = java.util.Arrays.copyOf(this.slotGen, cap);
		this.slotLook = java.util.Arrays.copyOf(this.slotLook, cap * 4);
	}

	private void refreshLooks(int budget) {
		for (int k = 0; k < budget && this.slotCount > 0; k++) {
			this.lookCursor = (this.lookCursor + 1) % this.slotCount;
			if (this.slotState[this.lookCursor] != null) {
				this.refreshLook(this.lookCursor);
			}
		}
	}

	private void refreshLook(int slot) {
		ClientLevel lvl = this.level;
		BlockState state = this.slotState[slot];
		if (lvl == null || state == null) {
			return;
		}
		BlockPos p = BlockPos.of(this.slotPos[slot]);
		int tint = 0xFFFFFFFF;
		try {
			BlockTintSource source = Minecraft.getInstance().getBlockColors().getTintSource(state, 0);
			if (source != null) {
				tint = source.colorInWorld(state, lvl, p);
			}
		} catch (RuntimeException ignored) {
		}
		int o = slot * 4;
		this.slotLook[o] = ((tint >> 16) & 0xFF) / 255.0F;
		this.slotLook[o + 1] = ((tint >> 8) & 0xFF) / 255.0F;
		this.slotLook[o + 2] = (tint & 0xFF) / 255.0F;
		int sky = lvl.getBrightness(LightLayer.SKY, p);
		if (p.getY() >= lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ())) {
			sky = 15;
		}
		this.slotLook[o + 3] = lvl.getBrightness(LightLayer.BLOCK, p) * 16 + sky;
	}

	void writeBlocks(ByteBuffer b, long offset) {
		TreeAtlas atlas = this.atlas;
		for (int slot = 0; slot < this.slotCount; slot++) {
			int o = (int) offset + slot * 48;
			BlockState state = this.slotState[slot];
			if (state == null || atlas == null) {
				b.putInt(o, 0).putInt(o + 4, 0).putInt(o + 8, 0).putInt(o + 12, 0);
				b.putInt(o + 16, 0).putInt(o + 20, 0).putInt(o + 24, 0).putInt(o + 28, 0);
				continue;
			}
			long pos = this.slotPos[slot];
			TreeAtlas.Kind kind = atlas.kind(state.getBlock());
			b.putInt(o, BlockPos.getX(pos)).putInt(o + 4, BlockPos.getY(pos)).putInt(o + 8, BlockPos.getZ(pos))
				.putInt(o + 12, this.slotFlags[slot] | 1 << 19 | this.slotGen[slot] << 20);
			int mods = this.modPointers.get(slot);
			b.putInt(o + 16, kind.leafCell() | kind.barkCell() << 8).putInt(o + 20, Math.min(this.slotDesc[slot], 0xFFFF) | mods << 16)
				.putInt(o + 24, kind.leafColor()).putInt(o + 28, kind.barkColor());
			int l = slot * 4;
			b.putFloat(o + 32, this.slotLook[l]).putFloat(o + 36, this.slotLook[l + 1]).putFloat(o + 40, this.slotLook[l + 2])
				.putFloat(o + 44, this.slotLook[l + 3]);
		}
	}

	public void clear(Minecraft mc) {
		LongOpenHashSet was = new LongOpenHashSet(this.active);
		this.active.clear();
		this.columns.clear();
		this.slots.clear();
		this.free.clear();
		java.util.Arrays.fill(this.slotState, null);
		this.slotCount = 0;
		this.queue.clear();
		this.queued.clear();
		this.centreX = Integer.MIN_VALUE;
		this.level = null;
		this.revision++;
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
