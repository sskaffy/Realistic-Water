package me.skaffy.client.realistic;

import java.util.ArrayList;
import java.util.List;
import me.skaffy.physics.SweptCollider;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public final class RealisticPlatform {
	private static final double STEP = 0.6;
	private static @Nullable RealisticBlockEntity under;
	private static final Vector3d anchorGrid = new Vector3d();
	private static final Vector3d anchorWorld = new Vector3d();

	private RealisticPlatform() {
	}

	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.isPaused() || player.isSpectator() || player.getAbilities().flying || player.isPassenger()) {
			under = null;
			return;
		}
		if (under != null && !under.isRemoved()) {
			sync(under);
			Vector3d now = under.body.toWorld(anchorGrid.x, anchorGrid.y, anchorGrid.z, new Vector3d());
			double dx = now.x - anchorWorld.x;
			double dy = now.y - anchorWorld.y;
			double dz = now.z - anchorWorld.z;
			if (dx * dx + dy * dy + dz * dz < 1.0) {
				player.setPos(player.getX() + dx, player.getY() + dy, player.getZ() + dz);
			}
		}
		pushOut(player, mc);

		under = null;
		AABB box = player.getBoundingBox();
		for (RealisticBlockEntity e : mc.level.getEntitiesOfClass(RealisticBlockEntity.class, box.expandTowards(0.0, -0.3, 0.0))) {
			sync(e);
			if (Math.abs(topUnder(e, box) - box.minY) < 0.08) {
				under = e;
				Vec3 feet = player.position();
				anchorWorld.set(feet.x, feet.y, feet.z);
				e.body.toGrid(feet.x, feet.y, feet.z, anchorGrid);
				break;
			}
		}
	}

	private static void sync(RealisticBlockEntity e) {
		e.syncClientBody(e.position(), e.renderRotation(1.0F));
	}

	private static void pushOut(LocalPlayer player, Minecraft mc) {
		AABB box = player.getBoundingBox().deflate(0.001);
		List<SweptCollider.Oriented> boxes = new ArrayList<>();
		for (RealisticBlockEntity e : mc.level.getEntitiesOfClass(RealisticBlockEntity.class, box)) {
			sync(e);
			boxes.addAll(e.orientedBoxes(box));
		}
		if (boxes.isEmpty()) {
			return;
		}
		double[] bounds = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
		double[] out = SweptCollider.pushOut(bounds, boxes);
		if (out == null || out[0] * out[0] + out[1] * out[1] + out[2] * out[2] > 4.0) {
			return;
		}
		player.setPos(player.getX() + out[0], player.getY() + out[1], player.getZ() + out[2]);
		if (out[1] > 1.0E-4) {
			Vec3 v = player.getDeltaMovement();
			if (v.y < 0.0) {
				player.setDeltaMovement(v.x, 0.0, v.z);
			}
			player.setOnGround(true);
		}
	}

	private static double topUnder(RealisticBlockEntity e, AABB box) {
		AABB column = new AABB(box.minX + 0.05, box.minY - 0.6, box.minZ + 0.05, box.maxX - 0.05, box.minY + STEP, box.maxZ - 0.05);
		double top = Double.NEGATIVE_INFINITY;
		for (SweptCollider.Oriented shape : e.orientedBoxes(column)) {
			double[] b = shape.bounds();
			if (b[4] <= box.minY + STEP) {
				top = Math.max(top, b[4]);
			}
		}
		return top;
	}
}
