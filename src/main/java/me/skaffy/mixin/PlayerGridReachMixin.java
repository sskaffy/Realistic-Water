package me.skaffy.mixin;

import me.skaffy.realistic.GridSpace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerGridReachMixin {
	@Inject(method = "isWithinBlockInteractionRange", at = @At("HEAD"), cancellable = true)
	private void realisticWater$reachToGridBlock(final BlockPos pos, final double buffer, final CallbackInfoReturnable<Boolean> info) {
		GridSpace.Found found = GridSpace.find(((Player) (Object) this).level(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (found == null) {
			return;
		}
		Player player = (Player) (Object) this;
		Vec3 at = found.toWorld(pos);
		double reach = player.blockInteractionRange() + buffer;
		info.setReturnValue(player.getEyePosition().distanceToSqr(at) < reach * reach);
	}
}
