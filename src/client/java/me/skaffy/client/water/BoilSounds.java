package me.skaffy.client.water;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.ModContent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

final class BoilSounds {
	private static final double HEAR_RANGE = 24.0;
	private static final int MAX_SPOTS = 6;
	private static final float BOIL_FULL = 0.05F;
	private static final float SIMMER_FULL = 0.06F;
	private static final float SIMMER_PITCH = 1.35F;
	private static final float SIMMER_VOLUME = 0.6F;
	private static final float KEEP = 0.9F;

	private static final class Loop extends AbstractTickableSoundInstance {
		Loop(Vec3 at, float pitch, float volume) {
			super(ModContent.BOILING, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
			this.looping = true;
			this.delay = 0;
			this.volume = volume;
			this.pitch = pitch;
			this.moveTo(at);
		}

		void moveTo(Vec3 at) {
			this.x = at.x;
			this.y = at.y;
			this.z = at.z;
		}

		void setVolume(float volume) {
			this.volume = volume;
		}

		void end() {
			this.stop();
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
		}
	}

	private static final class Spot {
		double sx;
		double sy;
		double sz;
		double weight;
		float boilCount;
		float simmerCount;
		float boil;
		float simmer;
		Loop boilLoop;
		Loop simmerLoop;

		Vec3 centre() {
			return new Vec3(this.sx / this.weight, this.sy / this.weight, this.sz / this.weight);
		}

		void stop() {
			if (this.boilLoop != null) {
				this.boilLoop.end();
				this.boilLoop = null;
			}
			if (this.simmerLoop != null) {
				this.simmerLoop.end();
				this.simmerLoop = null;
			}
		}
	}

	private final Long2ObjectOpenHashMap<Spot> spots = new Long2ObjectOpenHashMap<>();

	void add(long block, float boiled, float simmering) {
		int x = BlockPos.getX(block);
		int y = BlockPos.getY(block);
		int z = BlockPos.getZ(block);
		Spot spot = this.spots.computeIfAbsent(BlockPos.asLong(x >> 2, y >> 2, z >> 2), k -> new Spot());
		double w = boiled + simmering;
		spot.sx += (x + 0.5) * w;
		spot.sy += (y + 0.5) * w;
		spot.sz += (z + 0.5) * w;
		spot.weight += w;
		spot.boilCount += boiled;
		spot.simmerCount += simmering;
	}

	void tick(Minecraft mc) {
		if (this.spots.isEmpty()) {
			return;
		}
		Vec3 listener = mc.player != null ? mc.player.getEyePosition() : null;
		List<Spot> audible = new ArrayList<>();
		this.spots.values().removeIf(spot -> {
			spot.boil = spot.boil * KEEP + spot.boilCount * 20.0F * (1.0F - KEEP);
			spot.simmer = spot.simmer * KEEP + spot.simmerCount * 20.0F * (1.0F - KEEP);
			spot.boilCount = 0.0F;
			spot.simmerCount = 0.0F;
			if (spot.boil < BOIL_FULL * 0.01F && spot.simmer < SIMMER_FULL * 0.01F) {
				spot.stop();
				return true;
			}
			if (listener != null && spot.weight > 0.0 && spot.centre().distanceToSqr(listener) < HEAR_RANGE * HEAR_RANGE && WaterSettings.boilingSounds) {
				audible.add(spot);
			} else {
				spot.stop();
			}
			return false;
		});
		if (listener != null) {
			audible.sort((a, b) -> Double.compare(a.centre().distanceToSqr(listener), b.centre().distanceToSqr(listener)));
		}
		float volume = WaterSettings.flowSoundVolume * Math.max(WaterSettings.boilingSoundVolume, 0.0F);
		for (int k = 0; k < audible.size(); k++) {
			Spot spot = audible.get(k);
			if (k >= MAX_SPOTS) {
				spot.stop();
				continue;
			}
			Vec3 at = spot.centre();
			spot.boilLoop = this.drive(mc, spot.boilLoop, at, 0.95F + 0.1F * (k % 3) / 2.0F, Math.min(spot.boil / BOIL_FULL, 1.0F) * volume);
			spot.simmerLoop = this.drive(mc, spot.simmerLoop, at, SIMMER_PITCH, Math.min(spot.simmer / SIMMER_FULL, 1.0F) * SIMMER_VOLUME * volume);
			spot.sx *= KEEP;
			spot.sy *= KEEP;
			spot.sz *= KEEP;
			spot.weight *= KEEP;
		}
	}

	private Loop drive(Minecraft mc, Loop loop, Vec3 at, float pitch, float volume) {
		if (volume < 0.01F) {
			if (loop != null) {
				loop.end();
			}
			return null;
		}
		if (loop == null || loop.isStopped()) {
			loop = new Loop(at, pitch, Math.min(volume, 1.0F));
			mc.getSoundManager().play(loop);
		}
		loop.moveTo(at);
		loop.setVolume(Math.min(volume, 1.0F));
		return loop;
	}

	void clear() {
		for (Spot spot : this.spots.values()) {
			spot.stop();
		}
		this.spots.clear();
	}
}
