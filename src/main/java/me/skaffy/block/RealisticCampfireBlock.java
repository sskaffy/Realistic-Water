package me.skaffy.block;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Campfire with every vanilla behaviour (cooking, signal fire, dousing, damage, drops) and the vanilla block entity,
 * but no vanilla flame or smoke particles and a log-only model. The client fire renderer draws the fire instead.
 */
public class RealisticCampfireBlock extends CampfireBlock {
	public RealisticCampfireBlock(boolean spawnParticles, int fireDamage, Properties properties) {
		super(spawnParticles, fireDamage, properties);
	}

	@Override
	public void animateTick(final BlockState state, final Level level, final BlockPos pos, final RandomSource random) {
		if (state.getValue(LIT) && random.nextInt(10) == 0) {
			level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS,
				0.5F + random.nextFloat(), random.nextFloat() * 0.7F + 0.6F, false);
		}
	}

	/** The vanilla client ticker only spawns smoke particles for cooking food, so skip it on the client. */
	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
		return level.isClientSide() ? null : super.getTicker(level, state, type);
	}
}
