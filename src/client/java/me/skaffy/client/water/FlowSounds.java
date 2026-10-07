package me.skaffy.client.water;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.phys.Vec3;

final class FlowSounds {
	private static final double HEAR_RANGE = 24.0;
	private static final int SPREAD_TICKS = 10;

	private record Pending(int tick, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
	}

	private final List<Pending> pending = new ArrayList<>();
	private final RandomSource random = RandomSource.create();
	private int tick;

	void onFill(Material material, WaterRegion.Fill f, Vec3 listener) {
		boolean water = material == Material.WATER;
		if (water ? !WaterSettings.flowSounds : !WaterSettings.sandSounds) {
			return;
		}
		float moving = water ? 0.4F : 0.3F;
		float fast = water ? 4.0F : 2.5F;
		List<int[]> candidates = new ArrayList<>();
		List<Float> speeds = new ArrayList<>();
		float weight = 0.0F;
		float fastWeight = 0.0F;
		int i = 0;
		for (int z = 0; z < f.dz(); z++) {
			for (int y = 0; y < f.dy(); y++) {
				for (int x = 0; x < f.dx(); x++, i++) {
					float speed = f.blocksPerSecond(i);
					if (speed < moving || f.fullness(i) < 0.06F) {
						continue;
					}
					double bx = f.x() + x + 0.5;
					double by = f.y() + y + 0.5;
					double bz = f.z() + z + 0.5;
					if (listener.distanceToSqr(bx, by, bz) > HEAR_RANGE * HEAR_RANGE) {
						continue;
					}
					candidates.add(new int[]{f.x() + x, f.y() + y, f.z() + z});
					speeds.add(speed);
					weight += Math.min(speed / 1.5F, 2.0F);
					if (speed >= fast) {
						fastWeight += 1.0F;
					}
				}
			}
		}
		if (candidates.isEmpty()) {
			return;
		}
		float volumeScale = WaterSettings.flowSoundVolume;
		int count = this.roll(Math.min(weight * (water ? 0.012F : 0.01F), water ? 3.0F : 2.0F));
		for (int k = 0; k < count; k++) {
			int pick = this.random.nextInt(candidates.size());
			int[] b = candidates.get(pick);
			float speed = speeds.get(pick);
			float loud = Math.min(speed / 1.5F, 1.0F);
			if (water) {
				this.schedule(b, SoundEvents.WATER_AMBIENT, (0.45F + 0.45F * loud) * volumeScale, this.random.nextFloat() + 0.5F);
			} else {
				SoundEvent sound = this.random.nextBoolean() ? SoundType.SAND.getStepSound() : SoundType.SAND.getHitSound();
				this.schedule(b, sound, (0.12F + 0.25F * loud) * volumeScale, 0.7F + this.random.nextFloat() * 0.5F);
			}
		}
		int impacts = this.roll(Math.min(fastWeight * 0.03F, 1.0F));
		for (int k = 0; k < impacts; k++) {
			int[] b = candidates.get(this.random.nextInt(candidates.size()));
			if (water) {
				this.schedule(b, SoundEvents.GENERIC_SPLASH, 0.25F * volumeScale, 0.8F + this.random.nextFloat() * 0.4F);
			} else {
				this.schedule(b, SoundType.SAND.getFallSound(), 0.35F * volumeScale, 0.8F + this.random.nextFloat() * 0.3F);
			}
		}
	}

	private int roll(float expected) {
		int n = (int) expected;
		return n + (this.random.nextFloat() < expected - n ? 1 : 0);
	}

	private void schedule(int[] b, SoundEvent sound, float volume, float pitch) {
		double x = b[0] + this.random.nextDouble();
		double y = b[1] + this.random.nextDouble();
		double z = b[2] + this.random.nextDouble();
		this.pending.add(new Pending(this.tick + this.random.nextInt(SPREAD_TICKS), x, y, z, sound, volume, pitch));
	}

	void tick(ClientLevel level) {
		this.tick++;
		this.pending.removeIf(p -> {
			if (p.tick() > this.tick) {
				return false;
			}
			level.playLocalSound(p.x(), p.y(), p.z(), p.sound(), p.sound() == SoundEvents.WATER_AMBIENT ? SoundSource.AMBIENT : SoundSource.BLOCKS,
				p.volume(), p.pitch(), false);
			return true;
		});
	}

	void clear() {
		this.pending.clear();
	}
}
