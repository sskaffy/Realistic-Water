package me.skaffy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/** The wall-mounted version of {@link RealisticTorchBlock}. */
public class RealisticWallTorchBlock extends WallTorchBlock {
	public RealisticWallTorchBlock(SimpleParticleType particle, Properties properties) {
		super(particle, properties);
	}

	@Override
	public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random) {
	}
}
