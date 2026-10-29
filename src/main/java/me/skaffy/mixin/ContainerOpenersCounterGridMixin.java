package me.skaffy.mixin;

import java.util.ArrayList;
import java.util.List;
import me.skaffy.realistic.GridSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ContainerUser;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ContainerOpenersCounter.class)
public abstract class ContainerOpenersCounterGridMixin {
	@Inject(method = "getEntitiesWithContainerOpen", at = @At("HEAD"), cancellable = true)
	private void realisticWater$findOpenersOnBodies(final Level level, final BlockPos pos,
		final CallbackInfoReturnable<List<ContainerUser>> info) {
		if (!GridSpace.isGridPos(pos) || !(level instanceof ServerLevel server)) {
			return;
		}
		ContainerOpenersCounter self = (ContainerOpenersCounter) (Object) this;
		List<ContainerUser> open = new ArrayList<>();
		for (ServerPlayer player : server.players()) {
			if (!player.isSpectator() && player.hasContainerOpen(self, pos)) {
				open.add(player);
			}
		}
		info.setReturnValue(open);
	}
}
