package me.skaffy.ocean;

import me.skaffy.ModContent;
import me.skaffy.block.RealisticWaterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public final class OceanWaves {
	public static final int WAVES = 8;
	public static final int GEOMETRIC = 5;
	public static final double GRAVITY = 9.81;
	public static final float AMP_MAX = 1.5F;
	public static final float REST_HEIGHT = 8.0F / 9.0F;
	public static final int SHORE_REACH = 3;
	private static final double[] LENGTH = {26.0, 17.0, 11.5, 7.7, 5.1, 3.6, 2.4, 1.6};
	private static final double[] ANGLE = {0.0, 0.45, -0.35, 0.8, -0.7, 1.2, -1.1, 0.25};
	private static final double[] STEEPNESS = {0.045, 0.05, 0.05, 0.05, 0.045, 0.04, 0.035, 0.03};
	private static final double[] SEED = {0.0, 2.1, 4.7, 1.3, 5.9, 3.3, 0.8, 4.1};
	private static final double TAU = Math.PI * 2.0;

	private OceanWaves() {
	}

	public record Waves(double[] kx, double[] kz, double[] omega, double[] amp, double time) {
		public double phase(int i, double x, double z) {
			return this.kx[i] * x + this.kz[i] * z - this.omega[i] * this.time + SEED[i];
		}

		public double height(double x, double z) {
			double h = 0.0;
			for (int i = 0; i < GEOMETRIC; i++) {
				h += this.amp[i] * Math.sin(this.phase(i, x, z));
			}
			return h;
		}

		public double[] velocity(double x, double z, double depth) {
			double[] u = new double[3];
			for (int i = 0; i < GEOMETRIC; i++) {
				double k = Math.hypot(this.kx[i], this.kz[i]);
				double phase = this.phase(i, x, z);
				double e = this.amp[i] * this.omega[i] * Math.exp(k * Math.min(depth, 0.0));
				u[0] += this.kx[i] / k * e * Math.sin(phase);
				u[2] += this.kz[i] / k * e * Math.sin(phase);
				u[1] -= e * Math.cos(phase);
			}
			return u;
		}

		public float[] phases(double originX, double originZ) {
			float[] out = new float[WAVES];
			for (int i = 0; i < WAVES; i++) {
				double p = this.phase(i, originX, originZ) % TAU;
				out[i] = (float) (p < 0.0 ? p + TAU : p);
			}
			return out;
		}
	}

	public static Waves at(long gameTime, float partialTick, float strength) {
		double time = (gameTime + partialTick) / 20.0;
		double wind = 0.6 + 0.9 * Math.sin(TAU * gameTime / (24000.0 * 5.0));
		double[] kx = new double[WAVES];
		double[] kz = new double[WAVES];
		double[] omega = new double[WAVES];
		double[] amp = new double[WAVES];
		for (int i = 0; i < WAVES; i++) {
			double k = TAU / LENGTH[i];
			double dir = wind + ANGLE[i];
			kx[i] = k * Math.cos(dir);
			kz[i] = k * Math.sin(dir);
			omega[i] = Math.sqrt(GRAVITY * k);
			amp[i] = STEEPNESS[i] * LENGTH[i] / TAU * strength;
		}
		return new Waves(kx, kz, omega, amp, time);
	}

	public static float weather(Level level) {
		return 1.0F + 0.6F * level.getRainLevel(1.0F) + 0.9F * level.getThunderLevel(1.0F);
	}

	public static boolean isRestWater(BlockState state) {
		if (state.getBlock() == ModContent.REALISTIC_WATER_BLOCK) {
			return state.getValue(RealisticWaterBlock.OCEAN) == OceanOrigin.WATER;
		}
		FluidState fluid = state.getFluidState();
		return fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER;
	}

	public static boolean isRestAir(BlockState state) {
		return state.isAir() || state.getBlock() == ModContent.REALISTIC_WATER_BLOCK && state.getValue(RealisticWaterBlock.OCEAN) == OceanOrigin.AIR;
	}

	public static boolean isWaveSurface(BlockState state, BlockState above) {
		if (!isRestAir(above)) {
			return false;
		}
		if (state.getBlock() == ModContent.REALISTIC_WATER_BLOCK) {
			return state.getValue(RealisticWaterBlock.OCEAN) == OceanOrigin.WATER;
		}
		return state.getBlock() == Blocks.WATER && state.getFluidState().isSource();
	}

	public static BlockState oceanState(int amount, OceanOrigin origin) {
		return ModContent.REALISTIC_WATER_BLOCK.defaultBlockState()
			.setValue(RealisticWaterBlock.INFINITE, false)
			.setValue(LiquidBlock.LEVEL, amount >= 8 ? 0 : 8 - Math.max(1, amount))
			.setValue(RealisticWaterBlock.OCEAN, origin);
	}

	public static BlockState restState(BlockState state) {
		return RealisticWaterBlock.origin(state) == OceanOrigin.WATER ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
	}

	public static float biomeFactor(Holder<Biome> biome) {
		if (biome.is(BiomeTags.IS_DEEP_OCEAN)) {
			return 1.15F;
		}
		if (biome.is(BiomeTags.IS_OCEAN)) {
			return 1.0F;
		}
		if (biome.is(BiomeTags.IS_BEACH)) {
			return 0.7F;
		}
		if (biome.is(BiomeTags.IS_RIVER)) {
			return 0.15F;
		}
		return 0.25F;
	}

	@FunctionalInterface
	public interface States {
		BlockState get(int x, int y, int z);
	}

	public static States of(BlockGetter level) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		return (x, y, z) -> level.getBlockState(pos.set(x, y, z));
	}

	public static float columnAmplitude(States level, int x, int y, int z, float biome) {
		double nearest = SHORE_REACH + 1.0;
		for (int dz = -SHORE_REACH; dz <= SHORE_REACH; dz++) {
			for (int dx = -SHORE_REACH; dx <= SHORE_REACH; dx++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				double d = Math.sqrt(dx * dx + dz * dz);
				if (d >= nearest) {
					continue;
				}
				if (!isRestWater(level.get(x + dx, y, z + dz)) || !isRestAir(level.get(x + dx, y + 1, z + dz))) {
					nearest = d;
				}
			}
		}
		float fade = (float) Math.clamp((nearest - 1.0) / SHORE_REACH, 0.0, 1.0);
		return biome * fade * fade * (3.0F - 2.0F * fade);
	}
}
