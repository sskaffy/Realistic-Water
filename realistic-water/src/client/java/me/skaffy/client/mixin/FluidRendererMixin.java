package me.skaffy.client.mixin;

import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FluidRenderer.class)
public class FluidRendererMixin {
	@Inject(method = "tesselate", at = @At("HEAD"), cancellable = true)
	private void realisticWater$skipRealisticWater(
		BlockAndTintGetter level, BlockPos pos, FluidRenderer.Output output, BlockState blockState, FluidState fluidState, CallbackInfo ci
	) {
		if (RealisticWaterFluid.isRealistic(fluidState)) {
			ci.cancel();
		}
	}
}
