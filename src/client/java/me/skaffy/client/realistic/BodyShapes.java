package me.skaffy.client.realistic;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class BodyShapes {
	public static final int MAX_BOXES = 6;
	private static final Map<BlockState, double[][]> CACHE = new ConcurrentHashMap<>();

	private BodyShapes() {
	}

	public static double[][] boxes(BlockState state) {
		return CACHE.computeIfAbsent(state, s -> {
			List<AABB> parts = s.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
			if (parts.isEmpty()) {
				return new double[0][];
			}
			if (parts.size() > MAX_BOXES) {
				AABB all = parts.get(0);
				for (AABB a : parts) {
					all = all.minmax(a);
				}
				parts = List.of(all);
			}
			double[][] out = new double[parts.size()][];
			for (int i = 0; i < out.length; i++) {
				AABB a = parts.get(i);
				out[i] = new double[]{a.minX, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ};
			}
			return out;
		});
	}
}
