package me.skaffy.client.mixin;

import me.skaffy.client.kelp.RealisticKelp;
import me.skaffy.client.realistic.RealisticGrab;
import me.skaffy.client.realistic.RealisticMining;
import me.skaffy.client.realistic.RealisticPicking;
import me.skaffy.client.realistic.RealisticUse;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class RealisticBlockMinecraftMixin {
	@Shadow
	private int rightClickDelay;

	@Inject(method = "pick", at = @At("TAIL"))
	private void realisticWater$pickCells(float partialTicks, CallbackInfo ci) {
		RealisticPicking.refine((Minecraft) (Object) this, partialTicks);
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void realisticWater$startUsing(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.player != null && RealisticGrab.start(mc)) {
			this.rightClickDelay = 4;
			ci.cancel();
		} else if (mc.player != null && RealisticUse.start(mc)) {
			this.rightClickDelay = 4;
			ci.cancel();
		} else if (mc.player != null && RealisticKelp.get().tryGrab(mc)) {
			this.rightClickDelay = 4;
			ci.cancel();
		} else if (mc.player != null && me.skaffy.client.trees.TreeGrab.get().tryGrab(mc)) {
			this.rightClickDelay = 4;
			ci.cancel();
		} else if (mc.player != null && me.skaffy.client.grass.RealisticGrass.get().tryGrab(mc)) {
			this.rightClickDelay = 4;
			ci.cancel();
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void realisticWater$startMining(CallbackInfoReturnable<Boolean> cir) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.player != null && !mc.player.isSpectator() && RealisticMining.start(mc)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void realisticWater$keepMining(boolean down, CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.player != null && !mc.player.isUsingItem() && RealisticMining.tick(mc, down)) {
			ci.cancel();
		}
	}
}
