package me.skaffy.client.fire;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import me.skaffy.ModContent;
import me.skaffy.block.RealisticBlastFurnaceBlock;
import me.skaffy.block.RealisticCandleBlock;
import me.skaffy.block.RealisticFireBlock;
import me.skaffy.block.RealisticFurnaceBlock;
import me.skaffy.block.RealisticRedstoneTorchBlock;
import me.skaffy.block.RealisticRedstoneWallTorchBlock;
import me.skaffy.block.RealisticSmokerBlock;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.fluid.RealisticLavaFluid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client-side registry of everything in the loaded world that should emit realistic fire and smoke.
 * Phase 1: detection and bookkeeping only. Nothing is rendered from here yet.
 */
public final class FireWorld {
	private static final FireWorld INSTANCE = new FireWorld();
	private static final int SCAN_CHUNKS_PER_TICK = 4;
	private static final int REFRESH_INTERVAL_TICKS = 10;

	public enum Kind {
		REALISTIC_FIRE(1.0F),
		REALISTIC_SOUL_FIRE(1.0F),
		REALISTIC_TORCH(0.25F),
		REALISTIC_SOUL_TORCH(0.25F),
		REALISTIC_COPPER_TORCH(0.25F),
		REALISTIC_REDSTONE_TORCH(0.1F),
		REALISTIC_FURNACE(0.3F),
		REALISTIC_SMOKER(0.2F),
		REALISTIC_CANDLE(0.1F),
		REALISTIC_CAMPFIRE(0.8F),
		REALISTIC_SOUL_CAMPFIRE(0.8F),
		FIRE(1.0F),
		SOUL_FIRE(1.0F),
		CAMPFIRE(0.8F),
		SOUL_CAMPFIRE(0.8F),
		TORCH(0.25F),
		SOUL_TORCH(0.25F),
		FURNACE(0.3F),
		LAVA(0.6F);

		public final float intensity;

		Kind(float intensity) {
			this.intensity = intensity;
		}

		public boolean soul() {
			return this == SOUL_FIRE || this == SOUL_CAMPFIRE || this == SOUL_TORCH || this == REALISTIC_SOUL_TORCH
				|| this == REALISTIC_SOUL_CAMPFIRE || this == REALISTIC_SOUL_FIRE;
		}
	}

	public record Source(long pos, Kind kind) {
		public BlockPos blockPos() {
			return BlockPos.of(this.pos);
		}

		public float intensity() {
			return this.kind.intensity;
		}
	}

	private final Long2ObjectOpenHashMap<Source> sources = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<LongOpenHashSet> byChunk = new Long2ObjectOpenHashMap<>();
	private final ArrayDeque<ChunkPos> scanQueue = new ArrayDeque<>();
	private final List<Source> active = new ArrayList<>();
	private @Nullable ClientLevel lastLevel;
	private boolean builtVanilla = WaterSettings.fireVanillaBlocks;
	private boolean dirty = true;
	private int ticksSinceRefresh;

	private FireWorld() {
	}

	public static FireWorld get() {
		return INSTANCE;
	}

