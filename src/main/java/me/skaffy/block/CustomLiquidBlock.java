package me.skaffy.block;

import me.skaffy.ModContent;
import me.skaffy.fluid.CustomLiquidFluid;
import me.skaffy.item.CustomLiquidBucketItem;
import me.skaffy.liquid.CustomLiquid;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

public class CustomLiquidBlock extends LiquidBlock {
	public static final IntegerProperty SLOT = IntegerProperty.create("slot", 0, CustomLiquid.MAX_SLOTS - 1);

	public static final BooleanProperty INFINITE = BooleanProperty.create("infinite");

	public CustomLiquidBlock(FlowingFluid fluid, Properties properties) {
		super(fluid, properties);
		this.registerDefaultState(this.defaultBlockState().setValue(SLOT, 0).setValue(INFINITE, false));
	}

	public static int slot(BlockState state) {
		return state.getBlock() == ModContent.CUSTOM_LIQUID_BLOCK ? state.getValue(SLOT) : -1;
	}

	public static boolean isInfinite(BlockState state) {
		return state.getBlock() == ModContent.CUSTOM_LIQUID_BLOCK && state.getValue(INFINITE);
	}

	public static BlockState springState(int slot) {
		return stateFor(slot, 8).setValue(INFINITE, true);
	}

	public static int eighths(BlockState state) {
		if (state.getBlock() != ModContent.CUSTOM_LIQUID_BLOCK) {
			return 0;
		}
		int level = state.getValue(LEVEL);
		return level == 0 ? 8 : Math.max(8 - level, 1);
	}

	public static BlockState stateFor(int slot, int eighths) {
		BlockState full = ModContent.CUSTOM_LIQUID_BLOCK.defaultBlockState().setValue(SLOT, Math.min(Math.max(slot, 0), CustomLiquid.MAX_SLOTS - 1));
		return eighths >= 8 ? full : full.setValue(LEVEL, 8 - Math.max(1, eighths));
	}

	@Override
	protected FluidState getFluidState(final BlockState state) {
		return super.getFluidState(state).setValue(CustomLiquidFluid.SLOT, state.getValue(SLOT));
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(SLOT, INFINITE);
	}

	@Override
	public ItemStack pickupBlock(final @Nullable LivingEntity user, final LevelAccessor level, final BlockPos pos, final BlockState state) {
		if (state.getValue(LEVEL) != 0) {
			return ItemStack.EMPTY;
		}
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
		return CustomLiquidBucketItem.stackFor(level, state.getValue(SLOT), state.getValue(INFINITE));
	}
}
