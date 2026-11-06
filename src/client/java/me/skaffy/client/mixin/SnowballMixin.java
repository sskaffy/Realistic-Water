package me.skaffy.client.mixin;

import me.skaffy.client.water.WaterWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Snowball.class)
public class SnowballMixin {
	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void realisticWater$burst(byte id, CallbackInfo ci) {
		Snowball self = (Snowball) (Object) this;
		if (id == 3 && self.level() instanceof ClientLevel level) {
			WaterWorld.get().onSnowballHit(level, self);
		}
	}
}
