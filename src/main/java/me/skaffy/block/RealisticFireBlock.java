package me.skaffy.block;

import me.skaffy.ModContent;
import me.skaffy.mixin.FireBlockAccessor;
import net.minecraft.core.BlockPos;
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
		ours.realisticWater$burnOdds().clear();
		ours.realisticWater$burnOdds().putAll(vanilla.realisticWater$burnOdds());
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
