package me.skaffy.client.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.vitrail.glsl.TranslatedUnit;
import dev.vitrail.glsl.VertexInputs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.skaffy.client.compat.VitrailSea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.vitrail.glsl.ProgramTranslator", remap = false)
public abstract class VitrailProgramTranslatorMixin {
	@Inject(method = "translated", at = @At("HEAD"), require = 0, remap = false)
	private static void realisticWater$unmarkBefore(CallbackInfoReturnable<?> cir) {
		VitrailSea.BLOCK_HAS_UNIFORMS.set(false);
	}

	@Inject(method = "translated", at = @At("RETURN"), require = 0, remap = false)
	private static void realisticWater$unmarkAfter(CallbackInfoReturnable<?> cir) {
		VitrailSea.BLOCK_HAS_UNIFORMS.set(false);
	}

	@WrapOperation(method = "translated", require = 0, remap = false,
		at = @At(value = "INVOKE", target = "Ldev/vitrail/glsl/ProgramTranslator;fixedFunctionFirst(Ljava/util/Map;)Ljava/util/List;", remap = false))
	private static List<TranslatedUnit.Uniform> realisticWater$seaUniforms(Map<String, TranslatedUnit.Uniform> uniforms,
		Operation<List<TranslatedUnit.Uniform>> original, @Local(argsOnly = true) VertexInputs inputs) {
		List<TranslatedUnit.Uniform> block = original.call(uniforms);
		boolean terrain = inputs == VertexInputs.TERRAIN || inputs == VertexInputs.TERRAIN_SEPARATE_AO;
		boolean clash = false;
		for (String[] ours : VitrailSea.DECLARATIONS) {
			clash |= uniforms.containsKey(ours[0]);
		}
		VitrailSea.BLOCK_HAS_UNIFORMS.set(terrain && !clash);
		if (!terrain || clash) {
			return block;
		}
		List<TranslatedUnit.Uniform> out = new ArrayList<>(block);
		for (String[] ours : VitrailSea.DECLARATIONS) {
			out.add(TranslatedUnit.Uniform.of(ours[0], ours[1]));
		}
		return List.copyOf(out);
	}
}
