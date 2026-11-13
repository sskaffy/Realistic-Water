package me.skaffy.mixin;

import me.skaffy.block.RealisticSnowBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class EntitySnowEffectsMixin {
	@Inject(method = "spawnSprintParticle", at = @At("HEAD"), cancellable = true)
	private void realisticWater$noDirtInSnow(CallbackInfo ci) {
		if (RealisticSnowBlock.snowAt((Entity) (Object) this) != null) {
			ci.cancel();
		}
	}

	@Inject(method = "playStepSound", at = @At("HEAD"), cancellable = true)
	private void realisticWater$snowSteps(BlockPos pos, BlockState blockState, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		BlockState snow = RealisticSnowBlock.snowAt(self);
		if (snow != null && snow != blockState) {
			SoundType sound = snow.getSoundType();
			self.playSound(sound.getStepSound(), sound.getVolume() * 0.15F, sound.getPitch());
			ci.cancel();
		}
	}
}
