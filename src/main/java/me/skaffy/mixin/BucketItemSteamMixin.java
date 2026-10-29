package me.skaffy.mixin;

import me.skaffy.fluid.RealisticWaterFluid;
import me.skaffy.portal.PortalTransit;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BucketItem.class)
public abstract class BucketItemSteamMixin {
	@Shadow
	@Final
	protected Fluid content;

	@Redirect(method = "emptyContents", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDDLnet/minecraft/network/protocol/game/ClientboundLevelParticlesPacket$RandomizationType;)I"),
		require = 0)
	private int realisticWater$steam(ServerLevel level, ParticleOptions particle, double x, double y, double z, int count, double dx, double dy, double dz,
		double speed, ClientboundLevelParticlesPacket.RandomizationType type) {
		if (RealisticWaterFluid.isRealistic(this.content.defaultFluidState())) {
			PortalTransit.steamBurst(level, new Vec3(x + 0.5, y + 0.1, z + 0.5), 8.0F);
			return count;
		}
		return level.sendParticles(particle, x, y, z, count, dx, dy, dz, speed, type);
	}
}
