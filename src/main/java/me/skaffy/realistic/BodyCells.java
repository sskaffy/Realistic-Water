package me.skaffy.realistic;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public final class BodyCells {
	private BodyCells() {
	}

	private static @Nullable BlockState at(Entity e, double x, double y, double z) {
		AABB probe = new AABB(x - 0.01, y - 0.01, z - 0.01, x + 0.01, y + 0.01, z + 0.01);
		Vector3d g = new Vector3d();
		for (RealisticBlockEntity body : RealisticBlockEntity.near(e.level(), probe.inflate(1.0))) {
			if (body == e) {
				continue;
			}
			body.body.toGrid(x, y, z, g);
			var cell = body.body.cellAt(Mth.floor(g.x), Mth.floor(g.y), Mth.floor(g.z));
			if (cell != null && cell.payload instanceof BlockState state) {
				return state;
			}
		}
		return null;
	}

	public static @Nullable BlockState under(Entity e) {
		return under(e, 0.0);
	}

	public static @Nullable BlockState under(Entity e, double dy) {
		AABB box = e.getBoundingBox().move(0.0, dy, 0.0);
		double y = box.minY - 0.05;
		double cx = (box.minX + box.maxX) * 0.5;
		double cz = (box.minZ + box.maxZ) * 0.5;
		BlockState s = at(e, cx, y, cz);
		if (s != null) {
			return s;
		}
		double in = 0.05;
		for (int k = 0; k < 4; k++) {
			s = at(e, (k & 1) == 0 ? box.minX + in : box.maxX - in, y, (k & 2) == 0 ? box.minZ + in : box.maxZ - in);
			if (s != null) {
				return s;
			}
		}
		return null;
	}

	public static @Nullable BlockState beside(Entity e) {
		AABB box = e.getBoundingBox();
		double y = (box.minY + box.maxY) * 0.5;
		double cx = (box.minX + box.maxX) * 0.5;
		double cz = (box.minZ + box.maxZ) * 0.5;
		double[][] probes = {{box.maxX + 0.05, cz}, {box.minX - 0.05, cz}, {cx, box.maxZ + 0.05}, {cx, box.minZ - 0.05}};
		for (double[] p : probes) {
			BlockState s = at(e, p[0], y, p[1]);
			if (s != null) {
				return s;
			}
		}
		return null;
	}
}
