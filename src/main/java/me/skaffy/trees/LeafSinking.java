package me.skaffy.trees;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

public final class LeafSinking {
	private static final Set<UUID> sinking = ConcurrentHashMap.newKeySet();
	public static volatile boolean localOn;
	public static volatile float localTop = 0.8F;
	public static final float LOCAL_SIDE = 0.12F;
	static final float SERVER_TOP = 0.8F;
	static final float SERVER_SIDE = LOCAL_SIDE;
	private static final VoxelShape[] CACHE = new VoxelShape[64 * 64 * 2];

	private LeafSinking() {
	}

	public static void set(Player player, boolean on) {
		if (on) {
			sinking.add(player.getUUID());
		} else {
			sinking.remove(player.getUUID());
		}
	}

	public static void forget(Player player) {
		sinking.remove(player.getUUID());
	}

	public static @Nullable VoxelShape shape(BlockGetter level, BlockPos pos, @Nullable Entity entity) {
		if (!(entity instanceof Player player)) {
			return null;
		}
		float top;
		float side;
		if (player.level().isClientSide()) {
			if (!localOn) {
				return null;
			}
			top = localTop;
			side = LOCAL_SIDE;
		} else {
			if (!sinking.contains(player.getUUID())) {
				return null;
			}
			top = SERVER_TOP;
			side = SERVER_SIDE;
		}
		int open = 0;
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.values()) {
			if (d != Direction.DOWN && level.getBlockState(n.setWithOffset(pos, d)).isAir()) {
				open |= 1 << d.ordinal();
			}
		}
		if (open == 0) {
			return null;
		}
		int step = Math.clamp(Math.round(top * 63.0F), 0, 63);
		boolean server = !player.level().isClientSide();
		int key = open | step << 6 | (server ? 1 : 0) << 12;
		VoxelShape cached = CACHE[key];
		if (cached != null) {
			return cached;
		}
		double t = step / 63.0;
		double x0 = (open & 1 << Direction.WEST.ordinal()) != 0 ? side : 0.0;
		double x1 = (open & 1 << Direction.EAST.ordinal()) != 0 ? 1.0 - side : 1.0;
		double z0 = (open & 1 << Direction.NORTH.ordinal()) != 0 ? side : 0.0;
		double z1 = (open & 1 << Direction.SOUTH.ordinal()) != 0 ? 1.0 - side : 1.0;
		double y1 = (open & 1 << Direction.UP.ordinal()) != 0 ? t : 1.0;
		VoxelShape shape = Shapes.box(x0, 0.0, z0, x1, y1, z1);
		CACHE[key] = shape;
		return shape;
	}
}
