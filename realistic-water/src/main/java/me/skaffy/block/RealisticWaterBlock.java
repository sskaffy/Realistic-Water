package me.skaffy.block;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FlowingFluid;

public class RealisticWaterBlock extends LiquidBlock {
	public static final BooleanProperty INFINITE = BooleanProperty.create("infinite");

	public RealisticWaterBlock(FlowingFluid fluid, Properties properties) {
		super(fluid, properties);
		this.registerDefaultState(this.defaultBlockState().setValue(INFINITE, false));
	}

	public static boolean isInfinite(BlockState state) {
		return state.getBlock() instanceof RealisticWaterBlock && state.getValue(INFINITE);
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(INFINITE);
	}

	@Override
	protected boolean propagatesSkylightDown(final BlockState state) {
		return true;
	}
}
