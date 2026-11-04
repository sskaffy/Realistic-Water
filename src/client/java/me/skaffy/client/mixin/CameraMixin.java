package me.skaffy.client.mixin;

import me.skaffy.client.water.OceanMode;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.client.water.WaterWorld;
import me.skaffy.fluid.RealisticSlimeFluid;
import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.client.Camera;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
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
		if (!this.initialized || this.level == null) {
			return;
		}
		BlockState state = this.level.getBlockState(((Camera) (Object) this).blockPosition());
		FluidState fluid = state.getFluidState();
		boolean ocean = OceanMode.active() && (OceanMode.isVanillaWater(fluid) || RealisticWaterBlock.isOcean(state)) && WaterWorld.get().seaDrawsFog();
		if (ocean || RealisticWaterFluid.isRealistic(fluid) && !OceanMode.drawsOceanBlockAsVanilla(state) || RealisticSlimeFluid.isRealistic(fluid)) {
			cir.setReturnValue(FogType.NONE);
		}
	}
}
