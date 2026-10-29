package me.skaffy.client.realistic;

import me.skaffy.net.GridLevelEventPayload;
import me.skaffy.net.GridSoundPayload;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class GridEffects {
	private GridEffects() {
	}

	public static void sound(ClientLevel level, GridSoundPayload payload) {
		if (!(level.getEntity(payload.entityId()) instanceof RealisticBlockEntity body)) {
			return;
		}
		Vec3 at = body.cellToWorld(payload.x(), payload.y(), payload.z());
		SoundSource[] sources = SoundSource.values();
		SoundSource source = payload.source() >= 0 && payload.source() < sources.length ? sources[payload.source()] : SoundSource.BLOCKS;
		level.playLocalSound(at.x, at.y, at.z, payload.sound().value(), source, payload.volume(), payload.pitch(), false);
	}

	public static void levelEvent(ClientLevel level, GridLevelEventPayload payload) {
		if (!(level.getEntity(payload.entityId()) instanceof RealisticBlockEntity body)) {
			return;
		}
		Vec3 at = body.cellToWorld(payload.x(), payload.y(), payload.z());
		if (payload.event() == LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK || payload.event() == LevelEvent.PARTICLES_DESTROY_BLOCK) {
			BlockState state = Block.stateById(payload.data());
			if (state.isAir()) {
				return;
			}
			if (payload.event() == LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK) {
				SoundType soundType = state.getSoundType();
				level.playLocalSound(at.x, at.y, at.z, soundType.getBreakSound(), SoundSource.BLOCKS,
					(soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F, false);
			}
			destroyParticles(level, body, state, payload.x(), payload.y(), payload.z());
		}
	}

	private static void destroyParticles(ClientLevel level, RealisticBlockEntity body, BlockState state, float cx, float cy, float cz) {
		BlockParticleOption particle = new BlockParticleOption(ParticleTypes.BLOCK, state);
		int steps = 4;
		for (int ix = 0; ix < steps; ix++) {
			for (int iy = 0; iy < steps; iy++) {
				for (int iz = 0; iz < steps; iz++) {
					double ox = (ix + 0.5) / steps - 0.5;
					double oy = (iy + 0.5) / steps - 0.5;
					double oz = (iz + 0.5) / steps - 0.5;
					Vec3 point = body.cellToWorld(cx + ox, cy + oy, cz + oz);
					level.addParticle(particle, point.x, point.y, point.z, ox, oy, oz);
				}
			}
		}
	}
}
