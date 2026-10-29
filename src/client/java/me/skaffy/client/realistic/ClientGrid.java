package me.skaffy.client.realistic;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.GridSpace;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

public final class ClientGrid {
	private static final Long2ObjectMap<LevelChunk> CHUNKS = new Long2ObjectOpenHashMap<>();
	private static final List<RealisticBlockEntity> BODIES = new ArrayList<>();
	private static @Nullable ClientLevel level;
	private static boolean complained;

	private ClientGrid() {
	}

	public static void clear() {
		CHUNKS.clear();
		BODIES.clear();
		level = null;
		complained = false;
	}

	public static @Nullable LevelChunk chunk(int chunkX, int chunkZ) {
		return CHUNKS.get(ChunkPos.pack(chunkX, chunkZ));
	}

	public static @Nullable LevelChunk chunkOrCreate(int chunkX, int chunkZ) {
		LevelChunk existing = chunk(chunkX, chunkZ);
		if (existing != null) {
			return existing;
		}
		ClientLevel on = net.minecraft.client.Minecraft.getInstance().level;
		if (on == null) {
			return null;
		}
		return chunkFor(on, new BlockPos(chunkX << 4, 0, chunkZ << 4));
	}

	private static @Nullable LevelChunk chunkFor(ClientLevel on, BlockPos pos) {
		if (level != on) {
			CHUNKS.clear();
			BODIES.clear();
			complained = false;
			level = on;
		}
		long key = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
		LevelChunk chunk = CHUNKS.get(key);
		if (chunk == null) {
			chunk = new LevelChunk(on, new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
			CHUNKS.put(key, chunk);
		}
		return chunk;
	}

	public static void sync(RealisticBlockEntity body) {
		if (!(body.level() instanceof ClientLevel client)) {
			return;
		}
		BlockPos origin = body.gridOrigin();
		if (origin.equals(BlockPos.ZERO)) {
			return;
		}
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		List<BlockPos> wanted = new ArrayList<>(body.body.cells.size());
		for (RigidBody.Cell cell : body.body.cells) {
			if (!(cell.payload instanceof BlockState state)) {
				continue;
			}
			at.set(origin.getX() + cell.x, origin.getY() + cell.y, origin.getZ() + cell.z);
			LevelChunk chunk = chunkFor(client, at);
			if (chunk == null) {
				continue;
			}
			BlockPos immutable = at.immutable();
			wanted.add(immutable);
			if (chunk.getBlockState(immutable) != state) {
				try {
					chunk.setBlockState(immutable, state, 2);
				} catch (RuntimeException e) {
					if (!complained) {
						complained = true;
						me.skaffy.RealisticWater.LOGGER.warn("Could not put a realistic block into the client's grid chunk; "
							+ "block entities on realistic blocks will not render", e);
					}
					return;
				}
			}
		}
		body.rememberGridBlocks(wanted);
		if (!BODIES.contains(body)) {
			BODIES.add(body);
		}
		BODIES.removeIf(b -> b.isRemoved());
	}

	public static void forget(List<BlockPos> positions) {
		ClientLevel on = level;
		if (on == null) {
			return;
		}
		BlockState air = Blocks.AIR.defaultBlockState();
		for (BlockPos pos : positions) {
			LevelChunk chunk = chunk(pos.getX() >> 4, pos.getZ() >> 4);
			if (chunk != null && !chunk.getBlockState(pos).isAir()) {
				chunk.setBlockState(pos, air, 2);
			}
		}
	}

	public static @Nullable RealisticBlockEntity bodyAt(BlockPos gridPos) {
		if (!GridSpace.isGridPos(gridPos)) {
			return null;
		}
		for (RealisticBlockEntity body : BODIES) {
			if (!body.isRemoved() && body.cellAtGrid(gridPos) != null) {
				return body;
			}
		}
		return null;
	}
}
