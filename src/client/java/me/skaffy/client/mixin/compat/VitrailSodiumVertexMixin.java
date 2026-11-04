package me.skaffy.client.mixin.compat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.skaffy.client.compat.VitrailSea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.vitrail.glsl.SodiumVertex", remap = false)
public abstract class VitrailSodiumVertexMixin {
	@Inject(method = "prologue", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
	private static void realisticWater$liftSea(List<String> carried, Set<String> used, Map<String, String> synthesized, boolean separateAo,
		CallbackInfoReturnable<List<String>> cir) {
		cir.setReturnValue(VitrailSea.vertexLines(cir.getReturnValue(), carried, dev.vitrail.glsl.SodiumVertex.BLOCK_ID,
			dev.vitrail.glsl.SodiumVertex.PROLOGUE));
	}
}
