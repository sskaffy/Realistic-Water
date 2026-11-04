package me.skaffy.client.mixin.compat;

import com.llamalad7.mixinextras.sugar.Local;
import dev.vitrail.glsl.VertexInputs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.vitrail.glsl.TranslationCache", remap = false)
public abstract class VitrailTranslationCacheMixin {
	@Inject(method = "keyOf", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
	private static void realisticWater$seaKey(CallbackInfoReturnable<String> cir, @Local(argsOnly = true) VertexInputs inputs) {
		String key = cir.getReturnValue();
		if (key != null && (inputs == VertexInputs.TERRAIN || inputs == VertexInputs.TERRAIN_SEPARATE_AO)) {
			cir.setReturnValue(key + "rwsea3");
		}
	}
}
