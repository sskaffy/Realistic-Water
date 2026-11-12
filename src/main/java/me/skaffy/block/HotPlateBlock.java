package me.skaffy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

/** A block that glows and heats whatever touches it (water especially) for as long as it gets a redstone signal. */
public class HotPlateBlock extends Block {
	public static final BooleanProperty LIT = BlockStateProperties.LIT;

	public HotPlateBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(LIT, false));
	}

	public static boolean isHot(BlockState state) {
		return state.getBlock() instanceof HotPlateBlock && state.getValue(LIT);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(LIT, context.getLevel().hasNeighborSignal(context.getClickedPos()));
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
		@Nullable Orientation orientation, boolean movedByPiston) {
		if (level.isClientSide()) {
			return;
		}
		boolean powered = level.hasNeighborSignal(pos);
		if (powered != state.getValue(LIT)) {
			level.setBlock(pos, state.setValue(LIT, powered), Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LIT);
	}
}
