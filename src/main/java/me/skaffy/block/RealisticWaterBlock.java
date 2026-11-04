package me.skaffy.block;

import me.skaffy.ModContent;
import me.skaffy.ocean.OceanOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FlowingFluid;
import org.jspecify.annotations.Nullable;

public class RealisticWaterBlock extends LiquidBlock {
	public static final BooleanProperty INFINITE = BooleanProperty.create("infinite");
	public static final EnumProperty<OceanOrigin> OCEAN = EnumProperty.create("ocean", OceanOrigin.class);

	public RealisticWaterBlock(FlowingFluid fluid, Properties properties) {
		super(fluid, properties);
		this.registerDefaultState(this.defaultBlockState().setValue(INFINITE, false).setValue(OCEAN, OceanOrigin.NONE));
	}

	public static boolean isInfinite(BlockState state) {
		return state.getBlock() instanceof RealisticWaterBlock && state.getValue(INFINITE);
	}

	public static boolean isOcean(BlockState state) {
		return state.getBlock() == ModContent.REALISTIC_WATER_BLOCK && state.getValue(OCEAN) != OceanOrigin.NONE;
	}

	public static OceanOrigin origin(BlockState state) {
		return state.getBlock() == ModContent.REALISTIC_WATER_BLOCK ? state.getValue(OCEAN) : OceanOrigin.NONE;
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		super.createBlockStateDefinition(builder);
		builder.add(INFINITE, OCEAN);
	}

	@Override
	public ItemStack pickupBlock(final @Nullable LivingEntity user, final LevelAccessor level, final BlockPos pos, final BlockState state) {
		if (isOcean(state)) {
			if (state.getValue(LEVEL) != 0) {
				return ItemStack.EMPTY;
			}
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
			return new ItemStack(Items.WATER_BUCKET);
		}
		if (this == ModContent.REALISTIC_WATER_BLOCK && state.getValue(LEVEL) == 0 && !state.getValue(INFINITE)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
			return new ItemStack(ModContent.SMALL_REALISTIC_WATER_BUCKET);
		}
		return super.pickupBlock(user, level, pos, state);
	}

	@Override
	protected boolean propagatesSkylightDown(final BlockState state) {
		return state.getValue(OCEAN) != OceanOrigin.WATER;
	}
}
