package me.skaffy.block;

import me.skaffy.ModContent;
import me.skaffy.mixin.FireBlockAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoulFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Fire that behaves exactly like vanilla fire (spreading, burning out, igniting, damaging) but has no vanilla
 * model or particles. The client fire renderer draws it instead.
 */
public class RealisticFireBlock extends FireBlock {
	public RealisticFireBlock(Properties properties) {
		super(properties);
	}

	public static boolean isRealistic(BlockState state) {
		return state.getBlock() instanceof RealisticFireBlock;
	}

	/** State to place at {@code pos}, with the same support and neighbour-fire rules as vanilla fire. */
	public static BlockState stateFor(BlockGetter level, BlockPos pos) {
		return ((RealisticFireBlock) ModContent.REALISTIC_FIRE).getStateForPlacement(level, pos);
	}

	/** Realistic soul fire over soul sand/soil, otherwise realistic fire, mirroring vanilla's choice. */
	public static BlockState placementState(BlockGetter level, BlockPos pos) {
		if (SoulFireBlock.canSurviveOnBlock(level.getBlockState(pos.below()))) {
			return ModContent.REALISTIC_SOUL_FIRE.defaultBlockState();
		}
		return stateFor(level, pos);
	}

	/** Same age and flame sides as a vanilla fire state, but as realistic fire. */
	public static BlockState fromVanilla(BlockState vanilla) {
		BlockState out = ModContent.REALISTIC_FIRE.defaultBlockState().setValue(AGE, vanilla.getValue(AGE));
		for (BooleanProperty side : new BooleanProperty[]{NORTH, EAST, SOUTH, WEST, UP}) {
			out = out.setValue(side, vanilla.getValue(side));
		}
		return out;
	}

	/**
	 * Every FireBlock instance keeps its own flammability tables, and only vanilla fire gets them filled in.
	 * Copy them so realistic fire spreads and burns the same blocks, including ones other mods registered.
	 */
	public static void syncFlammability() {
		FireBlockAccessor vanilla = (FireBlockAccessor) Blocks.FIRE;
		FireBlockAccessor ours = (FireBlockAccessor) ModContent.REALISTIC_FIRE;
		ours.realisticWater$igniteOdds().clear();
		ours.realisticWater$igniteOdds().putAll(vanilla.realisticWater$igniteOdds());
		ours.realisticWater$igniteOdds().put(ModContent.REALISTIC_LEAVES, 30);
		ours.realisticWater$burnOdds().clear();
		ours.realisticWater$burnOdds().putAll(vanilla.realisticWater$burnOdds());
		ours.realisticWater$burnOdds().put(ModContent.REALISTIC_LEAVES, 60);
	}

	/** Slowly changing breeze, stronger when it rains or storms; x and z are in roughly -1.5 to 1.5. Shared by fire spread. */
	public static double[] wind(Level level) {
		double t = level.getGameTime() * 0.01;
		double strength = 0.6 + 0.6 * level.getRainLevel(1.0F) + 0.5 * level.getThunderLevel(1.0F);
		return new double[]{
			(Math.sin(t) + 0.5 * Math.sin(t * 2.3 + 1.0)) * strength,
			(Math.cos(t * 0.7) + 0.5 * Math.sin(t * 1.9 + 2.0)) * strength
		};
	}

