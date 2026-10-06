package me.skaffy.client.mixin;

import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.client.Camera;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public class CameraMixin {
	@Shadow
	private boolean initialized;
	@Shadow
	private Level level;

	@Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
	private void realisticWater$noVanillaFog(CallbackInfoReturnable<FogType> cir) {
		if (this.initialized && this.level != null
			&& RealisticWaterFluid.isRealistic(this.level.getFluidState(((Camera) (Object) this).blockPosition()))) {
			cir.setReturnValue(FogType.NONE);
		}
	}
}
