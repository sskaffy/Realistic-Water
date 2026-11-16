package me.skaffy.block;

import net.minecraft.world.level.block.state.BlockState;

public final class PistonPassable {
	private PistonPassable() {
	}

	public static boolean is(BlockState state) {
		if (state.isAir() || state.getBlock() instanceof LooseLayerBlock || state.getBlock() instanceof CustomLiquidBlock) {
			return true;
		}
		return state.getBlock() instanceof RealisticWaterBlock && !state.getValue(RealisticWaterBlock.FROZEN);
	}
}
