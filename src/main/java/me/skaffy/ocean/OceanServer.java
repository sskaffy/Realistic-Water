package me.skaffy.ocean;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import me.skaffy.block.RealisticWaterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

public final class OceanServer {
	public static final int MAX_RADIUS = 160;
	public static final int MAX_ZONES = 16;
	private static final int INTERVAL = 4;
	private static final int CLEANUP_INTERVAL = 20;
	private static final int REVERT_SLACK = 8;
	private static final int CREST_BLOCKS = 3;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	private static final class Player {
		boolean enabled;
		int radius = 48;
		float waveHeight = 1.0F;
		List<int[]> zones = List.of();

		boolean makesWaves() {
			return this.enabled && this.radius > 0 && this.waveHeight > 0.0F;
		}
	}

	private static final class LevelState {
		final Long2ObjectOpenHashMap<LongOpenHashSet> touched = new Long2ObjectOpenHashMap<>();
		final Long2FloatOpenHashMap amplitude = new Long2FloatOpenHashMap();
	}

	private static final Map<UUID, Player> PLAYERS = new HashMap<>();
	private static final Map<ServerLevel, LevelState> LEVELS = new IdentityHashMap<>();

	private OceanServer() {
	}

	private static LevelState state(ServerLevel level) {
		return LEVELS.computeIfAbsent(level, l -> new LevelState());
	}

	public static void setMode(ServerPlayer player, boolean enabled, int radius, float waveHeight) {
		Player p = PLAYERS.computeIfAbsent(player.getUUID(), id -> new Player());
		boolean was = p.makesWaves();
		p.enabled = enabled;
		p.radius = Math.clamp(radius, 0, MAX_RADIUS);
		p.waveHeight = Math.clamp(waveHeight, 0.0F, 4.0F);
		if (!enabled) {
			p.zones = List.of();
		}
		if (was && !p.makesWaves()) {
			resetAround(player);
		}
	}

	public static void forget(ServerPlayer player) {
		PLAYERS.remove(player.getUUID());
	}

	public static void forget(ServerLevel level) {
		LEVELS.remove(level);
	}

	public static void clear() {
		PLAYERS.clear();
		LEVELS.clear();
	}

	private static boolean makesWaves(ServerPlayer player) {
		Player p = PLAYERS.get(player.getUUID());
		return p != null && p.makesWaves();
	}

	public static void setZones(ServerPlayer player, List<int[]> zones) {
		Player p = PLAYERS.get(player.getUUID());
		if (p == null || !p.enabled) {
			return;
		}
		List<int[]> old = p.zones;
		p.zones = List.copyOf(zones);
		ServerLevel level = player.level();
		LevelState state = LEVELS.get(level);
		if (state == null) {
			return;
		}
		for (int[] box : old) {
			LongArrayList left = new LongArrayList();
			forTracked(state, box[0], box[2], box[3], box[5], key -> {
				int x = BlockPos.getX(key);
				int z = BlockPos.getZ(key);
				if (!inAnyZone(x, z, 0)) {
					left.add(key);
				}
			});
			for (long key : left) {
				revert(level, state, key);
			}
		}
	}

	private interface KeyVisitor {
		void visit(long key);
	}

	private static void forTracked(LevelState state, int x0, int z0, int x1, int z1, KeyVisitor visitor) {
		for (int cz = SectionPos.blockToSectionCoord(z0); cz <= SectionPos.blockToSectionCoord(z1 - 1); cz++) {
			for (int cx = SectionPos.blockToSectionCoord(x0); cx <= SectionPos.blockToSectionCoord(x1 - 1); cx++) {
				LongOpenHashSet set = state.touched.get(ChunkPos.pack(cx, cz));
				if (set == null) {
					continue;
				}
				for (LongIterator it = set.iterator(); it.hasNext(); ) {
					long key = it.nextLong();
					int x = BlockPos.getX(key);
					int z = BlockPos.getZ(key);
					if (x >= x0 && x < x1 && z >= z0 && z < z1) {
						visitor.visit(key);
					}
				}
			}
		}
	}

	private static boolean inAnyZone(int x, int z, int margin) {
		for (Player p : PLAYERS.values()) {
			if (!p.enabled) {
				continue;
			}
			for (int[] b : p.zones) {
				if (x >= b[0] - margin && x < b[3] + margin && z >= b[2] - margin && z < b[5] + margin) {
					return true;
				}
			}
		}
		return false;
	}

