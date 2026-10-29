package me.skaffy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.skaffy.realistic.RealisticCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class EntityCollideMixin {
	@WrapOperation(method = "move",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 realisticWater$collideWithBodies(final Entity self, final Vec3 movement, final Operation<Vec3> original) {
		return original.call(self, RealisticCollision.adjust(self, movement));
	}
}
