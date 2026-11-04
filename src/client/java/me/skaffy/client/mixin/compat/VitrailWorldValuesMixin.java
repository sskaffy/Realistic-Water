package me.skaffy.client.mixin.compat;

import dev.vitrail.uniform.UniformCatalog;
import me.skaffy.client.compat.VitrailSeaUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.vitrail.uniform.values.WorldValues", remap = false)
public abstract class VitrailWorldValuesMixin {
	@Inject(method = "register", at = @At("TAIL"), require = 0, remap = false)
	private static void realisticWater$seaUniforms(UniformCatalog.Builder builder, CallbackInfo ci) {
		VitrailSeaUniforms.register(builder);
	}
}
