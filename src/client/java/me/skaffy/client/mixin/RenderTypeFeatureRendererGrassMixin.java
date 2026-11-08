package me.skaffy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.skaffy.client.grass.GrassPack;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererGrassMixin {
	@WrapOperation(method = "executeGroup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/StagedVertexBuffer;"
		+ "getExecuteInfo(Lnet/minecraft/client/renderer/StagedVertexBuffer$Draw;)Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;"))
	private StagedVertexBuffer.ExecuteInfo realisticWater$grassMesh(StagedVertexBuffer buffer, StagedVertexBuffer.Draw draw,
		Operation<StagedVertexBuffer.ExecuteInfo> original, @Local PreparedRenderType renderType) {
		StagedVertexBuffer.ExecuteInfo info = original.call(buffer, draw);
		if (info != null && GrassPack.isOurs(renderType)) {
			return GrassPack.get().executeInfo(draw, info);
		}
		int tree = info != null ? me.skaffy.client.trees.TreePack.whichDraw(renderType) : 0;
		if (tree != 0) {
			return me.skaffy.client.trees.TreePack.get().executeInfo(draw, tree == 2);
		}
		return info;
	}
}
