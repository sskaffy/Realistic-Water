package me.skaffy.client.mixin;

import me.skaffy.client.compat.ShaderCompat;
import me.skaffy.client.water.WaterWorld;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private net.minecraft.client.renderer.Projection hudProjection;

	@Inject(method = "renderItemInHand", at = @At("HEAD"))
	private void realisticWater$beginHand(CallbackInfo ci) {
		me.skaffy.client.jelly.GelItems.beginHand();
	}

	@Inject(method = "renderItemInHand", at = @At("RETURN"))
	private void realisticWater$afterHand(net.minecraft.client.renderer.state.level.CameraRenderState cameraState,
		net.minecraft.client.renderer.state.level.PlayerRenderState playerState, com.mojang.renderpearl.api.textures.GpuTextureView depthTextureView,
		CallbackInfo ci) {
		me.skaffy.client.jelly.GelItems.drawHand(this.hudProjection.getMatrix(new Matrix4f()), new Matrix4f(cameraState.viewRotationMatrix),
			depthTextureView);
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void realisticWater$beginLevel(CallbackInfo ci) {
		ShaderCompat.beginLevel();
	}

	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private Matrix4f realisticWater$levelProjection(Matrix4f projection) {
		WaterWorld.LEVEL_PROJECTION.set(projection);
		return projection;
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void realisticWater$afterLevel(CallbackInfo ci) {
		ShaderCompat.afterLevel();
	}
}