	public static void applySync(ServerPlayer player, long[] positions, byte[] amounts) {
		Player p = PLAYERS.get(player.getUUID());
		if (p == null || !p.enabled || p.radius <= 0) {
			return;
		}
		ServerLevel level = player.level();
		LevelState state = state(level);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < positions.length; i++) {
			pos.set(positions[i]);
			if (!level.isLoaded(pos) || pos.distSqr(player.blockPosition()) > 512 * 512 || !inAnyZone(pos.getX(), pos.getZ(), 2)) {
				continue;
			}
			BlockState current = level.getBlockState(pos);
			int amount = amounts[i];
			BlockState target;
			if (current.getBlock() == Blocks.WATER) {
				if (amount >= 8) {
					continue;
				}
				target = OceanWaves.oceanState(Math.max(1, amount), OceanOrigin.WATER);
			} else if (current.isAir()) {
				if (amount <= 0) {
					continue;
				}
				target = OceanWaves.oceanState(amount, OceanOrigin.AIR);
			} else if (RealisticWaterBlock.isOcean(current)) {
				OceanOrigin origin = RealisticWaterBlock.origin(current);
				if (origin == OceanOrigin.WATER) {
					target = amount >= 8 ? Blocks.WATER.defaultBlockState() : OceanWaves.oceanState(Math.max(1, amount), origin);
				} else {
					target = amount <= 0 ? Blocks.AIR.defaultBlockState() : OceanWaves.oceanState(amount, origin);
				}
			} else {
				continue;
			}
			write(level, state, pos, current, target);
		}
	}

	private static void write(ServerLevel level, LevelState state, BlockPos pos, BlockState current, BlockState target) {
		if (current == target) {
			return;
		}
		level.setBlock(pos, target, FLAGS);
		long chunk = ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
		if (RealisticWaterBlock.isOcean(target)) {
			state.touched.computeIfAbsent(chunk, k -> new LongOpenHashSet()).add(pos.asLong());
		} else {
			LongOpenHashSet set = state.touched.get(chunk);
			if (set != null) {
				set.remove(pos.asLong());
				if (set.isEmpty()) {
					state.touched.remove(chunk);
				}
			}
		}
	}

	private static void revert(ServerLevel level, LevelState state, long key) {
		BlockPos pos = BlockPos.of(key);
		long chunk = ChunkPos.pack(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
		if (level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())) != null) {
			BlockState current = level.getBlockState(pos);
			if (RealisticWaterBlock.isOcean(current)) {
				level.setBlock(pos, OceanWaves.restState(current), FLAGS);
			}
		}
		LongOpenHashSet set = state.touched.get(chunk);
		if (set != null) {
			set.remove(key);
			if (set.isEmpty()) {
				state.touched.remove(chunk);
			}
		}
	}

	private static void resetAround(ServerPlayer player) {
		ServerLevel level = player.level();
		LevelState state = LEVELS.get(level);
		if (state == null) {
			return;
		}
		int reach = (level.getServer().getPlayerList().getViewDistance() + 1) * 16;
		int px = player.getBlockX();
		int pz = player.getBlockZ();
		LongArrayList gone = new LongArrayList();
		forTracked(state, px - reach, pz - reach, px + reach, pz + reach, key -> {
			if (!coveredByOther(level, player, BlockPos.getX(key), BlockPos.getZ(key))) {
				gone.add(key);
			}
		});
		for (long key : gone) {
			revert(level, state, key);
		}
	}

	private static boolean coveredByOther(ServerLevel level, @Nullable ServerPlayer except, int x, int z) {
		for (ServerPlayer other : level.players()) {
			if (other == except || !makesWaves(other)) {
				continue;
			}
			int r = PLAYERS.get(other.getUUID()).radius + REVERT_SLACK;
			if (Math.abs(other.getBlockX() - x) <= r && Math.abs(other.getBlockZ() - z) <= r) {
				return true;
			}
		}
		return false;
	}

	public static void tick(ServerLevel level) {
		long time = level.getGameTime();
		for (ServerPlayer player : level.players()) {
			Player p = PLAYERS.get(player.getUUID());
			if (p != null && p.makesWaves()) {
				waves(level, player, p, time);
			}
		}
		if (time % CLEANUP_INTERVAL == 0L) {
			LevelState state = LEVELS.get(level);
			if (state == null || state.touched.isEmpty()) {
				return;
			}
			LongArrayList gone = new LongArrayList();
			for (Long2ObjectMap.Entry<LongOpenHashSet> e : state.touched.long2ObjectEntrySet()) {
				for (LongIterator it = e.getValue().iterator(); it.hasNext(); ) {
					long key = it.nextLong();
					int x = BlockPos.getX(key);
					int z = BlockPos.getZ(key);
					if (!coveredByOther(level, null, x, z) && !inAnyZone(x, z, 2)) {
						gone.add(key);
					}
				}
			}
			for (long key : gone) {
				revert(level, state, key);
			}
		}
	}

	private static void waves(ServerLevel level, ServerPlayer player, Player p, long time) {
		LevelState state = state(level);
		OceanWaves.Waves waves = OceanWaves.at(time, 0.0F, p.waveHeight * OceanWaves.weather(level));
		OceanWaves.States states = (x, y, z) -> {
			LevelChunk c = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
			return c == null ? Blocks.AIR.defaultBlockState() : c.getBlockState(new BlockPos(x, y, z));
		};
		int r = p.radius;
		int phase = (int) Math.floorMod(time, (long) INTERVAL);
		int px = player.getBlockX();
		int pz = player.getBlockZ();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cz = SectionPos.blockToSectionCoord(pz - r); cz <= SectionPos.blockToSectionCoord(pz + r); cz++) {
			for (int cx = SectionPos.blockToSectionCoord(px - r); cx <= SectionPos.blockToSectionCoord(px + r); cx++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
				if (chunk == null) {
					continue;
				}
				int x0 = Math.max(cx << 4, px - r);
				int x1 = Math.min((cx << 4) + 15, px + r);
				int z0 = Math.max(cz << 4, pz - r);
				int z1 = Math.min((cz << 4) + 15, pz + r);
				for (int z = z0; z <= z1; z++) {
					for (int x = x0; x <= x1; x++) {
						if (Math.floorMod(x + 2 * z, INTERVAL) != phase || inAnyZone(x, z, 0)) {
							continue;
						}
						column(level, state, chunk, states, waves, x, z, pos);
					}
				}
			}
		}
	}

	private static void column(ServerLevel level, LevelState state, LevelChunk chunk, OceanWaves.States states, OceanWaves.Waves waves, int x, int z,
		BlockPos.MutableBlockPos pos) {
		int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		int s = Integer.MIN_VALUE;
		for (int y = top; y >= top - CREST_BLOCKS - 1; y--) {
			BlockState here = chunk.getBlockState(pos.set(x, y, z));
			if (OceanWaves.isRestAir(here)) {
				continue;
			}
			if (OceanWaves.isWaveSurface(here, chunk.getBlockState(pos.set(x, y + 1, z)))) {
				s = y;
			}
			break;
		}
		if (s == Integer.MIN_VALUE) {
			return;
		}
		long key = BlockPos.asLong(x, s, z);
		float amp;
		if (state.amplitude.containsKey(key)) {
			amp = state.amplitude.get(key);
		} else {
			amp = OceanWaves.columnAmplitude(states, x, s, z, OceanWaves.biomeFactor(level.getBiome(pos.set(x, s, z))));
			state.amplitude.put(key, amp);
		}
		double surface = s + OceanWaves.REST_HEIGHT + amp * waves.height(x + 0.5, z + 0.5);
		for (int k = 0; k <= CREST_BLOCKS; k++) {
			int y = s + k;
			pos.set(x, y, z);
			BlockState current = chunk.getBlockState(pos);
			double fill = Math.clamp(surface - y, 0.0, 1.0);
			int amount = (int) Math.round(fill * 8.0);
			BlockState target;
			if (k == 0) {
				amount = Math.max(amount, 1);
				target = amount >= 7 ? Blocks.WATER.defaultBlockState() : OceanWaves.oceanState(amount, OceanOrigin.WATER);
				if (target.getBlock() == Blocks.WATER && current.getBlock() == Blocks.WATER) {
					continue;
				}
			} else if (amount < 2) {
				if (!RealisticWaterBlock.isOcean(current)) {
					continue;
				}
				target = Blocks.AIR.defaultBlockState();
			} else {
				if (!OceanWaves.isRestAir(current)) {
					continue;
				}
				target = OceanWaves.oceanState(amount, OceanOrigin.AIR);
			}
			write(level, state, pos, current, target);
		}
	}

	public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
		LevelState state = LEVELS.get(level);
		if (state == null) {
			return;
		}
		ChunkPos c = chunk.getPos();
		state.touched.remove(ChunkPos.pack(c.x(), c.z()));
		int x0 = c.x() << 4;
		int z0 = c.z() << 4;
		state.amplitude.keySet().removeIf((long key) -> (BlockPos.getX(key) & ~15) == x0 && (BlockPos.getZ(key) & ~15) == z0);
	}

	public static void cleanForSave(SerializableChunkData data) {
		for (SerializableChunkData.SectionData section : data.sectionData()) {
			LevelChunkSection s = section.chunkSection();
			if (s == null || s.hasOnlyAir() || !s.maybeHas(RealisticWaterBlock::isOcean)) {
				continue;
			}
			for (int i = 0; i < 4096; i++) {
				int x = i & 15;
				int y = (i >> 4) & 15;
				int z = i >> 8;
				BlockState state = s.getBlockState(x, y, z);
				if (RealisticWaterBlock.isOcean(state)) {
					s.setBlockState(x, y, z, OceanWaves.restState(state), false);
				}
			}
		}
	}

	public static List<int[]> readZones(int[] flat) {
		List<int[]> out = new ArrayList<>();
		for (int i = 0; i + 5 < flat.length && out.size() < MAX_ZONES; i += 6) {
			out.add(new int[]{flat[i], flat[i + 1], flat[i + 2], flat[i + 3], flat[i + 4], flat[i + 5]});
		}
		return out;
	}
}
