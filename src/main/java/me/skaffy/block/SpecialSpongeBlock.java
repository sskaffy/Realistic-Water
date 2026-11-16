package me.skaffy.block;

import me.skaffy.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class SpecialSpongeBlock extends Block {
	public static final int RADIUS = 7;
	private static final int INTERVAL = 10;
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	public SpecialSpongeBlock(Properties properties) {
		super(properties);
	}

	public static boolean absorbs(BlockState state) {
		Block block = state.getBlock();
		if (block == ModContent.REALISTIC_WATER_BLOCK) {
			return !RealisticWaterBlock.isOcean(state) && !RealisticWaterBlock.isFrozen(state);
		}
		return block == ModContent.REALISTIC_LAVA_BLOCK || block == ModContent.REALISTIC_SLIME_BLOCK || block == ModContent.CUSTOM_LIQUID_BLOCK;
	}

	@Override
	protected void onPlace(final BlockState state, final Level level, final BlockPos pos, final BlockState oldState, final boolean movedByPiston) {
		if (!oldState.is(state.getBlock())) {
			level.scheduleTick(pos, this, 1);
		}
	}

	@Override
	protected void tick(final BlockState state, final ServerLevel level, final BlockPos pos, final RandomSource random) {
		int taken = absorb(level, pos);
		if (taken > 0) {
			level.playSound(null, pos, SoundEvents.SPONGE_ABSORB, SoundSource.BLOCKS, Math.min(0.4F + taken * 0.02F, 1.0F), 0.9F + random.nextFloat() * 0.2F);
			level.sendParticles(ParticleTypes.SPLASH, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, Math.min(4 + taken, 24), 0.4, 0.2, 0.4, 0.0);
		}
		level.scheduleTick(pos, this, INTERVAL);
	}

	private static int absorb(ServerLevel level, BlockPos center) {
		int taken = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int dy = -RADIUS; dy <= RADIUS; dy++) {
			int y = center.getY() + dy;
			if (y < level.getMinY() || y > level.getMaxY()) {
				continue;
			}
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				for (int dx = -RADIUS; dx <= RADIUS; dx++) {
					p.set(center.getX() + dx, y, center.getZ() + dz);
					if (level.isLoaded(p) && absorbs(level.getBlockState(p))) {
						level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
						taken++;
					}
				}
			}
		}
		return taken;
	}
}
