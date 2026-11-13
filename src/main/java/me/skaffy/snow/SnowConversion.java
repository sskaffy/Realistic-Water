package me.skaffy.snow;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.ModContent;
import me.skaffy.ModSettings;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticSnowBlock;
import me.skaffy.block.SnowOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class SnowConversion {
	private static final int CHUNKS_PER_TICK = 24;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	private static final class LevelQueue {
		final LongOpenHashSet loaded = new LongOpenHashSet();
		final LongOpenHashSet queued = new LongOpenHashSet();
		final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();

		void add(long chunk) {
			if (this.queued.add(chunk)) {
				this.queue.enqueue(chunk);
			}
		}
	}

	private static final Map<ResourceKey<Level>, LevelQueue> LEVELS = new HashMap<>();
	private static boolean applied;

	private SnowConversion() {
	}

	private static LevelQueue queue(ServerLevel level) {
		return LEVELS.computeIfAbsent(level.dimension(), k -> new LevelQueue());
	}

	public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
		LevelQueue q = queue(level);
		long key = chunk.getPos().pack();
		q.loaded.add(key);
		q.add(key);
	}

	public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
		LevelQueue q = LEVELS.get(level.dimension());
		if (q != null) {
			long key = chunk.getPos().pack();
			q.loaded.remove(key);
			q.queued.remove(key);
		}
	}

	public static void tick(ServerLevel level) {
		boolean want = ModSettings.realisticSnow;
		if (want != applied) {
			applied = want;
			for (LevelQueue q : LEVELS.values()) {
				for (long key : q.loaded) {
					q.add(key);
				}
			}
			RealisticWater.LOGGER.info("[snow] realistic snow {}: converting snow in loaded chunks", want ? "on" : "off");
		}
		LevelQueue q = LEVELS.get(level.dimension());
		if (q == null) {
			return;
		}
		int converted = 0;
		for (int n = 0; n < CHUNKS_PER_TICK && !q.queue.isEmpty(); n++) {
			long key = q.queue.dequeueLong();
			q.queued.remove(key);
			if (!q.loaded.contains(key)) {
				continue;
			}
			ChunkPos pos = ChunkPos.unpack(key);
			LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
			if (chunk != null) {
				converted += convert(level, chunk, want);
			}
		}
		if (converted > 0) {
			RealisticWater.LOGGER.debug("[snow] {} snow blocks {}", converted, want ? "made realistic" : "back to vanilla");
		}
	}

	private static boolean isVanillaSnow(BlockState state) {
		return state.is(Blocks.SNOW) || state.is(Blocks.SNOW_BLOCK);
	}

	private static boolean isConverted(BlockState state) {
		return state.getBlock() instanceof RealisticSnowBlock && state.getValue(RealisticSnowBlock.ORIGIN) != SnowOrigin.PLACED;
	}

	private static int convert(ServerLevel level, LevelChunk chunk, boolean toRealistic) {
		LevelChunkSection[] sections = chunk.getSections();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int x0 = chunk.getPos().getMinBlockX();
		int z0 = chunk.getPos().getMinBlockZ();
		int count = 0;
		for (int s = 0; s < sections.length; s++) {
			LevelChunkSection section = sections[s];
			if (section.hasOnlyAir() || !section.maybeHas(toRealistic ? SnowConversion::isVanillaSnow : SnowConversion::isConverted)) {
				continue;
			}
			int baseY = (level.getMinSectionY() + s) << 4;
			for (int i = 0; i < 4096; i++) {
				BlockState state = section.getBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15);
				BlockState next = toRealistic ? realistic(state) : vanilla(state);
				if (next != null) {
					pos.set(x0 + (i & 15), baseY + ((i >> 8) & 15), z0 + ((i >> 4) & 15));
					level.setBlock(pos, next, FLAGS);
					count++;
				}
			}
		}
		return count;
	}

	private static BlockState realistic(BlockState state) {
		if (state.is(Blocks.SNOW)) {
			return ModContent.REALISTIC_SNOW.defaultBlockState().setValue(RealisticSnowBlock.LAYERS, state.getValue(SnowLayerBlock.LAYERS))
				.setValue(RealisticSnowBlock.ORIGIN, SnowOrigin.LAYER);
		}
		if (state.is(Blocks.SNOW_BLOCK)) {
			return ModContent.REALISTIC_SNOW.defaultBlockState().setValue(RealisticSnowBlock.LAYERS, RealisticSnowBlock.MAX_LAYERS)
				.setValue(RealisticSnowBlock.ORIGIN, SnowOrigin.BLOCK);
		}
		return null;
	}

	private static BlockState vanilla(BlockState state) {
		if (!isConverted(state)) {
			return null;
		}
		int layers = state.getValue(RealisticSnowBlock.LAYERS);
		if (state.getValue(RealisticSnowBlock.ORIGIN) == SnowOrigin.BLOCK && layers == RealisticSnowBlock.MAX_LAYERS) {
			return Blocks.SNOW_BLOCK.defaultBlockState();
		}
		return Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
	}

	public static void clear() {
		LEVELS.clear();
		applied = false;
	}

	public static void forget(ServerLevel level) {
		LEVELS.remove(level.dimension());
	}
}
