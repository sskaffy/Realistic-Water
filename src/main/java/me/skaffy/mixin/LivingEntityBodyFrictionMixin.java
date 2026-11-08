package me.skaffy.mixin;

import me.skaffy.realistic.BodyFloor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class LivingEntityBodyFrictionMixin {
	@WrapOperation(method = "travelInAir",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState realisticWater$frictionState(final Level level, final BlockPos pos, final Operation<BlockState> original) {
		BlockState state = original.call(level, pos);
		BlockState floor = ((BodyFloor) this).realisticWater$bodyFloor();
		return floor != null && state.isAir() ? floor : state;
	}
}
