package me.skaffy.portal;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.ModContent;
import me.skaffy.block.RealisticLeavesBlock;
import me.skaffy.block.RealisticSandBlock;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.fluid.FluidMixing;
import me.skaffy.fluid.RealisticLavaFluid;
import me.skaffy.fluid.RealisticSlimeFluid;
import me.skaffy.fluid.RealisticWaterFluid;
import me.skaffy.net.LavaSyncPayload;
import me.skaffy.net.LeavesSyncPayload;
import me.skaffy.net.SandSyncPayload;
import me.skaffy.net.SlimeSyncPayload;
import me.skaffy.net.SteamPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import me.skaffy.net.SyncMaterial;
import me.skaffy.net.WaterSyncPayload;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public final class PortalTransit {
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;
	private static final long EXIT_TICKS = 100;
	private static final int SEARCH = 512;
	private static final int MAX_DROP = 64;
	private static final double STEAM_REACH = 160.0;
	private static final int STEAM_SOUND_TICKS = 10;
	private static final double TOUCH = 0.02;
	private static final Direction[] SPREAD = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN};

	private record Exit(ResourceKey<Level> dimension, Vec3 pos, long until) {
	}

	private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<Exit>> EXITS = new HashMap<>();
	private static final Map<ResourceKey<Level>, Long2ObjectOpenHashMap<float[]>> HELD = new HashMap<>();
	private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> STEAM_SOUNDS = new HashMap<>();

	private PortalTransit() {
	}

	public static void clear() {
		EXITS.clear();
		HELD.clear();
		STEAM_SOUNDS.clear();
	}

	public static void material(ServerLevel level, BlockPos portalPos, byte material, float eighths) {
		if (material < SyncMaterial.WATER || material > SyncMaterial.LEAVES || !level.isLoaded(portalPos)
			|| !(level.getBlockState(portalPos).getBlock() instanceof Portal portal)) {
			return;
		}
		Exit exit = exitFor(level, portalPos, portal);
		ServerLevel to = exit == null ? null : level.getServer().getLevel(exit.dimension());
		if (to == null) {
			return;
		}
		BlockPos at = BlockPos.containing(exit.pos());
		if (material == SyncMaterial.WATER && to.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, at)) {
			steam(to, exit.pos(), eighths);
			return;
		}
		float[] held = HELD.computeIfAbsent(to.dimension(), k -> new Long2ObjectOpenHashMap<>()).computeIfAbsent(at.asLong(), k -> new float[5]);
		held[material] += eighths;
		int whole = (int) held[material];
		if (whole > 0) {
			int left = deposit(to, at, material, whole);
			held[material] -= whole - left;
		}
	}

	private static @Nullable Exit exitFor(ServerLevel level, BlockPos portalPos, Portal portal) {
		long now = level.getGameTime();
		Long2ObjectOpenHashMap<Exit> cache = EXITS.computeIfAbsent(level.dimension(), k -> new Long2ObjectOpenHashMap<>());
		Exit known = cache.get(portalPos.asLong());
		if (known != null && known.until() > now) {
			return known;
		}
		Marker stand = EntityTypes.MARKER.create(level, EntitySpawnReason.TRIGGERED);
		if (stand == null) {
			return null;
		}
		stand.setPos(Vec3.atBottomCenterOf(portalPos));
		TeleportTransition transition = portal.getPortalDestination(level, stand, portalPos);
		if (transition == null || !level.isAllowedToEnterPortal(transition.newLevel())) {
			cache.remove(portalPos.asLong());
			return null;
		}
		Exit exit = new Exit(transition.newLevel().dimension(), transition.position(), now + EXIT_TICKS);
		cache.put(portalPos.asLong(), exit);
		return exit;
	}

	private static void steam(ServerLevel level, Vec3 at, float eighths) {
		steamBurst(level, at.add(0.0, 0.5, 0.0), eighths);
		long key = BlockPos.containing(at).asLong();
		Long2LongOpenHashMap sounds = STEAM_SOUNDS.computeIfAbsent(level.dimension(), k -> new Long2LongOpenHashMap());
		long now = level.getGameTime();
		if (now - sounds.getOrDefault(key, Long.MIN_VALUE / 2) >= STEAM_SOUND_TICKS) {
			sounds.put(key, now);
			level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F,
				2.6F + (level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.8F);
		}
	}

	public static void steamBurst(ServerLevel level, Vec3 at, float eighths) {
		SteamPayload payload = new SteamPayload(at.x, at.y, at.z, eighths);
		for (ServerPlayer player : level.players()) {
			if (player.distanceToSqr(at) < STEAM_REACH * STEAM_REACH) {
				ServerPlayNetworking.send(player, payload);
			}
		}
	}

	private static int deposit(ServerLevel level, BlockPos start, byte material, int eighths) {
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		queue.add(start);
		seen.add(start.asLong());
		int visited = 0;
		while (eighths > 0 && !queue.isEmpty() && visited++ < SEARCH) {
			BlockPos pos = queue.poll();
			if (!level.isInsideBuildHeight(pos.getY())) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof Portal)) {
				int have = amount(state, material);
				if (have < 0) {
					continue;
				}
				BlockPos rest = have == 0 ? settle(level, pos) : pos;
				int put = Math.min(8 - have, eighths);
				if (put > 0) {
					level.setBlock(rest, stateFor(material, have + put), FLAGS);
					if (material == SyncMaterial.WATER) {
						FluidMixing.solidifyLavaAround(level, rest);
					} else if (material == SyncMaterial.LAVA) {
						FluidMixing.solidifyIfTouchingWater(level, rest);
					}
					eighths -= put;
				}
			}
			for (Direction d : SPREAD) {
				BlockPos next = pos.relative(d);
				if (seen.add(next.asLong())) {
					queue.add(next);
				}
			}
		}
		return eighths;
	}

	private static BlockPos settle(ServerLevel level, BlockPos pos) {
		BlockPos.MutableBlockPos p = pos.mutable();
		for (int i = 0; i < MAX_DROP && p.getY() > level.getMinY() && level.getBlockState(p.move(Direction.DOWN)).isAir(); i++) {
		}
		if (!level.getBlockState(p).isAir()) {
			p.move(Direction.UP);
		}
		return p.immutable();
	}

	private static int amount(BlockState state, byte material) {
		if (state.isAir()) {
			return 0;
		}
		Block block = state.getBlock();
		return switch (material) {
			case SyncMaterial.WATER -> block == ModContent.REALISTIC_WATER_BLOCK && RealisticWaterFluid.isRealistic(state.getFluidState())
				&& !RealisticWaterBlock.isInfinite(state) ? state.getFluidState().getAmount() : -1;
			case SyncMaterial.LAVA -> block == ModContent.REALISTIC_LAVA_BLOCK && RealisticLavaFluid.isRealistic(state.getFluidState())
				&& !RealisticWaterBlock.isInfinite(state) ? state.getFluidState().getAmount() : -1;
			case SyncMaterial.SLIME -> block == ModContent.REALISTIC_SLIME_BLOCK && RealisticSlimeFluid.isRealistic(state.getFluidState())
				&& !RealisticWaterBlock.isInfinite(state) ? state.getFluidState().getAmount() : -1;
			case SyncMaterial.SAND -> block == ModContent.REALISTIC_SAND ? RealisticSandBlock.layers(state) : -1;
			case SyncMaterial.LEAVES -> block == ModContent.REALISTIC_LEAVES ? RealisticLeavesBlock.layers(state) : -1;
			default -> -1;
		};
	}

	private static BlockState stateFor(byte material, int eighths) {
		return switch (material) {
			case SyncMaterial.LAVA -> LavaSyncPayload.stateFor(eighths);
			case SyncMaterial.SLIME -> SlimeSyncPayload.stateFor(eighths);
			case SyncMaterial.SAND -> SandSyncPayload.stateFor(eighths);
			case SyncMaterial.LEAVES -> LeavesSyncPayload.stateFor(eighths);
			default -> WaterSyncPayload.stateFor(eighths);
		};
	}

	public static @Nullable BlockPos touchedPortal(ServerLevel level, RealisticBlockEntity body) {
		AABB box = body.getBoundingBox().inflate(TOUCH);
		for (BlockPos pos : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ), Mth.floor(box.maxX),
			Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
			BlockState state = level.getBlockState(pos);
			if (!(state.getBlock() instanceof Portal)) {
				continue;
			}
			VoxelShape shape = state.getShape(level, pos);
			AABB area = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
			if (!body.orientedBoxes(area.inflate(TOUCH)).isEmpty()) {
				return pos.immutable();
			}
		}
		return null;
	}
}
