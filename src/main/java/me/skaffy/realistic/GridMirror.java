package me.skaffy.realistic;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.net.GridBlockEntityPayload;
import me.skaffy.physics.RigidBody;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public final class GridMirror {
	private static final int PUSH_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	private final RealisticBlockEntity owner;
	private final ServerLevel level;
	private final GridSpace space;
	private final Long2ObjectMap<BlockState> mirrored = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet pending = new LongOpenHashSet();
	private final LongOpenHashSet dirtyBlockEntities = new LongOpenHashSet();
	private final LongOpenHashSet sentBlockEntities = new LongOpenHashSet();
	private final Long2ObjectMap<CompoundTag> loaded = new Long2ObjectOpenHashMap<>();
	private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
	private GridSpace.@Nullable Slot slot;
	private boolean writing;
	private boolean full = true;

	GridMirror(RealisticBlockEntity owner, ServerLevel level) {
		this.owner = owner;
		this.level = level;
		this.space = GridSpace.of(level);
	}

	public GridSpace.@Nullable Slot slot() {
		return this.slot;
	}

	public void invalidate() {
		this.full = true;
	}

	void noteChanged(BlockPos world) {
		if (this.writing || this.slot == null) {
			return;
		}
		long key = BlockPos.asLong(this.slot.gridX(world.getX()), this.slot.gridY(world.getY()), this.slot.gridZ(world.getZ()));
		this.pending.add(key);
		this.dirtyBlockEntities.add(key);
	}

	void noteBlockEntityChanged(BlockPos world) {
		GridSpace.Slot at = this.slot;
		if (at == null) {
			return;
		}
		this.dirtyBlockEntities.add(BlockPos.asLong(at.gridX(world.getX()), at.gridY(world.getY()), at.gridZ(world.getZ())));
	}

	void syncBlockEntities() {
		GridSpace.Slot at = this.slot;
		if (at == null || this.dirtyBlockEntities.isEmpty()) {
			return;
		}
		long[] keys = this.dirtyBlockEntities.toLongArray();
		this.dirtyBlockEntities.clear();
		List<GridBlockEntityPayload> out = new ArrayList<>();
		for (long key : keys) {
			GridBlockEntityPayload payload = this.payloadFor(at, key);
			if (payload != null) {
				out.add(payload);
			}
		}
		if (out.isEmpty()) {
			return;
		}
		for (ServerPlayer player : PlayerLookup.tracking(this.owner)) {
			for (GridBlockEntityPayload payload : out) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	public List<GridBlockEntityPayload> fullBlockEntityState() {
		GridSpace.Slot at = this.slot;
		if (at == null) {
			return List.of();
		}
		List<GridBlockEntityPayload> out = new ArrayList<>();
		for (RigidBody.Cell cell : this.owner.body.cells) {
			if (!(cell.payload instanceof BlockState state) || !state.hasBlockEntity()) {
				continue;
			}
			GridBlockEntityPayload payload = this.payloadFor(at, BlockPos.asLong(cell.x, cell.y, cell.z));
			if (payload != null) {
				out.add(payload);
			}
		}
		return out;
	}

	private @Nullable GridBlockEntityPayload payloadFor(GridSpace.Slot at, long key) {
		int gx = BlockPos.getX(key);
		int gy = BlockPos.getY(key);
		int gz = BlockPos.getZ(key);
		at.world(gx, gy, gz, this.scratch);
		BlockEntity be = this.level.getBlockEntity(this.scratch);
		if (be == null) {
			if (!this.sentBlockEntities.remove(key)) {
				return null;
			}
			return new GridBlockEntityPayload(this.owner.getId(), gx, gy, gz, Optional.empty());
		}
		this.sentBlockEntities.add(key);
		CompoundTag tag = be.getUpdateTag(this.level.registryAccess());
		tag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());
		return new GridBlockEntityPayload(this.owner.getId(), gx, gy, gz, Optional.of(tag));
	}

	void saveBlockEntities(ValueOutput.ValueOutputList list) {
		GridSpace.Slot at = this.slot;
		if (at == null) {
			return;
		}
		for (RigidBody.Cell cell : this.owner.body.cells) {
			if (!(cell.payload instanceof BlockState state) || !state.hasBlockEntity()) {
				continue;
			}
			at.world(cell.x, cell.y, cell.z, this.scratch);
			BlockEntity be = this.level.getBlockEntity(this.scratch);
			if (be == null) {
				continue;
			}
			ValueOutput out = list.addChild();
			out.putInt("x", cell.x);
			out.putInt("y", cell.y);
			out.putInt("z", cell.z);
			out.store("data", CompoundTag.CODEC, be.saveWithFullMetadata(this.level.registryAccess()));
		}
	}

	public void loadBlockEntities(Long2ObjectMap<CompoundTag> tags) {
		this.loaded.putAll(tags);
	}

	public Long2ObjectOpenHashMap<CompoundTag> takeBlockEntityTags(List<RigidBody.Cell> cells) {
		Long2ObjectOpenHashMap<CompoundTag> out = new Long2ObjectOpenHashMap<>();
		GridSpace.Slot at = this.slot;
		if (at == null) {
			return out;
		}
		for (RigidBody.Cell cell : cells) {
			if (!(cell.payload instanceof BlockState state) || !state.hasBlockEntity()) {
				continue;
			}
			at.world(cell.x, cell.y, cell.z, this.scratch);
			BlockEntity be = this.level.getBlockEntity(this.scratch);
			if (be != null) {
				out.put(BlockPos.asLong(cell.x, cell.y, cell.z), be.saveWithFullMetadata(this.level.registryAccess()));
			}
		}
		return out;
	}

	private void applyLoaded(GridSpace.Slot at) {
		if (this.loaded.isEmpty()) {
			return;
		}
		for (Long2ObjectMap.Entry<CompoundTag> entry : this.loaded.long2ObjectEntrySet()) {
			long key = entry.getLongKey();
			at.world(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), this.scratch);
			BlockEntity be = this.level.getBlockEntity(this.scratch);
			if (be == null) {
				continue;
			}
			BlockEntity restored = BlockEntity.loadStatic(this.scratch.immutable(), be.getBlockState(), entry.getValue(), this.level.registryAccess());
			if (restored != null) {
				this.level.setBlockEntity(restored);
			}
			this.dirtyBlockEntities.add(key);
		}
		this.loaded.clear();
	}

	public void push() {
		this.pull();
		GridSpace.Slot was = this.slot;
		GridSpace.Slot now = this.space.slotFor(this.owner);
		if (now == null) {
			return;
		}
		if (now != was) {
			this.writing = true;
			try {
				if (was != null) {
					this.clear(was, PUSH_FLAGS);
					this.removeTickets(was);
				}
			} finally {
				this.writing = false;
			}
			this.slot = now;
			this.owner.setGridOrigin(now.world(0, 0, 0));
			this.mirrored.clear();
			this.pending.clear();
			this.full = true;
			this.addTickets(now);
		}
		List<BlockPos> placed = new ArrayList<>();
		LongOpenHashSet written = new LongOpenHashSet();
		boolean retry = false;
		this.writing = true;
		try {
			LongOpenHashSet live = new LongOpenHashSet();
			for (RigidBody.Cell cell : this.owner.body.cells) {
				if (!(cell.payload instanceof BlockState state)) {
					continue;
				}
				long key = BlockPos.asLong(cell.x, cell.y, cell.z);
				live.add(key);
				if (!this.full && state == this.mirrored.get(key)) {
					continue;
				}
				BlockPos at = now.world(cell.x, cell.y, cell.z);
				if (this.level.getBlockState(at) != state) {
					this.level.setBlock(at, state, PUSH_FLAGS);
					placed.add(at);
					written.add(key);
				}
				if (this.level.getBlockState(at) == state) {
					this.mirrored.put(key, state);
				} else {
					retry = true;
				}
			}
			for (LongIterator it = this.mirrored.keySet().iterator(); it.hasNext(); ) {
				long key = it.nextLong();
				if (live.contains(key)) {
					continue;
				}
				BlockPos at = now.world(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
				this.level.setBlock(at, Blocks.AIR.defaultBlockState(), PUSH_FLAGS);
				written.add(key);
				it.remove();
			}
			this.full = retry;
		} finally {
			this.writing = false;
		}
		this.pending.removeAll(written);
		this.dirtyBlockEntities.addAll(written);
		for (BlockPos at : placed) {
			this.level.updateNeighborsAt(at, this.level.getBlockState(at).getBlock());
		}
		this.applyLoaded(now);
		this.syncBlockEntities();
		this.checkTicking(now);
	}

	private void checkTicking(GridSpace.Slot at) {
		if (this.tickingChecks < 0 || ++this.tickingChecks < 40) {
			return;
		}
		this.tickingChecks = -1;
		if (!this.level.shouldTickBlocksAt(ChunkPos.pack(at.cellChunkMinX(), at.cellChunkMinZ()))) {
			RealisticWater.LOGGER.error("Realistic block grid chunk {},{} is loaded but not block-ticking, so redstone, placing and breaking will not work "
				+ "on realistic blocks. The grid chunk ticket is not granting ENTITY_TICKING.", at.cellChunkMinX(), at.cellChunkMinZ());
		}
	}

	private int tickingChecks;

	public void pull() {
		GridSpace.Slot at = this.slot;
		if (at == null || this.pending.isEmpty()) {
			return;
		}
		long[] keys = this.pending.toLongArray();
		this.pending.clear();
		Long2ObjectOpenHashMap<BlockState> changed = new Long2ObjectOpenHashMap<>();
		List<long[]> added = new ArrayList<>();
		List<long[]> removed = new ArrayList<>();
		for (long key : keys) {
			int gx = BlockPos.getX(key);
			int gy = BlockPos.getY(key);
			int gz = BlockPos.getZ(key);
			at.world(gx, gy, gz, this.scratch);
			BlockState state = this.level.getBlockState(this.scratch);
			RigidBody.Cell cell = this.owner.body.cellAt(gx, gy, gz);
			if (state.isAir()) {
				if (cell != null) {
					removed.add(new long[]{gx, gy, gz});
				}
				this.mirrored.remove(key);
				continue;
			}
			this.mirrored.put(key, state);
			if (cell == null) {
				added.add(new long[]{gx, gy, gz});
			} else if (cell.payload != state) {
				changed.put(key, state);
			}
		}
		if (!changed.isEmpty()) {
			this.owner.setCellStates(changed);
		}
		for (long[] a : added) {
			at.world((int) a[0], (int) a[1], (int) a[2], this.scratch);
			this.owner.adoptCell((int) a[0], (int) a[1], (int) a[2], this.level.getBlockState(this.scratch));
		}
		for (long[] r : removed) {
			this.owner.removeCell((int) r[0], (int) r[1], (int) r[2]);
		}
	}

	public void randomTick(int tickSpeed) {
		GridSpace.Slot at = this.slot;
		List<RigidBody.Cell> cells = this.owner.body.cells;
		if (at == null || tickSpeed <= 0 || cells.isEmpty()) {
			return;
		}
		for (int i = 0; i < tickSpeed; i++) {
			RigidBody.Cell cell = cells.get(this.level.getRandom().nextInt(cells.size()));
			if (!(cell.payload instanceof BlockState state) || !state.isRandomlyTicking()) {
				continue;
			}
			at.world(cell.x, cell.y, cell.z, this.scratch);
			state.randomTick(this.level, this.scratch.immutable(), this.level.getRandom());
		}
	}

	public void release() {
		this.release(PUSH_FLAGS);
	}

	public void releaseMoved() {
		this.release(PUSH_FLAGS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
	}

	private void release(int flags) {
		GridSpace.Slot at = this.slot;
		if (at == null) {
			return;
		}
		this.writing = true;
		try {
			this.clear(at, flags);
		} finally {
			this.writing = false;
		}
		this.removeTickets(at);
		this.space.release(this.owner);
		this.slot = null;
		this.mirrored.clear();
		this.pending.clear();
		this.full = true;
	}

	private void clear(GridSpace.Slot at, int flags) {
		BlockState air = Blocks.AIR.defaultBlockState();
		for (LongIterator it = this.mirrored.keySet().iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			at.world(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), this.scratch);
			this.level.setBlock(this.scratch.immutable(), air, flags);
		}
		this.mirrored.clear();
	}

	private static final int TICKET_RADIUS = 2;

	private void addTickets(GridSpace.Slot at) {
		for (int cx = at.cellChunkMinX(); cx <= at.cellChunkMaxX(); cx++) {
			for (int cz = at.cellChunkMinZ(); cz <= at.cellChunkMaxZ(); cz++) {
				this.level.getChunkSource().addTicketWithRadius(ModContent.GRID_TICKET, new ChunkPos(cx, cz), TICKET_RADIUS);
			}
		}
	}

	private void removeTickets(GridSpace.Slot at) {
		for (int cx = at.cellChunkMinX(); cx <= at.cellChunkMaxX(); cx++) {
			for (int cz = at.cellChunkMinZ(); cz <= at.cellChunkMaxZ(); cz++) {
				this.level.getChunkSource().removeTicketWithRadius(ModContent.GRID_TICKET, new ChunkPos(cx, cz), TICKET_RADIUS);
			}
		}
	}
}
