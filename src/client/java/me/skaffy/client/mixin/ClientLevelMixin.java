package me.skaffy.client.mixin;

import me.skaffy.block.RealisticSandBlock;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.client.water.OceanMode;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.fire.FireWorld;
import me.skaffy.client.water.WaterWorld;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.injection.Redirect;
import me.skaffy.fluid.RealisticLavaFluid;
import me.skaffy.fluid.RealisticSlimeFluid;
import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
	@Unique
	private static boolean realisticWater$invisible(BlockState state) {
		FluidState fluid = state.getFluidState();
		if (OceanMode.drawsOceanBlockAsVanilla(state)) {
			return false;
		}
		return state.isAir()
			|| RealisticWaterFluid.isRealistic(fluid)
			|| RealisticLavaFluid.isRealistic(fluid)
			|| RealisticSlimeFluid.isRealistic(fluid)
			|| state.getBlock() instanceof RealisticSandBlock;
	}

	@Unique
	private static boolean realisticWater$seaLike(BlockState state) {
		return state.isAir() || state.getBlock() == Blocks.WATER || RealisticWaterBlock.isOcean(state);
	}

	@Unique
	private static boolean realisticWater$invisibleSwap(BlockState oldState, BlockState newState) {
		if (OceanMode.drawsSea() && (RealisticWaterBlock.isOcean(oldState) || RealisticWaterBlock.isOcean(newState))) {
			return realisticWater$seaLike(oldState) && realisticWater$seaLike(newState);
		}
		return realisticWater$invisible(oldState) && realisticWater$invisible(newState);
	}

	@Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
	private void realisticWater$onBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		WaterWorld.get().onBlockChanged((ClientLevel) (Object) this, pos, oldState, newState);
		me.skaffy.client.kelp.RealisticKelp.get().onBlockChanged((ClientLevel) (Object) this, pos, oldState, newState);
		FireWorld.get().onBlockChanged((ClientLevel) (Object) this, pos, oldState, newState);
		if (realisticWater$invisibleSwap(oldState, newState)) {
			ci.cancel();
		}
	}

	@Redirect(method = "tickWeatherEffects", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
	private void realisticWater$noRainSplash(ClientLevel level, ParticleOptions particle, double x, double y, double z, double xd, double yd, double zd) {
		if (WaterSettings.realisticRain && particle == ParticleTypes.RAIN) {
			return;
		}
		level.addParticle(particle, x, y, z, xd, yd, zd);
	}

	@Inject(method = "setBlocksDirty", at = @At("HEAD"), cancellable = true)
	private void realisticWater$skipInvisibleRebuild(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		if (realisticWater$invisibleSwap(oldState, newState)) {
			ci.cancel();
		}
	}
}
