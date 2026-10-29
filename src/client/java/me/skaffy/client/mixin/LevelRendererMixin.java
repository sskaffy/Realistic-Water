package me.skaffy.client.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import java.util.Optional;
import java.util.OptionalDouble;
import com.mojang.blaze3d.vertex.PoseStack;
import me.skaffy.client.compat.ShaderCompat;
import me.skaffy.client.water.WaterWorld;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Unique
	private boolean realisticWater$splitting;

	@Shadow
	private void executeClassicTransparency(final ChunkSectionsToRender chunkSectionsToRender, final FeatureRenderDispatcher.PreparedFrame featureFrame,
		final RenderPass renderPass) {
	}

	@Inject(method = "executeOit", at = @At("HEAD"))
	private void realisticWater$beforeOit(CallbackInfo ci) {
		ShaderCompat.beforeTranslucent();
	}

	@Inject(method = "executeClassicTransparency", at = @At("HEAD"), cancellable = true)
	private void realisticWater$beforeClassic(ChunkSectionsToRender chunkSectionsToRender, FeatureRenderDispatcher.PreparedFrame featureFrame,
		RenderPass renderPass, CallbackInfo ci) {
		if (this.realisticWater$splitting || renderPass.getClass() != FrontendRenderPass.class || !ShaderCompat.wantsBeforeTranslucent()) {
			return;
		}
		renderPass.close();
		ShaderCompat.beforeTranslucent();
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		try (RenderPass fresh = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Main after realistic fluids",
			main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(fresh);
			this.realisticWater$splitting = true;
			this.executeClassicTransparency(chunkSectionsToRender, featureFrame, fresh);
		} finally {
			this.realisticWater$splitting = false;
		}
		ci.cancel();
	}

	@Inject(method = "submitEntities", at = @At("TAIL"))
	private void realisticWater$submitPackMaterials(PoseStack poseStack, LevelRenderState levelRenderState, SubmitNodeCollector output, CallbackInfo ci) {
		WaterWorld.get().submitPackMaterials(output, levelRenderState.cameraRenderState.pos);
	}

	@Inject(method = "executeOutline", at = @At("HEAD"))
	private void realisticWater$renderWater(CallbackInfo ci) {
		ShaderCompat.inLevel();
	}
}
