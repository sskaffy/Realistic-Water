package me.skaffy.mixin;

import me.skaffy.realistic.GridSpace;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockEventMixin {
	@Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
	private void realisticWater$gridBlockEvent(final BlockPos pos, final Block block, final int paramA, final int paramB, final CallbackInfo info) {
		if (!GridSpace.isGridPos(pos)) {
			return;
		}
		info.cancel();
		ServerLevel level = (ServerLevel) (Object) this;
		RealisticBlockEntity body = GridSpace.bodyAt(level, pos);
		if (body == null) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		if (state.is(block) && state.triggerEvent(level, pos, paramA, paramB)) {
			body.sendGridBlockEvent(pos, paramA, paramB);
		}
	}
}
