package me.skaffy.client.mixin;

import me.skaffy.client.water.ClipRecorder;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(DeltaTracker.Timer.class)
public class DeltaTrackerTimerMixin {
	@ModifyVariable(method = "getGameTimeDeltaPartialTick", at = @At("HEAD"), argsOnly = true)
	private boolean realisticWater$interpolateWhileRendering(boolean ignoreFrozenGame) {
		return ignoreFrozenGame || ClipRecorder.get().isRecording();
	}
}
