package me.skaffy.mixin;

import me.skaffy.realistic.RealisticCollision;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerEdgeMixin {
	@Inject(method = "canFallAtLeast", at = @At("HEAD"), cancellable = true)
	private void realisticWater$bodyUnderfoot(final double dx, final double dz, final double minHeight,
		final CallbackInfoReturnable<Boolean> info) {
		if (RealisticCollision.supportedBy((Player) (Object) this, dx, dz, minHeight)) {
			info.setReturnValue(false);
		}
	}
}
