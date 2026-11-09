package me.skaffy.client.fire;

import me.skaffy.ModContent;
import me.skaffy.client.water.Material;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterWorld;
import me.skaffy.fluid.RealisticLavaFluid;
import me.skaffy.mixin.FireBlockAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * Brings every active realistic fire source in {@link FireWorld} to life. With the Vulkan renderer up, flames, smoke and
 * sparks are puffed into the same GPU particle simulation that steam uses (flames glow by themselves, smoke is lit by the
 * world), and fires touching water or standing in rain feed the existing steam. Without Vulkan it falls back to plain
 * vanilla particles so something is still visible. Wind pushes everything the same way in both.
 */
public final class FireEffects {
	private static int spawnedLastTick;
	private static long totalSpawned;
	private static long steamSpawned;
	private static long emberSpawned;
	private static long gpuPuffs;
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

	public static long gpuPuffs() {
		return gpuPuffs;
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
		boolean gpu = WaterWorld.get().gpuReady();
		RandomSource random = level.getRandom();
		Vec3 eye = mc.player.getEyePosition();
		double rangeSq = (double) WaterSettings.fireParticleRange * WaterSettings.fireParticleRange;
		int budget = WaterSettings.fireParticleBudget;
		long tick = level.getGameTime();
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
					if (gpu) {
						gpuLava(level, random, pos);
					} else {
						embers(level, random, pos);
					}
				}
			} else if (kind.name().startsWith("REALISTIC_")) {
				if (gpu) {
					gpuFire(level, random, pos, kind, tick);
				} else {
					emit(level, random, pos, kind);
				}
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

	// ---- GPU path -------------------------------------------------------------------------------------------------

	private static void gpuFire(ClientLevel level, RandomSource random, BlockPos pos, FireWorld.Kind kind, long tick) {
		float i = kind.intensity;
		if (kind == FireWorld.Kind.REALISTIC_REDSTONE_TORCH) {
			return;
		}
		WaterWorld water = WaterWorld.get();
		boolean soul = kind.soul();
		boolean stove = kind == FireWorld.Kind.REALISTIC_FURNACE || kind == FireWorld.Kind.REALISTIC_SMOKER;
		boolean rained = level.isRainingAt(pos.above());
		double x = pos.getX() + 0.5;
		double z = pos.getZ() + 0.5;
		double baseY = pos.getY() + (i >= 0.8F ? 0.05 : kind == FireWorld.Kind.REALISTIC_CANDLE ? 0.45 : stove ? 0.2 : 0.62);
		if (stove) {
			// A lit furnace glows through its front opening.
			BlockState state = level.getBlockState(pos);
			Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
			x += facing.getStepX() * 0.55;
			z += facing.getStepZ() * 0.55;
		}
		int stagger = (pos.getX() * 31 + pos.getZ() * 17 + pos.getY()) & 0xFFFF;
		float wx = (float) windX * 60.0F;
		float wz = (float) windZ * 60.0F;
		// How big the blaze is: every open flame within two blocks adds to it, so one flame is a flicker, a burning
		// tree is a roaring column. Level 0 is a lone flame and 6 is a full inferno.
		int neighbours = i >= 0.8F ? FireWorld.get().neighbourFlames(pos) : 0;
		int level6 = neighbours == 0 ? 0 : Math.min(6, (int) (Math.log(1 + neighbours) / Math.log(2.0) * 1.4));
		float blaze = 1.0F + 0.45F * level6;
		float step = WaterSettings.fireParticleStep * (0.55F + 0.45F * i) * (1.0F + 0.2F * level6)
			/ (float) Math.cbrt(Math.max(WaterSettings.fireFlameAmount * WaterSettings.fireParticleDensity, 0.05F));
		step = Math.max(step, 0.04F);

		// Flames: a cloud of glowing particles every tick that rises, tapers and cools from yellow to red. Bigger blazes
		// start wider and taller, with larger and longer-lived particles that reach higher and burn brighter.
		boolean flameNow = i >= 0.8F ? (tick + stagger) % 2 == 0 || level6 > 0 : true;
		if (flameNow && !(rained && random.nextFloat() < 0.5F)) {
			Material flameMaterial = soul ? Material.SOUL_FLAME : Material.FLAME;
			float rise = 0.6F + 1.4F * i + 0.5F * level6;
			int faces = kind == FireWorld.Kind.REALISTIC_FIRE ? burningFaces(level, pos) : 0;
			if (faces == 0) {
				// Nothing to cling to: a flame standing on its own.
				double half = Math.min(0.5, (0.03 + 0.15 * i) * blaze);
				double height = (0.05 + 0.1 * i) * blaze;
				water.addGasPuff(flameMaterial, x, baseY, z, half, height, wx * 0.5F, rise, wz * 0.5F, level6 + step);
				gpuPuffs++;
				count();
			} else {
				// Fire clings to the faces of the blocks it is eating: a sheet of flame against each burning wall, and a
				// low carpet over a burning floor, instead of a column hanging in the air.
				double wall = 0.06;
				double span = 0.46;
				double height = Math.min(1.0, 0.35 + 0.12 * level6);
				float wallStep = level6 + Math.max(step * 1.1F, 0.05F);
				double cx = pos.getX() + 0.5;
				double cz = pos.getZ() + 0.5;
				for (Direction d : Direction.Plane.HORIZONTAL) {
					if ((faces & (1 << d.get2DDataValue())) == 0) {
						continue;
					}
					double fx = cx + d.getStepX() * (0.5 - wall);
					double fz = cz + d.getStepZ() * (0.5 - wall);
					double halfX = d.getStepX() != 0 ? wall : span;
					double halfZ = d.getStepZ() != 0 ? wall : span;
					water.addGasPuff(flameMaterial, fx, pos.getY() + 0.02, fz, halfX, halfZ, height,
						-d.getStepX() * 0.12F + wx * 0.2F, rise, -d.getStepZ() * 0.12F + wz * 0.2F, wallStep);
					gpuPuffs++;
					count();
				}
				if ((faces & 16) != 0) {
					water.addGasPuff(flameMaterial, cx, pos.getY() + 0.02, cz, span, span, 0.12 + 0.05 * level6, wx * 0.3F, rise * 0.6F, wz * 0.3F, wallStep);
					gpuPuffs++;
					count();
				}
			}
		}

		// Smoke: a dark, lit plume that expands as it rises; it thickens and climbs with the blaze. Soul fire barely smokes.
		int smokeInterval = i >= 0.8F ? 3 : i >= 0.25F ? 5 : 9;
		float smokeChance = WaterSettings.fireSmokeAmount * (soul ? 0.3F : 1.0F) * (rained ? 1.5F : 1.0F) * (1.0F + 0.25F * level6);
		if ((tick + stagger) % smokeInterval == 0 && random.nextFloat() < smokeChance && kind != FireWorld.Kind.REALISTIC_CANDLE) {
			double half = Math.min(0.5, (0.03 + 0.1 * i) * blaze);
			water.addGasPuff(Material.SMOKE, x, baseY + (0.25 + 0.45 * i) * blaze, z, half, 0.1,
				wx, 0.4F + 0.8F * i + 0.3F * level6, wz, (level6 / 2) + Math.max(step, 0.1F));
			gpuPuffs++;
			count();
		}

		// Embers: glowing specks the fire throws up and the wind carries off into the dark. A blaze throws far more.
		float sparkChance = WaterSettings.fireSparks * (0.3F + 0.45F * i + 0.35F * level6) * (soul ? 0.0F : 1.0F) * (rained ? 0.3F : 1.0F);
		if ((tick + stagger) % 3 == 0 && random.nextFloat() < sparkChance && kind != FireWorld.Kind.REALISTIC_CANDLE) {
			water.addGasPuff(Material.SPARK, x, baseY + 0.15 * blaze, z, 0.05 * blaze, 0.08 * blaze,
				wx * 0.8F, 1.0F + 0.6F * i + 0.4F * level6, wz * 0.8F, 0.08F);
			emberSpawned++;
			gpuPuffs++;
			count();
		}

		// Steam: a fire touching water, or rain pouring on it, feeds the mod's existing steam.
		boolean wet = WaterSettings.fireSteam && nextToWater(level, pos);
		if ((wet || rained) && random.nextFloat() < (wet ? 0.12F : 0.03F)) {
			water.addSteam(x, pos.getY() + 0.5, z, 0.35F + 0.4F * i);
			steamSpawned++;
			count();
		}
	}

	/**
	 * Which faces of the cell hold something that burns: bits 0-3 are the horizontal sides (by their 2D data value) and bit 4
	 * is the floor. Fire clings to those, so a burning wall gets a sheet of flame up it and a burning floor a low carpet.
	 */
	private static int burningFaces(ClientLevel level, BlockPos pos) {
		var burn = ((FireBlockAccessor) Blocks.FIRE).realisticWater$burnOdds();
		int mask = 0;
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockState n = level.getBlockState(pos.relative(d));
			if (burn.getInt(n.getBlock()) > 0 || n.is(ModContent.REALISTIC_LEAVES)) {
				mask |= 1 << d.get2DDataValue();
			}
		}
		BlockState below = level.getBlockState(pos.below());
		if (burn.getInt(below.getBlock()) > 0 || below.is(ModContent.REALISTIC_LEAVES)) {
			mask |= 16;
		}
		return mask;
	}

	private static void gpuLava(ClientLevel level, RandomSource random, BlockPos pos) {
		WaterWorld water = WaterWorld.get();
		float sparks = WaterSettings.fireSparks;
		if (random.nextFloat() < 0.05F * sparks) {
			double x = pos.getX() + random.nextDouble();
			double z = pos.getZ() + random.nextDouble();
			water.addGasPuff(Material.SPARK, x, pos.getY() + 0.95, z, 0.03, 0.04,
				(float) windX * 60.0F + (random.nextFloat() - 0.5F) * 0.6F, 1.2F + random.nextFloat() * 1.4F, (float) windZ * 60.0F + (random.nextFloat() - 0.5F) * 0.6F,
				0.06F);
			emberSpawned++;
			gpuPuffs++;
			count();
		}
		if (random.nextFloat() < 0.012F * WaterSettings.fireSmokeAmount) {
			water.addGasPuff(Material.SMOKE, pos.getX() + random.nextDouble(), pos.getY() + 1.0, pos.getZ() + random.nextDouble(), 0.05, 0.1,
				(float) windX * 80.0F, 0.5F, (float) windZ * 80.0F, 0.1F);
			gpuPuffs++;
			count();
		}
	}

	// ---- Fallback without Vulkan ----------------------------------------------------------------------------------

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
		if ((wet || rained) && random.nextFloat() < (wet ? 0.5F : 0.15F) * density) {
			level.addParticle(ParticleTypes.CLOUD, pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.6, pos.getY() + 0.5 + random.nextDouble() * 0.5,
				pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.6, windX * 4, 0.05 + 0.03 * intensity, windZ * 4);
			count();
			steamSpawned++;
		}
	}
}
