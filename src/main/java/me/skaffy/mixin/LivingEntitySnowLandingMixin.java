package me.skaffy.mixin;

import me.skaffy.block.RealisticSnowBlock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LivingEntity.class)
public class LivingEntitySnowLandingMixin {
	@ModifyArg(method = "checkFallDamage", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/core/particles/BlockParticleOption;<init>(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/level/block/state/BlockState;)V"),
		index = 1)
	private BlockState realisticWater$snowLanding(BlockState state) {
		BlockState snow = RealisticSnowBlock.snowAt((Entity) (Object) this);
		return snow != null ? snow : state;
	}
}
