package me.skaffy.fluid;

import java.util.Optional;
import me.skaffy.ModContent;
import me.skaffy.block.CustomLiquidBlock;
import me.skaffy.liquid.CustomLiquid;
import me.skaffy.liquid.CustomLiquids;
import me.skaffy.liquid.LiquidParam;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.InsideBlockEffectType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public abstract class CustomLiquidFluid extends FlowingFluid {
	public static final IntegerProperty SLOT = IntegerProperty.create("slot", 0, CustomLiquid.MAX_SLOTS - 1);
	private static final int DAMAGE_INTERVAL = 10;

	@Override
	public Fluid getFlowing() {
		return ModContent.FLOWING_CUSTOM_LIQUID;
	}

	@Override
	public Fluid getSource() {
		return ModContent.CUSTOM_LIQUID;
	}

	@Override
	public Item getBucket() {
		return ModContent.CUSTOM_LIQUID_BUCKET;
	}

	public static boolean isCustom(FluidState state) {
		return !state.isEmpty() && state.getType() instanceof CustomLiquidFluid;
	}

	public static int slot(FluidState state) {
		return isCustom(state) ? state.getValue(SLOT) : -1;
	}

	@Override
	protected void createFluidStateDefinition(final StateDefinition.Builder<Fluid, FluidState> builder) {
		super.createFluidStateDefinition(builder);
		builder.add(SLOT);
	}

	@Override
	public void tick(final ServerLevel level, final BlockPos pos, BlockState blockState, FluidState fluidState) {
	}

	@Override
	public @Nullable ParticleOptions getDripParticle() {
		return null;
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
		BlockState state = level.getBlockState(pos);
		CustomLiquid liquid = CustomLiquids.get(level, slot(state.getFluidState()));
		if (liquid == null) {
			return;
		}
		float drag = liquid.get(LiquidParam.DRAG);
		if (drag > 0.0F) {
			entity.makeStuckInBlock(state, new Vec3(Mth.lerp(drag, 1.0F, 0.25F), Mth.lerp(drag, 1.0F, 0.15F), Mth.lerp(drag, 1.0F, 0.25F)));
		}
		if (liquid.on(LiquidParam.BURNS)) {
			effectApplier.apply(InsideBlockEffectType.LAVA_IGNITE);
		} else if (liquid.on(LiquidParam.EXTINGUISHES)) {
			effectApplier.apply(InsideBlockEffectType.EXTINGUISH);
		}
		float damage = liquid.get(LiquidParam.DAMAGE);
		if (damage > 0.0F && level instanceof ServerLevel server && entity.tickCount % DAMAGE_INTERVAL == 0) {
			entity.hurtServer(server, liquid.on(LiquidParam.BURNS) ? level.damageSources().lava() : level.damageSources().magic(),
				damage * DAMAGE_INTERVAL / 20.0F);
		}
	}

	@Override
	public int getSlopeFindDistance(final LevelReader level) {
		return 2;
	}

	@Override
	public BlockState createLegacyBlock(final FluidState fluidState) {
		return ModContent.CUSTOM_LIQUID_BLOCK.defaultBlockState()
			.setValue(LiquidBlock.LEVEL, getLegacyLevel(fluidState))
			.setValue(CustomLiquidBlock.SLOT, fluidState.getValue(SLOT));
	}

	@Override
	public boolean isSame(final Fluid other) {
		return other == ModContent.CUSTOM_LIQUID || other == ModContent.FLOWING_CUSTOM_LIQUID;
	}

	@Override
	public int getDropOff(final LevelReader level) {
		return 1;
	}

	@Override
	public int getTickDelay(final LevelReader level) {
		return 40;
	}

	@Override
	public boolean canBeReplacedWith(final FluidState state, final BlockGetter level, final BlockPos pos, final Fluid other, final Direction direction) {
		return false;
	}

	@Override
	protected float getExplosionResistance() {
		return 100.0F;
	}

	@Override
	public Optional<SoundEvent> getPickupSound() {
		return Optional.of(SoundEvents.BUCKET_FILL);
	}

	public static class Flowing extends CustomLiquidFluid {
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

	public static class Source extends CustomLiquidFluid {
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
