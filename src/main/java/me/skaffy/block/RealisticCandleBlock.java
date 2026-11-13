package me.skaffy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;

public class RealisticCandleBlock extends CandleBlock {
	public RealisticCandleBlock(Properties properties) {
		super(properties);
	}

	@Override
	public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random) {
		if (state.getValue(LIT) && random.nextFloat() < 0.17F) {
			level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.CANDLE_AMBIENT, SoundSource.BLOCKS,
				1.0F + random.nextFloat(), random.nextFloat() * 0.7F + 0.3F, false);
		}
	}
}
