package me.skaffy.client.fire;

import me.skaffy.client.water.Material;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class FireCover {
	private static final int MAX_ENTITIES = 32;

	private FireCover() {
	}

	public static boolean active() {
		return WaterSettings.fire && WaterSettings.fireParticles && WaterSettings.fireCoverEntities && WaterWorld.get().gpuReady();
	}

	public static boolean covers(double x, double y, double z) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !active()) {
			return false;
		}
		double range = WaterSettings.fireParticleRange;
		return mc.player.getEyePosition().distanceToSqr(x, y, z) <= range * range;
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null || mc.isPaused() || !active() || level.getGameTime() % 3L != 0L) {
			return;
		}
		WaterWorld water = WaterWorld.get();
		Vec3 wind = FireEffects.wind();
		float wx = (float) wind.x * 30.0F;
		float wz = (float) wind.z * 30.0F;
		float amount = Math.max(WaterSettings.fireFlameAmount * WaterSettings.fireCoverAmount, 0.01F);
		float step = Math.max(WaterSettings.fireParticleStep * 2.4F / (float) Math.cbrt(amount), 0.06F);
		boolean firstPerson = mc.options.getCameraType().isFirstPerson();
		int drawn = 0;
		for (Entity entity : level.entitiesForRendering()) {
			if (drawn >= MAX_ENTITIES) {
				break;
			}
			if (!entity.displayFireAnimation() || !covers(entity.getX(), entity.getY(), entity.getZ())) {
				continue;
			}
			drawn++;
			AABB box = entity.getBoundingBox();
			Vec3 motion = entity.getDeltaMovement();
			float vx = (float) motion.x * 20.0F * 0.6F + wx;
			float vz = (float) motion.z * 20.0F * 0.6F + wz;
			double cx = (box.minX + box.maxX) * 0.5;
			double cz = (box.minZ + box.maxZ) * 0.5;
			double halfX = box.getXsize() * 0.5;
			double halfZ = box.getZsize() * 0.5;
			double tall = box.getYsize();
			float rise = 0.7F + 0.5F * (float) Math.min(tall, 2.0);
			if (entity == mc.player && firstPerson) {
				double height = tall * 0.4;
				double ring = 0.14;
				double thick = 0.05;
				water.addGasPuff(Material.FLAME, cx + halfX + ring, box.minY + 0.03, cz, thick, halfZ + ring, height, vx, rise, vz, step);
				water.addGasPuff(Material.FLAME, cx - halfX - ring, box.minY + 0.03, cz, thick, halfZ + ring, height, vx, rise, vz, step);
				water.addGasPuff(Material.FLAME, cx, box.minY + 0.03, cz + halfZ + ring, halfX + ring, thick, height, vx, rise, vz, step);
				water.addGasPuff(Material.FLAME, cx, box.minY + 0.03, cz - halfZ - ring, halfX + ring, thick, height, vx, rise, vz, step);
			} else {
				float bodyStep = step * (float) Math.max(1.0, Math.cbrt(halfX * halfZ * tall * 4.0 / 0.65));
				water.addGasPuff(Material.FLAME, cx, box.minY + 0.03, cz, halfX + 0.04, halfZ + 0.04, tall * 0.75, vx, rise, vz, bodyStep);
			}
		}
	}
}
