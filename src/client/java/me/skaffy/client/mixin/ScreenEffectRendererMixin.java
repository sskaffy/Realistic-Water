package me.skaffy.client.mixin;

import me.skaffy.client.fire.FireCover;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private void realisticWater$noFireOverlay(float partialTicks, SubmitNodeCollector submitNodeCollector, PlayerRenderState playerRenderState,
		CameraRenderState cameraRenderState, boolean hideGui, CallbackInfo ci) {
		if (playerRenderState.isOnFire && FireCover.active()) {
			playerRenderState.isOnFire = false;
		}
	}
}
