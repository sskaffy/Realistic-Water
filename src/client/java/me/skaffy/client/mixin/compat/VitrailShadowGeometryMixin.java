package me.skaffy.client.mixin.compat;

import me.skaffy.client.compat.ShaderCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.vitrail.render.ShadowGeometry", remap = false)
public abstract class VitrailShadowGeometryMixin {
	@Inject(method = "gather", at = @At("TAIL"), require = 0, remap = false)
	private static void realisticWater$keepWalk(CallbackInfo ci) {
		ShaderCompat.vitrailShadowGathered();
	}

	@Inject(method = "submit", at = @At("TAIL"), require = 0, remap = false)
	private static void realisticWater$submitCasters(Minecraft minecraft, Vec3 camera, CallbackInfo ci) {
		ShaderCompat.vitrailShadowSubmit(camera);
	}
}
