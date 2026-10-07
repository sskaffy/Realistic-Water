package me.skaffy.client.mixin;

import me.skaffy.block.RealisticSandBlock;
import me.skaffy.client.water.WaterWorld;
import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
	@Unique
	private static boolean realisticWater$invisible(BlockState state) {
		return state.isAir() || RealisticWaterFluid.isRealistic(state.getFluidState()) || state.getBlock() instanceof RealisticSandBlock;
	}

	@Unique
	private static boolean realisticWater$invisibleSwap(BlockState oldState, BlockState newState) {
		return realisticWater$invisible(oldState) && realisticWater$invisible(newState);
	}

	@Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
	private void realisticWater$onBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
		WaterWorld.get().onBlockChanged((ClientLevel) (Object) this, pos, oldState, newState);
		if (realisticWater$invisibleSwap(oldState, newState)) {
			ci.cancel();
		}
	}

	@Inject(method = "setBlocksDirty", at = @At("HEAD"), cancellable = true)
	private void realisticWater$skipInvisibleRebuild(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		if (realisticWater$invisibleSwap(oldState, newState)) {
			ci.cancel();
		}
	}
}