	private static long chunkKey(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	private static long chunkKeyOf(long blockPos) {
		return chunkKey(BlockPos.getX(blockPos) >> 4, BlockPos.getZ(blockPos) >> 4);
	}

	private static boolean lit(BlockState state) {
		return state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
	}

	/** State-only classification. Lava still needs {@link #exposed} to filter buried blocks. */
	static @Nullable Kind classify(BlockState state) {
		if (state.isAir()) {
			return null;
		}
		Block b = state.getBlock();
		// The mod's own blocks always emit; vanilla blocks only when the player opts in.
		if (b instanceof RealisticFireBlock) {
			return Kind.REALISTIC_FIRE;
		}
		if (b == ModContent.REALISTIC_COPPER_TORCH || b == ModContent.REALISTIC_COPPER_WALL_TORCH) {
			return Kind.REALISTIC_COPPER_TORCH;
		}
		if (b instanceof RealisticRedstoneTorchBlock || b instanceof RealisticRedstoneWallTorchBlock) {
			return lit(state) ? Kind.REALISTIC_REDSTONE_TORCH : null;
		}
		if (b == ModContent.REALISTIC_SOUL_FIRE) {
			return Kind.REALISTIC_SOUL_FIRE;
		}
		if (b == ModContent.REALISTIC_TORCH || b == ModContent.REALISTIC_WALL_TORCH) {
			return Kind.REALISTIC_TORCH;
		}
		if (b == ModContent.REALISTIC_SOUL_TORCH || b == ModContent.REALISTIC_SOUL_WALL_TORCH) {
			return Kind.REALISTIC_SOUL_TORCH;
		}
		if (b instanceof RealisticFurnaceBlock || b instanceof RealisticBlastFurnaceBlock) {
			return lit(state) ? Kind.REALISTIC_FURNACE : null;
		}
		if (b instanceof RealisticSmokerBlock) {
			return lit(state) ? Kind.REALISTIC_SMOKER : null;
		}
		if (b instanceof RealisticCandleBlock) {
			return lit(state) ? Kind.REALISTIC_CANDLE : null;
		}
		if (b == ModContent.REALISTIC_CAMPFIRE) {
			return lit(state) ? Kind.REALISTIC_CAMPFIRE : null;
		}
		if (b == ModContent.REALISTIC_SOUL_CAMPFIRE) {
			return lit(state) ? Kind.REALISTIC_SOUL_CAMPFIRE : null;
		}
		if (RealisticLavaFluid.isRealistic(state.getFluidState())) {
			return Kind.LAVA;
		}
		if (!WaterSettings.fireVanillaBlocks) {
			return null;
		}
		if (b == Blocks.FIRE) {
			return Kind.FIRE;
		}
		if (b == Blocks.SOUL_FIRE) {
			return Kind.SOUL_FIRE;
		}
		if (b == Blocks.CAMPFIRE) {
			return lit(state) ? Kind.CAMPFIRE : null;
		}
		if (b == Blocks.SOUL_CAMPFIRE) {
			return lit(state) ? Kind.SOUL_CAMPFIRE : null;
		}
		if (b == Blocks.TORCH || b == Blocks.WALL_TORCH) {
			return Kind.TORCH;
		}
		if (b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH) {
			return Kind.SOUL_TORCH;
		}
		if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER) {
			return lit(state) ? Kind.FURNACE : null;
		}
		if (state.getFluidState().is(FluidTags.LAVA)) {
			return Kind.LAVA;
		}
		return null;
	}

	/** Lava only emits from its open surface: the block above must be neither lava nor a solid roof. */
	private static boolean exposed(ClientLevel level, BlockPos pos) {
		BlockPos above = pos.above();
		BlockState aboveState = level.getBlockState(above);
		return !aboveState.getFluidState().is(FluidTags.LAVA) && !aboveState.isFaceSturdy(level, above, Direction.DOWN);
	}

	private static @Nullable Kind refine(ClientLevel level, BlockPos pos, @Nullable Kind kind) {
		if (kind == Kind.LAVA && !exposed(level, pos)) {
			return null;
		}
		return kind;
	}

	private void put(long pos, @Nullable Kind kind) {
		Source current = this.sources.get(pos);
		if (kind == null) {
			if (current != null) {
				this.sources.remove(pos);
				LongOpenHashSet set = this.byChunk.get(chunkKeyOf(pos));
				if (set != null) {
					set.remove(pos);
					if (set.isEmpty()) {
						this.byChunk.remove(chunkKeyOf(pos));
					}
				}
				this.dirty = true;
			}
			return;
		}
		if (current != null && current.kind == kind) {
			return;
		}
		this.sources.put(pos, new Source(pos, kind));
		this.byChunk.computeIfAbsent(chunkKeyOf(pos), k -> new LongOpenHashSet()).add(pos);
		this.dirty = true;
	}

