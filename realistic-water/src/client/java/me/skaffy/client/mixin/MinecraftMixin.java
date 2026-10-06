package me.skaffy.client.mixin;

import me.skaffy.client.water.ClipRecorder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {
	@ModifyArg(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/DeltaTracker$Timer;advanceGameTime(J)I")
	)
	private long realisticWater$clipClock(long realMillis) {
		return ClipRecorder.get().gameClockMillis(realMillis);
	}

	@Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
	private void realisticWater$captureClipFrame(boolean advanceGameTime, CallbackInfo ci) {
		ClipRecorder.get().captureFrame((Minecraft) (Object) this, advanceGameTime);
	}
}
