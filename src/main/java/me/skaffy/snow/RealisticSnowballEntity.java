package me.skaffy.snow;

import me.skaffy.ModContent;
import me.skaffy.net.SnowBurstPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class RealisticSnowballEntity extends ThrowableItemProjectile {
	private static final double BURST_REACH = 96.0;

	public RealisticSnowballEntity(final EntityType<? extends RealisticSnowballEntity> type, final Level level) {
		super(type, level);
	}

	public RealisticSnowballEntity(final Level level, final LivingEntity owner, final ItemStack stack) {
		super(ModContent.REALISTIC_SNOWBALL, owner, level, stack);
	}

	public RealisticSnowballEntity(final Level level, final double x, final double y, final double z, final ItemStack stack) {
		super(ModContent.REALISTIC_SNOWBALL, x, y, z, level, stack);
	}

	@Override
	protected Item getDefaultItem() {
		return ModContent.REALISTIC_SNOWBALL_ITEM;
	}

	@Override
	public void handleEntityEvent(final @EntityEvent.Value byte id) {
		if (id == 3) {
			for (int i = 0; i < 4; i++) {
				this.level().addParticle(ParticleTypes.ITEM_SNOWBALL, this.getX(), this.getY(), this.getZ(), 0.0, 0.0, 0.0);
			}
		}
	}

	@Override
	protected void onHitEntity(final EntityHitResult hitResult) {
		super.onHitEntity(hitResult);
		Entity entity = hitResult.getEntity();
		entity.hurt(this.damageSources().thrown(this, this.getOwner()), entity instanceof Blaze ? 3 : 0);
	}

	@Override
	protected void onHit(final HitResult hitResult) {
		super.onHit(hitResult);
		if (this.level() instanceof ServerLevel level) {
			Vec3 motion = this.getDeltaMovement();
			Vec3 at = hitResult.getLocation();
			byte face = hitResult instanceof BlockHitResult blockHit && hitResult.getType() == HitResult.Type.BLOCK ? (byte) blockHit.getDirection().get3DDataValue() : -1;
			SnowBurstPayload payload = new SnowBurstPayload(at.x, at.y, at.z, (float) motion.x, (float) motion.y, (float) motion.z, face);
			for (ServerPlayer player : level.players()) {
				if (player.distanceToSqr(at) < BURST_REACH * BURST_REACH && ServerPlayNetworking.canSend(player, SnowBurstPayload.TYPE)) {
					ServerPlayNetworking.send(player, payload);
				}
			}
			level.broadcastEntityEvent(this, (byte) 3);
			this.discard();
		}
	}
}
