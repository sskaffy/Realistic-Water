package me.skaffy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;
	@Shadow
	private boolean clientIsFloating;
	@Shadow
	private int aboveGroundTickCount;

	@Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
	private void realisticWater$bodyVehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
		if (this.player.getRootVehicle() instanceof RealisticBlockEntity) {
			ci.cancel();
		}
	}

	@WrapOperation(method = "handlePlayerPositionChange", require = 0,
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;noCollision(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Z"))
	private boolean realisticWater$trustClientOnBodies(final ServerLevel level, final Entity entity, final AABB box, final Operation<Boolean> original) {
		if (!level.getEntitiesOfClass(RealisticBlockEntity.class, box.inflate(0.5)).isEmpty()) {
			return false;
		}
		return original.call(level, entity, box);
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void realisticWater$standingOnBody(CallbackInfo ci) {
		if (!this.clientIsFloating) {
			return;
		}
		AABB feet = this.player.getBoundingBox();
		AABB below = new AABB(feet.minX, feet.minY - 0.6, feet.minZ, feet.maxX, feet.minY + 0.1, feet.maxZ);
		if (!this.player.level().getEntitiesOfClass(RealisticBlockEntity.class, below).isEmpty()) {
			this.clientIsFloating = false;
			this.aboveGroundTickCount = 0;
		}
	}
}
