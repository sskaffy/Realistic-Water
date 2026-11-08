package me.skaffy.client.realistic;

import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

public final class BoatWaterMask {
	private BoatWaterMask() {
	}

	public static boolean needed(Level level, AABB hull) {
		double y = hull.minY + 0.15;
		double[][] points = {
			{(hull.minX + hull.maxX) * 0.5, (hull.minZ + hull.maxZ) * 0.5},
			{hull.minX + 0.2, hull.minZ + 0.2}, {hull.maxX - 0.2, hull.minZ + 0.2},
			{hull.minX + 0.2, hull.maxZ - 0.2}, {hull.maxX - 0.2, hull.maxZ - 0.2}};
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (double[] p : points) {
			pos.set(p[0], y, p[1]);
			BlockState state = level.getBlockState(pos);
			FluidState fluid = state.getFluidState();
			if (fluid.is(FluidTags.WATER) && (!RealisticWaterFluid.isRealistic(fluid) || RealisticWaterBlock.isOcean(state))) {
				return true;
			}
		}
		return false;
	}
}