	public void onBlockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
		if (oldState == newState) {
			return;
		}
		this.put(pos.asLong(), refine(level, pos, classify(newState)));
		// A lava block loses or gains its "exposed" status when the block above it changes.
		BlockPos below = pos.below();
		Source under = this.sources.get(below.asLong());
		if (under != null && under.kind == Kind.LAVA || level.getFluidState(below).is(FluidTags.LAVA)) {
			this.put(below.asLong(), refine(level, below, classify(level.getBlockState(below))));
		}
	}

	public void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		this.scanQueue.add(chunk.getPos());
	}

	public void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		ChunkPos cp = chunk.getPos();
		LongOpenHashSet set = this.byChunk.remove(chunkKey(cp.x(), cp.z()));
		if (set != null) {
			for (long pos : set) {
				this.sources.remove(pos);
			}
			this.dirty = true;
		}
		this.scanQueue.remove(cp);
	}

	private void scanChunk(ClientLevel level, ChunkPos chunkPos) {
		if (!level.hasChunk(chunkPos.x(), chunkPos.z())) {
			return;
		}
		LevelChunk chunk = level.getChunk(chunkPos.x(), chunkPos.z());
		LevelChunkSection[] sections = chunk.getSections();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int s = 0; s < sections.length; s++) {
			LevelChunkSection section = sections[s];
			if (section.hasOnlyAir() || !section.maybeHas(state -> classify(state) != null)) {
				continue;
			}
			int baseY = (level.getMinSectionY() + s) << 4;
			for (int i = 0; i < 4096; i++) {
				Kind kind = classify(section.getBlockState(i & 15, (i >> 4) & 15, i >> 8));
				if (kind == null) {
					continue;
				}
				pos.set((chunkPos.x() << 4) + (i & 15), baseY + ((i >> 4) & 15), (chunkPos.z() << 4) + (i >> 8));
				this.put(pos.asLong(), refine(level, pos, kind));
			}
		}
	}

	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) {
			return;
		}
		if (level != this.lastLevel) {
			this.clear();
			this.lastLevel = level;
		}
		if (!WaterSettings.fire) {
			return;
		}
		if (WaterSettings.fireVanillaBlocks != this.builtVanilla) {
			this.builtVanilla = WaterSettings.fireVanillaBlocks;
			this.rescan(mc);
		}
		for (int i = 0; i < SCAN_CHUNKS_PER_TICK && !this.scanQueue.isEmpty(); i++) {
			this.scanChunk(level, this.scanQueue.poll());
		}
		if (++this.ticksSinceRefresh >= REFRESH_INTERVAL_TICKS || this.dirty) {
			this.ticksSinceRefresh = 0;
			this.refreshActive(mc);
		}
	}

	private void refreshActive(Minecraft mc) {
		this.dirty = false;
		this.active.clear();
		if (mc.player == null) {
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		double range = WaterSettings.fireRange;
		double r2 = range * range;
		List<Source> near = new ArrayList<>();
		List<Double> dist = new ArrayList<>();
		for (Source s : this.sources.values()) {
			double dx = BlockPos.getX(s.pos) + 0.5 - eye.x;
			double dy = BlockPos.getY(s.pos) + 0.5 - eye.y;
			double dz = BlockPos.getZ(s.pos) + 0.5 - eye.z;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 <= r2) {
				near.add(s);
				dist.add(d2);
			}
		}
		Integer[] order = new Integer[near.size()];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
		}
		java.util.Arrays.sort(order, Comparator.comparingDouble(dist::get));
		int cap = Math.max(0, WaterSettings.fireMaxSources);
		for (int i = 0; i < order.length && i < cap; i++) {
			this.active.add(near.get(order[i]));
		}
	}

	/** Nearest-first sources within range, capped by {@code fireMaxSources}. Valid until the next tick. */
	public List<Source> activeSources() {
		return this.active;
	}

	public int totalSources() {
		return this.sources.size();
	}

	public Map<Kind, Integer> countsByKind() {
		Map<Kind, Integer> out = new EnumMap<>(Kind.class);
		for (Source s : this.sources.values()) {
			out.merge(s.kind, 1, Integer::sum);
		}
		return out;
	}

	/** Drop everything and re-read the chunks around the player. */
	public void rescan(Minecraft mc) {
		this.clear();
		if (mc.level == null || mc.player == null) {
			return;
		}
		int radius = mc.options.renderDistance().get();
		ChunkPos center = ChunkPos.containing(mc.player.blockPosition());
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				this.scanQueue.add(new ChunkPos(center.x() + dx, center.z() + dz));
			}
		}
	}

	public String statsLine() {
		StringBuilder sb = new StringBuilder("Fire sources: ").append(this.sources.size()).append(" total, ")
			.append(this.active.size()).append(" active (range ").append(WaterSettings.fireRange).append(", cap ")
			.append(WaterSettings.fireMaxSources).append("), scan queue ").append(this.scanQueue.size());
		Map<Kind, Integer> counts = this.countsByKind();
		if (!counts.isEmpty()) {
			sb.append(" [");
			boolean first = true;
			for (Map.Entry<Kind, Integer> e : counts.entrySet()) {
				sb.append(first ? "" : ", ").append(e.getKey().name().toLowerCase()).append('=').append(e.getValue());
				first = false;
			}
			sb.append(']');
		}
		return sb.toString();
	}

	public void clear() {
		this.sources.clear();
		this.byChunk.clear();
		this.scanQueue.clear();
		this.active.clear();
		this.dirty = true;
	}
}
