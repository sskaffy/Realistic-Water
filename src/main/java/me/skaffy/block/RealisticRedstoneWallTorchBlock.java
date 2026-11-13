package me.skaffy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

public class RealisticRedstoneWallTorchBlock extends RedstoneWallTorchBlock {
	public RealisticRedstoneWallTorchBlock(Properties properties) {
		super(properties);
	}

	@Override
	public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random) {
	}
}
