package me.skaffy.block;

import me.skaffy.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class RealisticSnowBlock extends Block {
	public static final int MAX_LAYERS = 8;
	public static final IntegerProperty LAYERS = BlockStateProperties.LAYERS;
	public static final EnumProperty<SnowOrigin> ORIGIN = EnumProperty.create("origin", SnowOrigin.class);
	public static final BooleanProperty PACKED = BooleanProperty.create("packed");
	public static final float FIRM_SHARE = 0.45F;
	private static final VoxelShape[] SHAPES = Block.boxes(MAX_LAYERS, height -> Block.column(16.0, 0.0, height * 2));
	private static final VoxelShape[] FIRM = new VoxelShape[MAX_LAYERS + 1];

	static {
		for (int layers = 0; layers <= MAX_LAYERS; layers++) {
			double height = Math.floor(layers * 2 * FIRM_SHARE);
			FIRM[layers] = height <= 0.0 ? Shapes.empty() : Block.column(16.0, 0.0, height);
		}
	}

	public RealisticSnowBlock(Properties properties) {
		super(properties);
		this.registerDefaultState(this.stateDefinition.any().setValue(LAYERS, 1).setValue(ORIGIN, SnowOrigin.PLACED).setValue(PACKED, false));
	}

	public static int layers(BlockState state) {
		return state.getBlock() instanceof RealisticSnowBlock ? state.getValue(LAYERS) : 0;
	}

	public static float speedFactor(BlockState state, BlockGetter level, BlockPos pos) {
		return (float) (1.0 - 0.5 * sinkDepth(state, level, pos));
	}

	public static double sinkDepth(BlockState state, BlockGetter level, BlockPos pos) {
		if (!(state.getBlock() instanceof RealisticSnowBlock) || state.getValue(PACKED)) {
			return 0.0;
		}
		int layers = state.getValue(LAYERS);
		if (layers == MAX_LAYERS && level.getBlockState(pos.above()).getBlock() instanceof RealisticSnowBlock) {
			return 0.0;
		}
		return layers / 8.0 - firmHeight(layers);
	}

	@Override
	public void fallOn(final net.minecraft.world.level.Level level, final BlockState state, final BlockPos pos,
		final net.minecraft.world.entity.Entity entity, final double fallDistance) {
		float cushion = (float) Math.min(1.2 * sinkDepth(state, level, pos), 0.75);
		entity.causeFallDamage(fallDistance, 1.0F - cushion, entity.damageSources().fall());
	}

	private static boolean restsOnTop(final CollisionContext context) {
		if (context.isPlacement() || !(context instanceof net.minecraft.world.phys.shapes.EntityCollisionContext entityContext)) {
			return false;
		}
		net.minecraft.world.entity.Entity entity = entityContext.getEntity();
		return entity instanceof net.minecraft.world.entity.item.ItemEntity || entity instanceof net.minecraft.world.entity.ExperienceOrb;
	}

	@Override
	protected void onExplosionHit(final BlockState state, final net.minecraft.server.level.ServerLevel level, final BlockPos pos,
		final net.minecraft.world.level.Explosion explosion, final java.util.function.BiConsumer<net.minecraft.world.item.ItemStack, BlockPos> onHit) {
		if (explosion.getBlockInteraction() == net.minecraft.world.level.Explosion.BlockInteraction.TRIGGER_BLOCK) {
			return;
		}
		me.skaffy.snow.SnowBlasts.record(level, pos, state.getValue(LAYERS), explosion.center(), explosion.radius());
		level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
		this.wasExploded(level, pos, explosion);
	}

	public static @org.jspecify.annotations.Nullable BlockState snowAt(net.minecraft.world.entity.Entity entity) {
		BlockState state = entity.level().getBlockState(entity.blockPosition());
		if (state.getBlock() instanceof RealisticSnowBlock || state.is(Blocks.SNOW)) {
			return state;
		}
		return null;
	}

	public static double firmHeight(int layers) {
		return Math.floor(Math.clamp(layers, 0, MAX_LAYERS) * 2 * FIRM_SHARE) / 16.0;
	}

	@Override
	protected VoxelShape getShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		return SHAPES[state.getValue(LAYERS)];
	}

	@Override
	protected VoxelShape getCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		if (state.getValue(PACKED) || state.getValue(LAYERS) == MAX_LAYERS && level.getBlockState(pos.above()).getBlock() instanceof RealisticSnowBlock
			|| restsOnTop(context)) {
			return SHAPES[state.getValue(LAYERS)];
		}
		return FIRM[state.getValue(LAYERS)];
	}

	@Override
	protected VoxelShape getBlockSupportShape(final BlockState state, final BlockGetter level, final BlockPos pos) {
		return SHAPES[state.getValue(LAYERS)];
	}

	@Override
	protected VoxelShape getVisualShape(final BlockState state, final BlockGetter level, final BlockPos pos, final CollisionContext context) {
		return SHAPES[state.getValue(LAYERS)];
	}

	@Override
	protected boolean useShapeForLightOcclusion(final BlockState state) {
		return true;
	}

	@Override
	protected boolean isPathfindable(final BlockState state, final PathComputationType type) {
		return type == PathComputationType.LAND && state.getValue(LAYERS) < 5;
	}

	@Override
	protected boolean canSurvive(final BlockState state, final LevelReader level, final BlockPos pos) {
		BlockState below = level.getBlockState(pos.below());
		if (below.is(BlockTags.CANNOT_SUPPORT_SNOW_LAYER)) {
			return false;
		}
		if (below.is(BlockTags.SUPPORT_OVERRIDE_SNOW_LAYER)) {
			return true;
		}
		return Block.isFaceFull(below.getCollisionShape(level, pos.below()), Direction.UP)
			|| below.is(this) && below.getValue(LAYERS) == MAX_LAYERS
			|| below.is(Blocks.SNOW) && below.getValue(LAYERS) == MAX_LAYERS;
	}

	@Override
	protected BlockState updateShape(final BlockState state, final LevelReader level, final ScheduledTickAccess ticks, final BlockPos pos,
		final Direction direction, final BlockPos neighbourPos, final BlockState neighbourState, final RandomSource random) {
		return !state.canSurvive(level, pos) ? Blocks.AIR.defaultBlockState()
			: super.updateShape(state, level, ticks, pos, direction, neighbourPos, neighbourState, random);
	}

	@Override
	protected void randomTick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
		if (level.getBrightness(LightLayer.BLOCK, pos) > 11) {
			dropResources(state, level, pos);
			level.removeBlock(pos, false);
			level.gameEvent(net.minecraft.world.level.gameevent.GameEvent.BLOCK_DESTROY, pos, net.minecraft.world.level.gameevent.GameEvent.Context.of(state));
		}
	}

	@Override
	protected boolean canBeReplaced(final BlockState state, final BlockPlaceContext context) {
		int layers = state.getValue(LAYERS);
		if (context.getItemInHand().is(ModContent.REALISTIC_SNOW_LAYER_ITEM)) {
			return layers < MAX_LAYERS && (!context.replacingClickedOnBlock() || context.getClickedFace() == Direction.UP);
		}
		return layers == 1;
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(LAYERS, ORIGIN, PACKED);
	}
}
