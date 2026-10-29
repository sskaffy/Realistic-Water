package me.skaffy.fluid;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.RealisticWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

public final class BoilingWater {
	public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, RealisticWater.id("boiling_water"));
	private static final long MEMORY_TICKS = 30L;
	private static final int REACH = 512;

	private static final class Boiling {
		final Long2LongOpenHashMap until = new Long2LongOpenHashMap();
		float damage = 1.0F;
	}

	private static final Map<ServerLevel, Boiling> LEVELS = new HashMap<>();

	private BoilingWater() {
	}

	public static void report(ServerLevel level, BlockPos near, long[] positions, float damage) {
		Boiling boiling = LEVELS.computeIfAbsent(level, l -> new Boiling());
		boiling.damage = Mth.clamp(damage, 0.0F, 4.0F);
		long until = level.getGameTime() + MEMORY_TICKS;
		for (long key : positions) {
			BlockPos pos = BlockPos.of(key);
			if (pos.distSqr(near) <= (double) REACH * REACH && level.isLoaded(pos) && RealisticWaterFluid.isRealistic(level.getFluidState(pos))) {
				boiling.until.put(key, until);
			}
		}
	}

	public static void tick(ServerLevel level) {
		Boiling boiling = LEVELS.get(level);
		if (boiling == null) {
			return;
		}
		long now = level.getGameTime();
		boiling.until.long2LongEntrySet().removeIf(e -> e.getLongValue() < now);
		if (boiling.until.isEmpty()) {
			LEVELS.remove(level);
			return;
		}
		if (boiling.damage <= 0.0F) {
			return;
		}
		DamageSource source = new DamageSource(level.damageSources().damageTypes.getOrThrow(DAMAGE));
		for (Long2LongMap.Entry e : boiling.until.long2LongEntrySet()) {
			BlockPos pos = BlockPos.of(e.getLongKey());
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos))) {
				entity.hurtServer(level, source, boiling.damage);
			}
		}
	}

	public static void forget(ServerLevel level) {
		LEVELS.remove(level);
	}

	public static void clear() {
		LEVELS.clear();
	}
}
