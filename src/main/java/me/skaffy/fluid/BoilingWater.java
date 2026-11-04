package me.skaffy.fluid;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.RealisticWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

public final class BoilingWater {
	public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, RealisticWater.id("boiling_water"));
	private static final long MEMORY_TICKS = 30L;
	private static final int REACH = 512;
	private static final int SCALD_FROM = 60;
	private static final int SCALD_FULL = 100;

	private static final class Boiling {
		final Long2LongOpenHashMap until = new Long2LongOpenHashMap();
		final Long2IntOpenHashMap degrees = new Long2IntOpenHashMap();
		float damage = 1.0F;
	}

	private static final class Scald extends DamageSource {
		private final int degrees;

		Scald(Holder<DamageType> type, int degrees) {
			super(type);
			this.degrees = degrees;
		}

		@Override
		public Component getLocalizedDeathMessage(final LivingEntity victim) {
			String key = "death.attack.realistic-water.cooked";
			LivingEntity killer = victim.getKillCredit();
			return killer != null
				? Component.translatable(key + ".player", victim.getDisplayName(), this.degrees, killer.getDisplayName())
				: Component.translatable(key, victim.getDisplayName(), this.degrees);
		}
	}

	private static final Map<ServerLevel, Boiling> LEVELS = new HashMap<>();

	private BoilingWater() {
	}

	public static void report(ServerLevel level, BlockPos near, long[] positions, byte[] degrees, float damage) {
		Boiling boiling = LEVELS.computeIfAbsent(level, l -> new Boiling());
		boiling.damage = Mth.clamp(damage, 0.0F, 4.0F);
		long until = level.getGameTime() + MEMORY_TICKS;
		for (int i = 0; i < positions.length && i < degrees.length; i++) {
			long key = positions[i];
			BlockPos pos = BlockPos.of(key);
			if (pos.distSqr(near) <= (double) REACH * REACH && level.isLoaded(pos) && RealisticWaterFluid.isRealistic(level.getFluidState(pos))) {
				boiling.until.put(key, until);
				boiling.degrees.put(key, degrees[i] & 0xFF);
			}
		}
	}

	static float scald(int degrees) {
		if (degrees < SCALD_FROM) {
			return 0.0F;
		}
		return 1.0F + 3.0F * Mth.clamp((degrees - SCALD_FROM) / (float) (SCALD_FULL - SCALD_FROM), 0.0F, 1.0F);
	}

	public static void tick(ServerLevel level) {
		Boiling boiling = LEVELS.get(level);
		if (boiling == null) {
			return;
		}
		long now = level.getGameTime();
		boiling.until.long2LongEntrySet().removeIf(e -> e.getLongValue() < now);
		boiling.degrees.keySet().removeIf(key -> !boiling.until.containsKey(key));
		if (boiling.until.isEmpty()) {
			LEVELS.remove(level);
			return;
		}
		if (boiling.damage <= 0.0F) {
			return;
		}
		Holder<DamageType> type = level.damageSources().damageTypes.getOrThrow(DAMAGE);
		for (Long2LongMap.Entry e : boiling.until.long2LongEntrySet()) {
			BlockPos pos = BlockPos.of(e.getLongKey());
			int degrees = boiling.degrees.get(e.getLongKey());
			float hurt = scald(degrees) * boiling.damage;
			if (hurt <= 0.0F) {
				continue;
			}
			for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos))) {
				if (!entity.blockPosition().equals(pos) || entity.fireImmune() || entity.hasEffect(MobEffects.FIRE_RESISTANCE)) {
					continue;
				}
				entity.hurtServer(level, new Scald(type, degrees), hurt);
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
