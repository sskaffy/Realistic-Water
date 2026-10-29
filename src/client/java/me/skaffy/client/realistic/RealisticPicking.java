package me.skaffy.client.realistic;

import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public final class RealisticPicking {
	private RealisticPicking() {
	}

	public static void refine(Minecraft mc, float partialTicks) {
		Entity camera = mc.getCameraEntity();
		if (camera == null || mc.level == null || mc.player == null) {
			return;
		}
		Vec3 eye = camera.getEyePosition(partialTicks);
		Vec3 dir = camera.getViewVector(partialTicks);
		double reach = Math.max(mc.player.blockInteractionRange(), mc.player.entityInteractionRange());
		HitResult current = mc.hitResult;
		boolean currentIsBody = current instanceof EntityHitResult e && e.getEntity() instanceof RealisticBlockEntity;
		double limit = current == null || current.getType() == HitResult.Type.MISS || currentIsBody ? reach : eye.distanceTo(current.getLocation());
		AABB area = new AABB(eye, eye.add(dir.scale(reach))).inflate(1.0);
		RealisticHit best = null;
		for (RealisticBlockEntity e : mc.level.getEntitiesOfClass(RealisticBlockEntity.class, area)) {
			e.syncClientBody(e.renderPosition(partialTicks), e.renderRotation(partialTicks));
			RigidBody.Hit hit = e.body.rayCast(eye.x, eye.y, eye.z, dir.x, dir.y, dir.z, limit);
			if (hit != null) {
				limit = hit.distance();
				Vec3 at = eye.add(dir.scale(hit.distance()));
				best = new RealisticHit(e, at, hit.x(), hit.y(), hit.z(),
					RealisticBlockEntity.RAY_FACES[hit.face()].get3DDataValue(),
					e.body.toGrid(at.x, at.y, at.z, new org.joml.Vector3d()));
			}
		}
		if (best != null) {
			mc.hitResult = best;
			mc.crosshairPickEntity = best.getEntity();
		} else if (currentIsBody) {
			mc.hitResult = mc.player.pick(mc.player.blockInteractionRange(), partialTicks, false);
			mc.crosshairPickEntity = null;
		}
	}
}
