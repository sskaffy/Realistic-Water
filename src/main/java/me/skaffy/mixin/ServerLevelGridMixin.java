package me.skaffy.mixin;

import me.skaffy.realistic.GridSpace;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class ServerLevelGridMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"), cancellable = true)
	private void realisticWater$noteGridChange(final BlockPos pos, final BlockState old, final BlockState current,
		final @Block.UpdateFlags int updateFlags, final CallbackInfo info) {
		if (!GridSpace.isGridPos(pos)) {
			return;
		}
		info.cancel();
		RealisticBlockEntity body = GridSpace.bodyAt((ServerLevel) (Object) this, pos);
		if (body != null) {
			body.gridChanged(pos);
		}
	}

	@Inject(
		method = "playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
		at = @At("HEAD"), cancellable = true)
	private void realisticWater$moveGridSound(final @Nullable Entity except, final double x, final double y, final double z,
		final Holder<SoundEvent> sound, final SoundSource source, final float volume, final float pitch, final long seed, final CallbackInfo info) {
		GridSpace.Found found = GridSpace.find((ServerLevel) (Object) this, x, y, z);
		if (found == null) {
			if (GridSpace.isGridPos((int) Math.floor(x))) {
				me.skaffy.RealisticWater.LOGGER.warn("[sound] {} at grid ({},{},{}) belongs to no body, so nobody hears it",
					sound.getRegisteredName(), (int) x, (int) y, (int) z);
			}
			return;
		}
		info.cancel();
		GridSpace.Slot slot = found.slot();
		found.body().sendGridSound((float) (x - slot.originX()), (float) (y - slot.originY()), (float) (z - slot.originZ()),
			sound, source, volume, pitch);
	}

	@Inject(method = "levelEvent", at = @At("HEAD"), cancellable = true)
	private void realisticWater$moveGridLevelEvent(final @Nullable Entity source, final @LevelEvent.Value int type, final BlockPos pos, final int data,
		final CallbackInfo info) {
		GridSpace.Found found = GridSpace.find((ServerLevel) (Object) this, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (found == null) {
			if (GridSpace.isGridPos(pos)) {
				me.skaffy.RealisticWater.LOGGER.warn("[levelEvent] {} at grid {} belongs to no body, so nobody sees or hears it", type, pos);
			}
			return;
		}
		info.cancel();
		GridSpace.Slot slot = found.slot();
		found.body().sendGridLevelEvent((float) (pos.getX() + 0.5 - slot.originX()), (float) (pos.getY() + 0.5 - slot.originY()),
			(float) (pos.getZ() + 0.5 - slot.originZ()), type, data);
	}

	@Inject(method = "addFreshEntity", at = @At("HEAD"))
	private void realisticWater$moveGridDrop(final Entity entity, final CallbackInfoReturnable<Boolean> info) {
		if (entity instanceof RealisticBlockEntity) {
			return;
		}
		GridSpace.Found found = GridSpace.find((ServerLevel) (Object) this, entity.getX(), entity.getY(), entity.getZ());
		if (found == null) {
			return;
		}
		Vec3 at = found.toWorld(entity.getX(), entity.getY(), entity.getZ());
		entity.setPos(at);
		entity.setDeltaMovement(Vec3.ZERO);
	}
}
