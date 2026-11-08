package me.skaffy.client.mixin;

import me.skaffy.client.realistic.BoatWaterMask;
import net.minecraft.client.renderer.entity.AbstractBoatRenderer;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBoatRenderer.class)
public abstract class AbstractBoatRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/vehicle/boat/AbstractBoat;Lnet/minecraft/client/renderer/entity/state/BoatRenderState;F)V",
		at = @At("TAIL"))
	private void realisticWater$waterPatch(AbstractBoat entity, BoatRenderState state, float partialTicks, CallbackInfo ci) {
		if (!state.isUnderWater && !BoatWaterMask.needed(entity.level(), entity.getBoundingBox())) {
			state.isUnderWater = true;
		}
	}
}
