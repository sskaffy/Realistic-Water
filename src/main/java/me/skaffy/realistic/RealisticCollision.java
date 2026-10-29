package me.skaffy.realistic;

import java.util.ArrayList;
import java.util.List;
import me.skaffy.physics.SweptCollider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class RealisticCollision {
	public static final double MAX_SLOPE = 45.0;

	private RealisticCollision() {
	}

	public static final boolean DEBUG = false;
	private static long lastLog;

	private static void debug(Entity entity, String what) {
		if (!DEBUG || !(entity instanceof net.minecraft.world.entity.player.Player) || !entity.level().isClientSide()) {
			return;
		}
		long now = entity.level().getGameTime();
		if (now - lastLog < 20L) {
			return;
		}
		lastLog = now;
		me.skaffy.RealisticWater.LOGGER.info("[collide] {}", what);
	}

	private static String describe(Entity entity, AABB area) {
		var near = entity.level().getEntitiesOfClass(RealisticBlockEntity.class, area.inflate(8.0));
		if (near.isEmpty()) {
			return "no bodies within 8 blocks of " + entity.blockPosition();
		}
		RealisticBlockEntity body = near.get(0);
		return String.format("bodies=%d first: cells=%d entity=(%.2f,%.2f,%.2f) body.pos=(%.2f,%.2f,%.2f) aabb=(%.2f..%.2f y) sim=%s owner=%d player=(%.2f,%.2f,%.2f)",
			near.size(), body.body.cells.size(), body.getX(), body.getY(), body.getZ(),
			body.body.pos.x, body.body.pos.y, body.body.pos.z, body.body.minY, body.body.maxY,
			body.isSimulated(), body.owner(), entity.getX(), entity.getY(), entity.getZ());
	}

	public static boolean supportedBy(Entity entity, double dx, double dz, double depth) {
		AABB box = entity.getBoundingBox().move(dx, 0.0, dz);
		AABB area = box.expandTowards(0.0, -depth, 0.0).inflate(0.5);
		List<SweptCollider.Oriented> boxes = gather(entity, area);
		if (boxes == null) {
			return false;
		}
		double[] bounds = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
		return SweptCollider.allowedAlong(bounds, 1, -depth, boxes, MAX_SLOPE) > -depth + 1.0E-9;
	}

	private static @Nullable List<SweptCollider.Oriented> gather(Entity entity, AABB area) {
		List<SweptCollider.Oriented> boxes = null;
		for (RealisticBlockEntity body : entity.level().getEntitiesOfClass(RealisticBlockEntity.class, area)) {
			List<SweptCollider.Oriented> some = body.orientedBoxes(area);
			if (some.isEmpty()) {
				continue;
			}
			if (boxes == null) {
				boxes = new ArrayList<>(some);
			} else {
				boxes.addAll(some);
			}
		}
		return boxes;
	}

	public static Vec3 adjust(Entity entity, Vec3 movement) {
		if (entity instanceof RealisticBlockEntity || entity.noPhysics || entity.isSpectator()) {
			debug(entity, "skipped: body/noPhysics/spectator");
			return movement;
		}
		if (entity instanceof ServerPlayer) {
			return movement;
		}
		double step = entity.maxUpStep();
		AABB box = entity.getBoundingBox();
		AABB area = box.expandTowards(movement).inflate(0.5 + Math.max(step, 0.0));
		List<SweptCollider.Oriented> boxes = gather(entity, area);
		if (boxes == null) {
			debug(entity, "NO BOXES. " + describe(entity, area));
			return movement;
		}
		double[] bounds = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
		SweptCollider.Result result = SweptCollider.resolve(bounds, movement.x, movement.y, movement.z, boxes, step, MAX_SLOPE);
		debug(entity, String.format("boxes=%d in=(%.4f,%.4f,%.4f) out=(%.4f,%.4f,%.4f) escape=(%.4f,%.4f,%.4f) grounded=%s | %s",
			boxes.size(), movement.x, movement.y, movement.z, result.x(), result.y(), result.z(),
			result.ex(), result.ey(), result.ez(), result.grounded(), describe(entity, area)));
		if (result.ex() != 0.0 || result.ey() != 0.0 || result.ez() != 0.0) {
			entity.setPos(entity.getX() + result.ex(), entity.getY() + result.ey(), entity.getZ() + result.ez());
		}
		double y = movement.y > 0.0 ? Math.min(result.y(), movement.y) : result.y();
		return new Vec3(result.x(), y, result.z());
	}
}
