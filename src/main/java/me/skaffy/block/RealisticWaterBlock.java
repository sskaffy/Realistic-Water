package me.skaffy.block;

import me.skaffy.ModContent;
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
import net.minecraft.world.level.material.FlowingFluid;
import org.jspecify.annotations.Nullable;

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
	public ItemStack pickupBlock(final @Nullable LivingEntity user, final LevelAccessor level, final BlockPos pos, final BlockState state) {
		if (this == ModContent.REALISTIC_WATER_BLOCK && state.getValue(LEVEL) == 0 && !state.getValue(INFINITE)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
			return new ItemStack(ModContent.SMALL_REALISTIC_WATER_BUCKET);
		}
		return super.pickupBlock(user, level, pos, state);
	}

	@Override
	protected boolean propagatesSkylightDown(final BlockState state) {
		return true;
	}
}
