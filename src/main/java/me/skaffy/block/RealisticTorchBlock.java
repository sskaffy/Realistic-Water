package me.skaffy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/** A standing torch with the vanilla shape, light and placement rules but no vanilla flame or smoke particles. */
public class RealisticTorchBlock extends TorchBlock {
	public RealisticTorchBlock(SimpleParticleType particle, Properties properties) {
		super(particle, properties);
	}

	@Override
	public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random) {
	}
}
