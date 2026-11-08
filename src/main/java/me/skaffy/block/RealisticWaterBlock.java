package me.skaffy.block;

import me.skaffy.ModContent;
import me.skaffy.ocean.OceanOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
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
	public static final BooleanProperty FROZEN = BooleanProperty.create("frozen");
	private static final VoxelShape[] ICE_SHAPES = new VoxelShape[9];

	static {
		for (int i = 1; i <= 8; i++) {
			ICE_SHAPES[i] = Block.box(0.0, 0.0, 0.0, 16.0, i * 2.0, 16.0);
		}
	}

	public RealisticWaterBlock(FlowingFluid fluid, Properties properties) {
		super(fluid, properties);
		this.registerDefaultState(this.defaultBlockState().setValue(INFINITE, false).setValue(OCEAN, OceanOrigin.NONE).setValue(FROZEN, false));
	}

	public static boolean isFrozen(BlockState state) {
		return state.getBlock() == ModContent.REALISTIC_WATER_BLOCK && state.getValue(FROZEN);
	}

	public static int eighths(BlockState state) {
		int level = state.getValue(LEVEL);
		return level == 0 ? 8 : Math.max(8 - level, 1);
	}

	@Override
	protected FluidState getFluidState(final BlockState state) {
		return state.getValue(FROZEN) ? Fluids.EMPTY.defaultFluidState() : super.getFluidState(state);
	}

	@Override
	protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		return state.getValue(FROZEN) ? ICE_SHAPES[eighths(state)] : super.getCollisionShape(state, level, pos, context);
	}

	@Override
	protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		return state.getValue(FROZEN) ? ICE_SHAPES[eighths(state)] : super.getShape(state, level, pos, context);
	}

	@Override
	protected boolean canBeReplaced(final BlockState state, final BlockPlaceContext context) {
		return !state.getValue(FROZEN) && super.canBeReplaced(state, context);
	}

	@Override
	protected boolean canBeReplaced(final BlockState state, final Fluid fluid) {
		return !state.getValue(FROZEN) && super.canBeReplaced(state, fluid);
	}

	@Override
	protected float getDestroyProgress(final BlockState state, final Player player, final BlockGetter level, final BlockPos pos) {
		return state.getValue(FROZEN) ? Blocks.ICE.defaultBlockState().getDestroyProgress(player, level, pos) : super.getDestroyProgress(state, player, level, pos);
	}

	@Override
	protected boolean isPathfindable(final BlockState state, final net.minecraft.world.level.pathfinder.PathComputationType type) {
		return !state.getValue(FROZEN) && super.isPathfindable(state, type);
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
		builder.add(INFINITE, OCEAN, FROZEN);
	}

	@Override
	public ItemStack pickupBlock(final @Nullable LivingEntity user, final LevelAccessor level, final BlockPos pos, final BlockState state) {
		if (state.getValue(FROZEN)) {
			return ItemStack.EMPTY;
		}
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
