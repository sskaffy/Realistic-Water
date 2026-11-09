package me.skaffy.client.fire;

import me.skaffy.client.water.WaterSettings;
import me.skaffy.fluid.RealisticLavaFluid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Draws flames and smoke for every active realistic fire source in {@link FireWorld}, and lets them interact with the world:
 * wind pushes smoke and bends flames, rain weakens flames and turns smoke into steam, water next to a fire makes steam, and
 * realistic lava throws embers. The vanilla blocks' own particles are removed, so this is where realistic fire is seen.
 */
public final class FireEffects {
	private static int spawnedLastTick;
	private static long totalSpawned;
	private static long steamSpawned;
	private static long emberSpawned;
	private static double windX;
	private static double windZ;

	private FireEffects() {
	}

	public static int spawnedLastTick() {
		return spawnedLastTick;
	}

	public static long totalSpawned() {
		return totalSpawned;
	}

	public static long steamSpawned() {
		return steamSpawned;
	}

	public static long emberSpawned() {
		return emberSpawned;
	}

	/** Current smoke drift in blocks per tick: slowly changing gusts, stronger when it rains or storms. */
	public static Vec3 wind() {
		return new Vec3(windX, 0.0, windZ);
	}

	private static void updateWind(ClientLevel level) {
		double t = level.getGameTime() * 0.01;
		double strength = WaterSettings.fireWind * (0.012 + 0.02 * level.getRainLevel(1.0F) + 0.02 * level.getThunderLevel(1.0F));
		windX = (Math.sin(t) + 0.5 * Math.sin(t * 2.3 + 1.0)) * strength;
		windZ = (Math.cos(t * 0.7) + 0.5 * Math.sin(t * 1.9 + 2.0)) * strength;
	}

	public static void tick(Minecraft mc) {
		spawnedLastTick = 0;
		ClientLevel level = mc.level;
		if (level == null || mc.player == null || mc.isPaused() || !WaterSettings.fire || !WaterSettings.fireParticles) {
			return;
		}
		updateWind(level);
		RandomSource random = level.getRandom();
		Vec3 eye = mc.player.getEyePosition();
		double rangeSq = (double) WaterSettings.fireParticleRange * WaterSettings.fireParticleRange;
		int budget = WaterSettings.fireParticleBudget;
		for (FireWorld.Source source : FireWorld.get().activeSources()) {
			if (spawnedLastTick >= budget) {
				return;
			}
			BlockPos pos = source.blockPos();
			if (eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > rangeSq) {
				continue;
			}
			FireWorld.Kind kind = source.kind();
			if (kind == FireWorld.Kind.LAVA) {
				if (WaterSettings.fireEmbers && RealisticLavaFluid.isRealistic(level.getFluidState(pos))) {
					embers(level, random, pos);
				}
			} else if (kind.name().startsWith("REALISTIC_")) {
				emit(level, random, pos, kind);
			}
		}
	}

	private static boolean nextToWater(ClientLevel level, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			if (level.getFluidState(pos.relative(dir)).is(FluidTags.WATER)) {
				return true;
			}
		}
		return false;
	}

	private static void count() {
		spawnedLastTick++;
		totalSpawned++;
	}

	private static void embers(ClientLevel level, RandomSource random, BlockPos pos) {
		float density = WaterSettings.fireParticleDensity;
		if (random.nextFloat() < 0.04F * density) {
			double x = pos.getX() + random.nextDouble();
			double z = pos.getZ() + random.nextDouble();
			level.addParticle(ParticleTypes.SMALL_FLAME, x, pos.getY() + 0.95, z,
				windX * 6 + (random.nextDouble() - 0.5) * 0.02, 0.06 + random.nextDouble() * 0.05, windZ * 6 + (random.nextDouble() - 0.5) * 0.02);
			count();
			emberSpawned++;
		}
		if (random.nextFloat() < 0.02F * density) {
			level.addParticle(ParticleTypes.SMOKE, pos.getX() + random.nextDouble(), pos.getY() + 1.0, pos.getZ() + random.nextDouble(),
				windX * 4, 0.04, windZ * 4);
			count();
		}
	}

	private static void emit(ClientLevel level, RandomSource random, BlockPos pos, FireWorld.Kind kind) {
		float density = WaterSettings.fireParticleDensity;
		float intensity = kind.intensity;
		boolean soul = kind.soul();
		boolean rained = level.isRainingAt(pos.above());
		boolean wet = WaterSettings.fireSteam && nextToWater(level, pos);
		// Flames: wide and low for open fire, a thin tip for torches and candles. Rain beats them down.
		float spread = 0.15F + 0.3F * intensity;
		double baseY = pos.getY() + (intensity >= 0.8F ? 0.15 : 0.65);
		float flameChance = Math.min(1.0F, (0.35F + 1.2F * intensity) * density * (rained ? 0.5F : 1.0F));
		ParticleOptions flame = soul ? ParticleTypes.SOUL_FIRE_FLAME : intensity < 0.5F ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME;
		int flames = (int) flameChance + (random.nextFloat() < flameChance % 1.0F ? 1 : 0);
		for (int i = 0; i < Math.max(flames, 1) && random.nextFloat() < Math.min(1.0F, flameChance + 0.2F); i++) {
			double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * spread * 2.0;
			double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * spread * 2.0;
			double y = baseY + random.nextDouble() * (0.2 + 0.5 * intensity);
			level.addParticle(flame, x, y, z, windX * 1.5, 0.01 + 0.03 * intensity, windZ * 1.5);
			count();
		}
		// Smoke: rises faster and farther the bigger the fire, carried by the wind.
		float smokeChance = (0.1F + 0.5F * intensity) * density * (rained ? 1.5F : 1.0F);
		if (random.nextFloat() < smokeChance) {
			double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * spread;
			double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * spread;
			double y = baseY + 0.3 + 0.4 * intensity;
			double rise = 0.03 + 0.05 * intensity;
			ParticleOptions smoke = intensity >= 0.8F ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE;
			if (kind == FireWorld.Kind.REALISTIC_CAMPFIRE || kind == FireWorld.Kind.REALISTIC_SOUL_CAMPFIRE) {
				smoke = ParticleTypes.CAMPFIRE_COSY_SMOKE;
				rise = 0.07;
			}
			level.addParticle(smoke, x, y, z, windX * 4, rise, windZ * 4);
			count();
		}
		// Steam: a fire touching water (or rain pouring on it) hisses white clouds.
		if ((wet || rained) && random.nextFloat() < (wet ? 0.5F : 0.15F) * density) {
			level.addParticle(ParticleTypes.CLOUD, pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.6, pos.getY() + 0.5 + random.nextDouble() * 0.5,
				pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.6, windX * 4, 0.05 + 0.03 * intensity, windZ * 4);
			count();
			steamSpawned++;
		}
	}
}
