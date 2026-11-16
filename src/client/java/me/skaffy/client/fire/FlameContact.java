package me.skaffy.client.fire;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.skaffy.ModContent;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterWorld;
import me.skaffy.mixin.FireBlockAccessor;
import me.skaffy.net.FlameTouchPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class FlameContact {
	private static final int INTERVAL = 4;
	private static final double ENTITY_RANGE = 48.0;

	private record Volume(AABB box, long expires) {
	}

	private static final Long2ObjectOpenHashMap<Volume> VOLUMES = new Long2ObjectOpenHashMap<>();
	private static ClientLevel volumesLevel;

	private FlameContact() {
	}

	public static int volumes() {
		return VOLUMES.size();
	}

	static void add(ClientLevel level, double x, double y, double z, double halfX, double halfZ, double height, float vx, float vy, float vz) {
		if (!WaterSettings.fireFlameHitbox && !WaterSettings.fireFlameIgnites) {
			return;
		}
		if (volumesLevel != level) {
			VOLUMES.clear();
			volumesLevel = level;
		}
		double life = Math.max(WaterSettings.fireFlameLife, 0.05F);
		double reach = Mth.clamp(Math.max(vy, 0.0F) * life * 0.35 + 0.1, 0.1, 1.2);
		double driftX = vx * life * 0.35;
		double driftZ = vz * life * 0.35;
		AABB box = new AABB(x - halfX + Math.min(driftX, 0.0), y, z - halfZ + Math.min(driftZ, 0.0),
			x + halfX + Math.max(driftX, 0.0), y + height + reach, z + halfZ + Math.max(driftZ, 0.0));
		long key = BlockPos.asLong(Mth.floor(x * 4.0), Mth.floor(y * 4.0), Mth.floor(z * 4.0));
		VOLUMES.put(key, new Volume(box, level.getGameTime() + (long) Math.ceil(life * 20.0) + 2L));
	}

	private static boolean touches(AABB box) {
		for (Volume v : VOLUMES.values()) {
			if (v.box.intersects(box)) {
				return true;
			}
		}
		return false;
	}

	private static boolean flammable(BlockState state) {
		return state.is(Blocks.TNT) || state.is(ModContent.REALISTIC_LEAVES)
			|| ((FireBlockAccessor) Blocks.FIRE).realisticWater$burnOdds().getInt(state.getBlock()) > 0;
	}

	private static boolean nextToFire(ClientLevel level, BlockPos.MutableBlockPos probe, int x, int y, int z) {
		for (Direction d : Direction.values()) {
			if (level.getBlockState(probe.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())).getBlock() instanceof FireBlock) {
				return true;
			}
		}
		return false;
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null || level != volumesLevel) {
			VOLUMES.clear();
			volumesLevel = level;
			return;
		}
		if (mc.isPaused() || VOLUMES.isEmpty()) {
			return;
		}
		long time = level.getGameTime();
		VOLUMES.values().removeIf(v -> v.expires < time);
		if (time % INTERVAL != 0 || VOLUMES.isEmpty() || !ClientPlayNetworking.canSend(FlameTouchPayload.TYPE)) {
			return;
		}
		boolean self = false;
		IntArrayList entities = new IntArrayList();
		LongOpenHashSet blocks = new LongOpenHashSet();
		boolean host = WaterWorld.isHostClient();
		if (WaterSettings.fireFlameHitbox) {
			self = !mc.player.isSpectator() && !mc.player.isCreative() && !mc.player.fireImmune() && touches(mc.player.getBoundingBox().deflate(0.1, 0.0, 0.1));
			if (host) {
				for (Entity entity : level.entitiesForRendering()) {
					if (entities.size() >= FlameTouchPayload.MAX_ENTITIES) {
						break;
					}
					if (entity == mc.player || !(entity instanceof LivingEntity) || entity.fireImmune()
						|| entity.distanceToSqr(mc.player) > ENTITY_RANGE * ENTITY_RANGE) {
						continue;
					}
					if (touches(entity.getBoundingBox().deflate(0.1, 0.0, 0.1))) {
						entities.add(entity.getId());
					}
				}
			}
		}
		if (host && WaterSettings.fireFlameIgnites) {
			BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
			BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
			for (Volume v : VOLUMES.values()) {
				if (blocks.size() >= FlameTouchPayload.MAX_BLOCKS) {
					break;
				}
				AABB b = v.box.inflate(0.08);
				for (int x = Mth.floor(b.minX); x <= Mth.floor(b.maxX); x++) {
					for (int y = Mth.floor(b.minY); y <= Mth.floor(b.maxY); y++) {
						for (int z = Mth.floor(b.minZ); z <= Mth.floor(b.maxZ); z++) {
							long key = BlockPos.asLong(x, y, z);
							if (blocks.contains(key)) {
								continue;
							}
							BlockState state = level.getBlockState(probe.set(x, y, z));
							if (!state.isAir() && flammable(state) && !nextToFire(level, near, x, y, z)) {
								blocks.add(key);
							}
						}
					}
				}
			}
		}
		if (self || !entities.isEmpty() || !blocks.isEmpty()) {
			ClientPlayNetworking.send(new FlameTouchPayload(self, entities.toIntArray(), blocks.toLongArray()));
		}
	}
}
