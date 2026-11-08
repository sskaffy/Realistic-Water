package me.skaffy.mixin;

import me.skaffy.realistic.BodyFloor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.skaffy.realistic.BodyCells;
import me.skaffy.realistic.RealisticCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityCollideMixin implements BodyFloor {
	@Unique
	private @Nullable BlockState realisticWater$floor;
	@Unique
	private boolean realisticWater$honeyWall;

	@Override
	public @Nullable BlockState realisticWater$bodyFloor() {
		return this.realisticWater$floor;
	}

	@WrapOperation(method = "move",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 realisticWater$collideWithBodies(final Entity self, final Vec3 movement, final Operation<Vec3> original) {
		Vec3 adjusted = RealisticCollision.adjust(self, movement);
		this.realisticWater$floor = movement.y < 0.0 && adjusted.y > movement.y + 1.0E-7 ? BodyCells.under(self, adjusted.y) : null;
		this.realisticWater$honeyWall = false;
		if (this.realisticWater$floor == null && movement.y < -0.08 && (adjusted.x != movement.x || adjusted.z != movement.z)) {
			BlockState side = BodyCells.beside(self);
			this.realisticWater$honeyWall = side != null && side.getBlock() instanceof HoneyBlock;
		}
		return original.call(self, adjusted);
	}

	@WrapOperation(method = "move",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState realisticWater$effectState(final Level level, final BlockPos pos, final Operation<BlockState> original) {
		BlockState state = original.call(level, pos);
		return this.realisticWater$floor != null && state.isAir() ? this.realisticWater$floor : state;
	}

	@WrapOperation(method = {"getBlockJumpFactor", "getBlockSpeedFactor"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
	private BlockState realisticWater$factorState(final Level level, final BlockPos pos, final Operation<BlockState> original) {
		BlockState state = original.call(level, pos);
		return this.realisticWater$floor != null && state.isAir() ? this.realisticWater$floor : state;
	}

	@Inject(method = "move", at = @At("HEAD"))
	private void realisticWater$forgetFloor(final MoverType type, final Vec3 delta, final CallbackInfo ci) {
		this.realisticWater$floor = null;
		this.realisticWater$honeyWall = false;
	}

	@Inject(method = "move", at = @At("TAIL"))
	private void realisticWater$honeySlide(final MoverType type, final Vec3 delta, final CallbackInfo ci) {
		if (!this.realisticWater$honeyWall) {
			return;
		}
		Entity self = (Entity) (Object) this;
		Vec3 v = self.getDeltaMovement();
		if (v.y < -0.13) {
			double keep = -0.05 / v.y;
			self.setDeltaMovement(v.x * keep, -0.05, v.z * keep);
		} else {
			self.setDeltaMovement(v.x, -0.05, v.z);
		}
		self.resetFallDistance();
	}
}