	/**
	 * A fire that burns itself out chars the ground beneath it and, less and less likely the further away, the ground around
	 * it, so a dead blaze leaves a ragged scorched patch instead of a neat dot. A hotter blaze (more fire around it) reaches
	 * further. Only open ground is charred, never grass under a roof or another block.
	 */
	private static void scorch(ServerLevel level, BlockPos pos, RandomSource random, int near) {
		double reach = 1.0 + 0.1 * Math.min(near, 8);
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				double chance = Math.min(0.9, 0.6 * reach / (1.0 + 1.3 * (dx * dx + dz * dz)));
				if (random.nextDouble() >= chance) {
					continue;
				}
				// Follow the ground up or down a step, so it works on slopes.
				for (int dy = 0; dy >= -1; dy--) {
					BlockPos ground = pos.offset(dx, dy - 1, dz);
					BlockState state = level.getBlockState(ground);
					if (!state.is(Blocks.GRASS_BLOCK) && !state.is(Blocks.PODZOL) && !state.is(Blocks.MYCELIUM)) {
						continue;
					}
					BlockPos above = ground.above();
					if (level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
						level.setBlock(ground, Blocks.COARSE_DIRT.defaultBlockState(), 3);
					}
					break;
				}
			}
		}
	}

	private static BlockState young(BlockState fire, RandomSource random) {
		return fire.hasProperty(AGE) ? fire.setValue(AGE, random.nextInt(3)) : fire;
	}

	private int burnOdds(BlockState state) {
		return ((FireBlockAccessor) this).realisticWater$burnOdds().getInt(state.getBlock());
	}

	private int igniteOdds(BlockState state) {
		return ((FireBlockAccessor) this).realisticWater$igniteOdds().getInt(state.getBlock());
	}

	/**
	 * Fire that behaves like a real one. It burns the fuel touching it a little at a time, so a log smoulders while dry
	 * leaves flash over; with nothing to burn it dies down. Heat rises, so it climbs far faster than it spreads sideways or
	 * down, the wind pushes it downwind, and every flame nearby makes it hotter, so a blaze grows and feeds itself.
	 */
	@Override
	protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
		level.scheduleTick(pos, this, 6 + random.nextInt(8));
		if (!state.canSurvive(level, pos)) {
			level.removeBlock(pos, false);
			return;
		}
		int age = state.getValue(AGE);
		boolean eternal = level.getBlockState(pos.below()).is(level.dimensionType().infiniburn());
		// Water touching the fire (any side, edge or corner, or the cell itself) puts it out, except fire fed by netherrack and the like.
		if (!eternal) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (level.getFluidState(pos.offset(dx, dy, dz)).is(net.minecraft.tags.FluidTags.WATER)) {
							level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 2.0F);
							level.removeBlock(pos, false);
							return;
						}
					}
				}
			}
		}
		if (level.isRainingAt(pos) && random.nextFloat() < 0.12F + age * 0.015F) {
			level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 2.0F);
			level.removeBlock(pos, false);
			return;
		}

		// Heat from the fires around it, and the fuel it touches.
		int near = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if ((dx != 0 || dy != 0 || dz != 0) && level.getBlockState(pos.offset(dx, dy, dz)).getBlock() instanceof FireBlock) {
						near++;
					}
				}
			}
		}
		double heat = 1.0 + 0.3 * near + 0.08 * age;
		boolean fuelled = false;
		double[] wind = wind(level);
		for (Direction d : Direction.values()) {
			BlockPos np = pos.relative(d);
			BlockState ns = level.getBlockState(np);
			int burn = burnOdds(ns);
			if (burn <= 0) {
				continue;
			}
			fuelled = true;
			double bias = d == Direction.UP ? 1.8 : d == Direction.DOWN ? 0.5 : Mth.clamp(1.0 + 0.5 * (wind[0] * d.getStepX() + wind[1] * d.getStepZ()), 0.4, 2.5);
			double chance = Math.min(0.9, burn / 60.0 * 0.12 * heat * bias);
			// Heat soaks into the fuel a bit each tick; it only catches once it has soaked up enough, so the fire creeps.
			if (FireHeat.feed(level, np, chance * (0.5 + random.nextDouble()))) {
				if (ns.is(Blocks.TNT)) {
					TntBlock.prime(level, np);
					level.removeBlock(np, false);
				} else {
					BlockState fire = placementState(level, np);
					level.setBlock(np, fire.canSurvive(level, np) ? young(fire, random) : Blocks.AIR.defaultBlockState(), 3);
				}
			}
		}

		// Age and die down: fuelled fire stays lively, fire with nothing to burn burns out.
		if (fuelled) {
			if (age < 6 && random.nextFloat() < 0.4F) {
				level.setBlock(pos, state.setValue(AGE, age + 1), 3);
			}
		} else if (!eternal) {
			int next = age + 1 + random.nextInt(2);
			if (next >= 15 || age >= 4 && random.nextFloat() < 0.18F) {
				scorch(level, pos, random, near);
				level.removeBlock(pos, false);
				return;
			}
			level.setBlock(pos, state.setValue(AGE, next), 3);
		}

		// Spread into air next to fuel: mostly up, some across, a little down, leaning downwind and with more heat more often.
		if (age < 1 || random.nextFloat() < 0.25F) {
			return;
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 4; dy++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;
					}
					BlockPos cell = pos.offset(dx, dy, dz);
					if (!level.isEmptyBlock(cell)) {
						continue;
					}
					int ignite = 0;
					for (Direction d : Direction.values()) {
						ignite = Math.max(ignite, igniteOdds(level.getBlockState(cell.relative(d))));
					}
					if (ignite <= 0) {
						continue;
					}
					double distSq = dx * dx + dy * dy + dz * dz;
					double rise = dy > 0 ? 1.0 + 0.7 * dy : dy < 0 ? 0.3 : 1.0;
					double len = Math.sqrt(dx * dx + dz * dz);
					double drift = len < 1.0E-3 ? 1.0 : Mth.clamp(1.0 + 0.6 * (wind[0] * dx + wind[1] * dz) / len, 0.3, 3.0);
					double chance = ignite / 100.0 * 0.05 / (1.0 + 0.8 * distSq) * rise * drift * heat;
					BlockState fire = placementState(level, cell);
					if (fire.canSurvive(level, cell) && FireHeat.feed(level, cell, chance * (0.5 + random.nextDouble()))) {
						level.setBlock(cell, young(fire, random), 3);
					}
				}
			}
		}
	}

	@Override
	protected RenderShape getRenderShape(final BlockState state) {
		return RenderShape.INVISIBLE;
	}

	@Override
	public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random) {
		if (random.nextInt(24) == 0) {
			level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS,
				1.0F + random.nextFloat(), random.nextFloat() * 0.7F + 0.3F, false);
		}
	}
}
