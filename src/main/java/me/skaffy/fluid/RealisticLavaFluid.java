package me.skaffy.fluid;

import java.util.Optional;
import me.skaffy.ModContent;
import me.skaffy.ModSettings;
import me.skaffy.block.RealisticWaterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.InsideBlockEffectType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

public abstract class RealisticLavaFluid extends FlowingFluid {
	@Override
	public Fluid getFlowing() {
		return ModContent.FLOWING_REALISTIC_LAVA;
	}

	@Override
	public Fluid getSource() {
		return ModContent.REALISTIC_LAVA;
	}

	@Override
	public Item getBucket() {
		return ModContent.REALISTIC_LAVA_BUCKET;
	}

	public static boolean isRealistic(FluidState state) {
		return !state.isEmpty() && state.getType() instanceof RealisticLavaFluid;
	}

	@Override
	public void tick(final ServerLevel level, final BlockPos pos, BlockState blockState, FluidState fluidState) {
	}

	@Override
	public void randomTick(final ServerLevel level, final BlockPos pos, final FluidState fluidState, final RandomSource random) {
		if (!ModSettings.lavaIgniteFlammables || !level.canSpreadFireAround(pos)) {
			return;
		}
		int passes = random.nextInt(3);
		if (passes > 0) {
			BlockPos testPos = pos;
			for (int pass = 0; pass < passes; pass++) {
				testPos = testPos.offset(random.nextInt(3) - 1, 1, random.nextInt(3) - 1);
				if (!level.isLoaded(testPos)) {
					return;
				}
				BlockState blockState = level.getBlockState(testPos);
				if (blockState.isAir()) {
					if (this.hasFlammableNeighbours(level, testPos)) {
						level.setBlockAndUpdate(testPos, BaseFireBlock.getState(level, testPos));
						return;
					}
				} else if (blockState.is(BlockTags.BLOCKS_LAVA_FIRE_SPREAD)) {
					return;
				}
			}
		} else {
			for (int i = 0; i < 3; i++) {
				BlockPos testPos = pos.offset(random.nextInt(3) - 1, 0, random.nextInt(3) - 1);
				if (!level.isLoaded(testPos)) {
					return;
				}
				if (level.isEmptyBlock(testPos.above()) && this.isFlammable(level, testPos)) {
					level.setBlockAndUpdate(testPos.above(), BaseFireBlock.getState(level, testPos));
				}
			}
		}
	}

	@Override
	protected boolean isRandomlyTicking() {
		return true;
	}

	private boolean hasFlammableNeighbours(final LevelReader level, final BlockPos pos) {
		for (Direction direction : Direction.values()) {
			if (this.isFlammable(level, pos.relative(direction))) {
				return true;
			}
		}
		return false;
	}

	private boolean isFlammable(final LevelReader level, final BlockPos pos) {
		return level.isInsideBuildHeight(pos.getY()) && level.hasChunkAt(pos) && level.getBlockState(pos).ignitedByLava();
	}

	@Override
	public @Nullable ParticleOptions getDripParticle() {
		return ParticleTypes.DRIPPING_LAVA;
	}

	@Override
	protected boolean canConvertToSource(final ServerLevel level) {
		return false;
	}

	@Override
	protected void beforeDestroyingBlock(final LevelAccessor level, final BlockPos pos, final BlockState state) {
		BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
		Block.dropResources(state, level, pos, blockEntity);
	}

	@Override
	protected void entityInside(final Level level, final BlockPos pos, final Entity entity, final InsideBlockEffectApplier effectApplier) {
		effectApplier.apply(InsideBlockEffectType.CLEAR_FREEZE);
		effectApplier.apply(InsideBlockEffectType.LAVA_IGNITE);
		effectApplier.runAfter(InsideBlockEffectType.LAVA_IGNITE, Entity::lavaHurt);
	}

	@Override
	public int getSlopeFindDistance(final LevelReader level) {
		return 4;
	}

	@Override
	public BlockState createLegacyBlock(final FluidState fluidState) {
		return ModContent.REALISTIC_LAVA_BLOCK.defaultBlockState()
			.setValue(LiquidBlock.LEVEL, getLegacyLevel(fluidState))
			.setValue(RealisticWaterBlock.INFINITE, fluidState.isSource() && ModSettings.bucketMakesSource);
	}

	@Override
	public boolean isSame(final Fluid other) {
		return other == ModContent.REALISTIC_LAVA || other == ModContent.FLOWING_REALISTIC_LAVA;
	}

	@Override
	public int getDropOff(final LevelReader level) {
		return 2;
	}

	@Override
	public int getTickDelay(final LevelReader level) {
		return 30;
	}

	@Override
	public boolean canBeReplacedWith(final FluidState state, final BlockGetter level, final BlockPos pos, final Fluid other, final Direction direction) {
		return direction == Direction.DOWN && !other.is(FluidTags.LAVA);
	}

	@Override
	protected float getExplosionResistance() {
		return 100.0F;
	}

	@Override
	public Optional<SoundEvent> getPickupSound() {
		return Optional.of(SoundEvents.BUCKET_FILL_LAVA);
	}

	public static class Flowing extends RealisticLavaFluid {
		@Override
		protected void createFluidStateDefinition(final StateDefinition.Builder<Fluid, FluidState> builder) {
			super.createFluidStateDefinition(builder);
			builder.add(LEVEL);
		}

		@Override
		public int getAmount(final FluidState fluidState) {
			return fluidState.getValue(LEVEL);
		}

		@Override
		public boolean isSource(final FluidState fluidState) {
			return false;
		}
	}

	public static class Source extends RealisticLavaFluid {
		@Override
		public int getAmount(final FluidState fluidState) {
			return 8;
		}

		@Override
		public boolean isSource(final FluidState fluidState) {
			return true;
		}
	}
}
