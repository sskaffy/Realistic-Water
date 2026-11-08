package me.skaffy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

public final class WobblyBlocks {
	public static final BooleanProperty HANGING = BooleanProperty.create("hanging");
	private static final Direction[] HOLDS = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

	private WobblyBlocks() {
	}

	public static final float SLIME_PRESS = 0.18F;
	public static final float SLIME_MAX = 0.4F;
	public static final float HONEY_PRESS = 0.12F;
	public static final float HONEY_MAX = 0.22F;
	private static final net.minecraft.world.phys.shapes.VoxelShape[] SUNK = new net.minecraft.world.phys.shapes.VoxelShape[2 * 64 * 64];

	public static double topDent(net.minecraft.world.entity.Entity e, boolean honey) {
		net.minecraft.world.phys.AABB box = e.getBoundingBox();
		return topDent(box.getXsize() * box.getYsize() * box.getZsize(), honey);
	}

	public static double topDent(double volume, boolean honey) {
		double weight = Math.clamp(volume / 0.648, 0.1, 3.0);
		return Math.min((honey ? HONEY_PRESS : SLIME_PRESS) * Math.sqrt(weight), honey ? HONEY_MAX : SLIME_MAX);
	}

	public static int gelKind(BlockState state) {
		return state.getBlock() instanceof WobblyHoneyBlock ? 1 : state.getBlock() instanceof WobblySlimeBlock ? 0 : -1;
	}

	public static net.minecraft.world.phys.shapes.@org.jspecify.annotations.Nullable VoxelShape sunk(
		net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state, net.minecraft.world.phys.shapes.CollisionContext context, boolean honey) {
		if (!(context instanceof net.minecraft.world.phys.shapes.EntityCollisionContext ec)) {
			return null;
		}
		net.minecraft.world.entity.Entity e = ec.getEntity();
		if (e == null || e instanceof FallingBlockEntity) {
			return null;
		}
		return sunk(level, pos, state, topDent(e, honey), honey);
	}

	public static net.minecraft.world.phys.shapes.@org.jspecify.annotations.Nullable VoxelShape sunk(
		net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state, double topDent, boolean honey) {
		int step = (int) Math.round(topDent * 63.0 / (honey ? HONEY_MAX : SLIME_MAX));
		if (step <= 0) {
			return null;
		}
		int open = 0;
		BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.values()) {
			if (d != Direction.DOWN && level.getBlockState(n.setWithOffset(pos, d)).getBlock() != state.getBlock()) {
				open |= 1 << d.ordinal();
			}
		}
		if (open == 0) {
			return null;
		}
		int key = (honey ? 1 : 0) << 12 | step << 6 | (open >> 1 & 63);
		net.minecraft.world.phys.shapes.VoxelShape cached = SUNK[key];
		if (cached != null) {
			return cached;
		}
		double dent = step / 63.0 * (honey ? HONEY_MAX : SLIME_MAX);
		double inset = honey ? 1.0 / 16.0 : 0.0;
		double top = honey ? 15.0 / 16.0 : 1.0;
		double side = dent * 0.5;
		double x0 = inset + ((open & 1 << Direction.WEST.ordinal()) != 0 ? side : 0.0);
		double x1 = 1.0 - inset - ((open & 1 << Direction.EAST.ordinal()) != 0 ? side : 0.0);
		double z0 = inset + ((open & 1 << Direction.NORTH.ordinal()) != 0 ? side : 0.0);
		double z1 = 1.0 - inset - ((open & 1 << Direction.SOUTH.ordinal()) != 0 ? side : 0.0);
		double y1 = (open & 1 << Direction.UP.ordinal()) != 0 ? top - dent : top;
		net.minecraft.world.phys.shapes.VoxelShape shape = net.minecraft.world.phys.shapes.Shapes.box(x0, 0.0, z0, x1, y1, z1);
		SUNK[key] = shape;
		return shape;
	}

	public static boolean isWobbly(BlockState state) {
		return state.getBlock() instanceof WobblySlimeBlock || state.getBlock() instanceof WobblyHoneyBlock;
	}

	private static boolean heldBy(BlockGetter level, BlockPos pos, Direction toward) {
		BlockPos at = pos.relative(toward);
		return level.getBlockState(at).isFaceSturdy(level, at, toward.getOpposite());
	}

	static boolean hangs(BlockGetter level, BlockPos pos) {
		for (Direction d : HOLDS) {
			if (heldBy(level, pos, d)) {
				return true;
			}
		}
		return false;
	}

	static void settle(ServerLevel level, BlockPos pos, BlockState state) {
		if (me.skaffy.realistic.GridSpace.isGridPos(pos)) {
			return;
		}
		boolean free = FallingBlock.isFree(level.getBlockState(pos.below())) && pos.getY() >= level.getMinY();
		boolean hanging = free && hangs(level, pos);
		if (state.getValue(HANGING) != hanging) {
			state = state.setValue(HANGING, hanging);
			level.setBlock(pos, state, 2);
		}
		if (free && !hanging) {
			FallingBlockEntity.fall(level, pos, state.setValue(HANGING, false));
		}
	}
}
