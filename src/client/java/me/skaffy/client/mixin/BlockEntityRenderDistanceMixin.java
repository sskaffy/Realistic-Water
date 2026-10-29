package me.skaffy.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.skaffy.client.realistic.ClientGrid;
import me.skaffy.realistic.GridSpace;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDistanceMixin {
	@WrapOperation(method = "tryExtractRenderState",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;shouldRender(Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/phys/Vec3;)Z"))
	private boolean realisticWater$drawGridBlockEntities(final BlockEntityRenderer<BlockEntity, ?> renderer, final BlockEntity blockEntity,
		final Vec3 cameraPos, final Operation<Boolean> original) {
		if (original.call(renderer, blockEntity, cameraPos)) {
			return true;
		}
		BlockPos at = blockEntity.getBlockPos();
		if (!GridSpace.isGridPos(at)) {
			return false;
		}
		RealisticBlockEntity body = ClientGrid.bodyAt(at);
		if (body == null) {
			return false;
		}
		float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
		return body.gridToWorld(at, partial).closerThan(cameraPos, renderer.getViewDistance());
	}
}
