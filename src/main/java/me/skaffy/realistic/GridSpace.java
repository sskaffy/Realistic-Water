package me.skaffy.realistic;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public final class GridSpace {
	public static final int BASE_CHUNK_X = 400_000;
	public static final int HALO = 16;
	private static final int Y_GAP = 2 * HALO;

	private static final Map<ServerLevel, GridSpace> SPACES = new HashMap<>();

	private final ServerLevel level;
	private final Long2ObjectMap<Slot> byBody = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectMap<List<Slot>> byColumn = new Long2ObjectOpenHashMap<>();
	private final Map<Long, ArrayDeque<Footprint>> freeByShape = new HashMap<>();
	private final List<Footprint> footprints = new ArrayList<>();
	private int nextColumnX = BASE_CHUNK_X;

	private GridSpace(ServerLevel level) {
		this.level = level;
	}

	public static GridSpace of(ServerLevel level) {
		return SPACES.computeIfAbsent(level, GridSpace::new);
	}

	public static void forget(ServerLevel level) {
		SPACES.remove(level);
	}

	public static void clear() {
		SPACES.clear();
	}

	public static boolean isGridPos(int x) {
		return x >= BASE_CHUNK_X << 4;
	}

	public static boolean isGridPos(BlockPos pos) {
		return isGridPos(pos.getX());
	}

	public static boolean isGridChunk(int chunkX) {
		return chunkX >= BASE_CHUNK_X;
	}

	public static boolean isGridChunk(ChunkPos pos) {
		return isGridChunk(pos.x());
	}

	public static @Nullable RealisticBlockEntity bodyAt(Level level, BlockPos pos) {
		if (!isGridPos(pos) || !(level instanceof ServerLevel server)) {
			return null;
		}
		GridSpace space = SPACES.get(server);
		if (space == null) {
			return null;
		}
		Slot slot = space.slotAt(pos.getX() >> 4, pos.getZ() >> 4, pos.getY());
		if (slot == null) {
			return null;
		}
		return server.getEntity(slot.entityId) instanceof RealisticBlockEntity body ? body : null;
	}

	public static @Nullable Found find(Level level, double x, double y, double z) {
		if (!isGridPos((int) Math.floor(x)) || !(level instanceof ServerLevel server)) {
			return null;
		}
		GridSpace space = SPACES.get(server);
		if (space == null) {
			return null;
		}
		Slot slot = space.slotAt(Mth.floor(x) >> 4, Mth.floor(z) >> 4, Mth.floor(y));
		if (slot == null) {
			return null;
		}
		return server.getEntity(slot.entityId) instanceof RealisticBlockEntity body ? new Found(body, slot) : null;
	}

	public record Found(RealisticBlockEntity body, Slot slot) {
		public Vec3 toWorld(double x, double y, double z) {
			Vector3d out = this.body.body.toWorld(x - this.slot.originX, y - this.slot.originY, z - this.slot.originZ, new Vector3d());
			return new Vec3(out.x, out.y, out.z);
		}

		public Vec3 toWorld(BlockPos pos) {
			return this.toWorld(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		}
	}

	private @Nullable Slot slotAt(int chunkX, int chunkZ, int y) {
		List<Slot> here = this.byColumn.get(column(chunkX, chunkZ));
		if (here == null) {
			return null;
		}
		for (Slot slot : here) {
			if (slot.holds(y) && this.byBody.get((long) slot.entityId) == slot) {
				return slot;
			}
		}
		return null;
	}

	private static long column(int chunkX, int chunkZ) {
		return ChunkPos.pack(chunkX, chunkZ);
	}

	public static final class Slot {
		final Footprint footprint;
		final int entityId;
		final int originX;
		final int originY;
		final int originZ;
		final int minGx;
		final int minGy;
		final int minGz;
		final int sizeX;
		final int sizeY;
		final int sizeZ;
		final int bandY;
		final int bandHeight;

		private Slot(Footprint footprint, int entityId, int[] bbox, int bandY) {
			this.footprint = footprint;
			this.entityId = entityId;
			this.minGx = bbox[0];
			this.minGy = bbox[1];
			this.minGz = bbox[2];
			this.sizeX = bbox[3] - bbox[0] + 1;
			this.sizeY = bbox[4] - bbox[1] + 1;
			this.sizeZ = bbox[5] - bbox[2] + 1;
			this.bandY = bandY;
			this.bandHeight = this.sizeY + Y_GAP;
			this.originX = (footprint.chunkX << 4) + HALO - this.minGx;
			this.originZ = (footprint.chunkZ << 4) + HALO - this.minGz;
			this.originY = bandY + HALO - this.minGy;
		}

		public int originX() {
			return this.originX;
		}

		public int originY() {
			return this.originY;
		}

		public int originZ() {
			return this.originZ;
		}

		public BlockPos world(int gx, int gy, int gz) {
			return new BlockPos(this.originX + gx, this.originY + gy, this.originZ + gz);
		}

		public void world(int gx, int gy, int gz, BlockPos.MutableBlockPos into) {
			into.set(this.originX + gx, this.originY + gy, this.originZ + gz);
		}

		public int gridX(int worldX) {
			return worldX - this.originX;
		}

		public int gridY(int worldY) {
			return worldY - this.originY;
		}

		public int gridZ(int worldZ) {
			return worldZ - this.originZ;
		}

		boolean fits(int[] bbox) {
			return bbox[0] >= this.minGx && bbox[1] >= this.minGy && bbox[2] >= this.minGz
				&& bbox[3] - this.minGx < this.sizeX && bbox[4] - this.minGy < this.sizeY && bbox[5] - this.minGz < this.sizeZ;
		}

		boolean holds(BlockPos pos) {
			return this.holds(pos.getY());
		}

		boolean holds(int y) {
			return y >= this.bandY && y < this.bandY + this.bandHeight;
		}

		public int chunkMinX() {
			return this.footprint.chunkX;
		}

		public int chunkMinZ() {
			return this.footprint.chunkZ;
		}

		public int chunkMaxX() {
			return this.footprint.chunkX + this.footprint.columnsX - 1;
		}

		public int chunkMaxZ() {
			return this.footprint.chunkZ + this.footprint.columnsZ - 1;
		}

		public int cellChunkMinX() {
			return this.originX + this.minGx >> 4;
		}

		public int cellChunkMaxX() {
			return this.originX + this.minGx + this.sizeX - 1 >> 4;
		}

		public int cellChunkMinZ() {
			return this.originZ + this.minGz >> 4;
		}

		public int cellChunkMaxZ() {
			return this.originZ + this.minGz + this.sizeZ - 1 >> 4;
		}
	}

	private static final class Footprint {
		final int chunkX;
		final int chunkZ;
		final int columnsX;
		final int columnsZ;
		final int bandHeight;
		final int minY;
		final int maxY;
		int nextBandY;
		final ArrayDeque<Integer> freeBands = new ArrayDeque<>();
		int used;

		Footprint(int chunkX, int chunkZ, int columnsX, int columnsZ, int bandHeight, int minY, int maxY) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.columnsX = columnsX;
			this.columnsZ = columnsZ;
			this.bandHeight = bandHeight;
			this.minY = minY;
			this.maxY = maxY;
			this.nextBandY = minY;
		}

		@Nullable Integer takeBand() {
			if (!this.freeBands.isEmpty()) {
				return this.freeBands.poll();
			}
			if (this.nextBandY + this.bandHeight > this.maxY) {
				return null;
			}
			int y = this.nextBandY;
			this.nextBandY += this.bandHeight;
			return y;
		}
	}

	public @Nullable Slot slotFor(RealisticBlockEntity body) {
		if (body.body.cells.isEmpty()) {
			return null;
		}
		long id = body.getId();
		int[] bbox = gridBounds(body);
		Slot slot = this.byBody.get(id);
		if (slot != null && slot.fits(bbox)) {
			return slot;
		}
		if (slot != null) {
			this.release(body);
		}
		Slot made = this.allocate(body.getId(), bbox);
		if (made == null) {
			return null;
		}
		this.byBody.put(id, made);
		for (int cx = made.chunkMinX(); cx <= made.chunkMaxX(); cx++) {
			for (int cz = made.chunkMinZ(); cz <= made.chunkMaxZ(); cz++) {
				this.byColumn.computeIfAbsent(column(cx, cz), k -> new ArrayList<>()).add(made);
			}
		}
		return made;
	}

	public @Nullable Slot slotOf(RealisticBlockEntity body) {
		return this.byBody.get((long) body.getId());
	}

	public void release(RealisticBlockEntity body) {
		Slot slot = this.byBody.remove((long) body.getId());
		if (slot == null) {
			return;
		}
		for (int cx = slot.chunkMinX(); cx <= slot.chunkMaxX(); cx++) {
			for (int cz = slot.chunkMinZ(); cz <= slot.chunkMaxZ(); cz++) {
				long key = column(cx, cz);
				List<Slot> here = this.byColumn.get(key);
				if (here != null && here.remove(slot) && here.isEmpty()) {
					this.byColumn.remove(key);
				}
			}
		}
		slot.footprint.freeBands.add(slot.bandY);
		if (--slot.footprint.used <= 0) {
			this.freeByShape.computeIfAbsent(shape(slot.footprint.columnsX, slot.footprint.columnsZ, slot.footprint.bandHeight),
				k -> new ArrayDeque<>()).add(slot.footprint);
		}
	}

	private static final int GRANULARITY = 16;

	private static int[] gridBounds(RealisticBlockEntity body) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (var cell : body.body.cells) {
			minX = Math.min(minX, cell.x);
			minY = Math.min(minY, cell.y);
			minZ = Math.min(minZ, cell.z);
			maxX = Math.max(maxX, cell.x);
			maxY = Math.max(maxY, cell.y);
			maxZ = Math.max(maxZ, cell.z);
		}
		return new int[]{snapDown(minX), snapDown(minY), snapDown(minZ), snapUp(maxX), snapUp(maxY), snapUp(maxZ)};
	}

	private static int snapDown(int value) {
		return Math.floorDiv(value - 2, GRANULARITY) * GRANULARITY;
	}

	private static int snapUp(int value) {
		return Math.floorDiv(value + 2, GRANULARITY) * GRANULARITY + GRANULARITY - 1;
	}

	private @Nullable Slot allocate(int entityId, int[] bbox) {
		int sizeX = bbox[3] - bbox[0] + 1;
		int sizeY = bbox[4] - bbox[1] + 1;
		int sizeZ = bbox[5] - bbox[2] + 1;
		int columnsX = Math.ceilDiv(sizeX + 2 * HALO, 16);
		int columnsZ = Math.ceilDiv(sizeZ + 2 * HALO, 16);
		int bandHeight = sizeY + Y_GAP;
		long shape = shape(columnsX, columnsZ, bandHeight);
		ArrayDeque<Footprint> free = this.freeByShape.get(shape);
		while (free != null && !free.isEmpty()) {
			Footprint reused = free.peek();
			Integer band = reused.takeBand();
			if (band != null) {
				reused.used++;
				return new Slot(reused, entityId, bbox, band);
			}
			free.poll();
		}
		for (Footprint existing : this.footprints) {
			if (existing.columnsX != columnsX || existing.columnsZ != columnsZ || existing.bandHeight != bandHeight) {
				continue;
			}
			Integer band = existing.takeBand();
			if (band != null) {
				existing.used++;
				return new Slot(existing, entityId, bbox, band);
			}
		}
		int minY = SectionPos.sectionToBlockCoord(this.level.getMinSectionY());
		int maxY = minY + this.level.getHeight();
		if (bandHeight > maxY - minY) {
			return null;
		}
		Footprint fresh = new Footprint(this.nextColumnX, BASE_CHUNK_X, columnsX, columnsZ, bandHeight, minY, maxY);
		this.nextColumnX += columnsX;
		this.footprints.add(fresh);
		Integer band = fresh.takeBand();
		if (band == null) {
			return null;
		}
		fresh.used++;
		return new Slot(fresh, entityId, bbox, band);
	}

	private static long shape(int columnsX, int columnsZ, int bandHeight) {
		return (long) columnsX << 44 | (long) columnsZ << 24 | bandHeight;
	}
}
