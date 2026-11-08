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

public class WaterDrainBlock extends Block {
	public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
	public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

	public WaterDrainBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(OPEN, false).setValue(POWERED, false));
	}

	@Override
	public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
		return this.defaultBlockState().setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
	}

	static BlockState withSignal(BlockState state, boolean powered) {
		if (powered == state.getValue(POWERED)) {
			return state;
		}
		BlockState next = state.setValue(POWERED, powered);
		return powered ? next.cycle(OPEN) : next;
	}

	public static boolean isOpen(BlockState state) {
		return state.getBlock() instanceof WaterDrainBlock && state.getValue(OPEN);
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
		@Nullable Orientation orientation, boolean movedByPiston) {
		if (level.isClientSide()) {
			return;
		}
		BlockState next = withSignal(state, level.hasNeighborSignal(pos));
		if (next != state) {
			level.setBlock(pos, next, Block.UPDATE_CLIENTS);
		}
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(OPEN, POWERED);
	}
}
