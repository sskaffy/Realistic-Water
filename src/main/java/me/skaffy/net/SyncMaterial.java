package me.skaffy.net;

import me.skaffy.ModContent;
import net.minecraft.world.level.block.Block;

public final class SyncMaterial {
	public static final byte WATER = 0;
	public static final byte SAND = 1;
	public static final byte LAVA = 2;
	public static final byte SLIME = 3;
	public static final byte LEAVES = 4;
	public static final byte SNOW = 5;

	private SyncMaterial() {
	}

	public static Block block(byte material) {
		return switch (material) {
			case SAND -> ModContent.REALISTIC_SAND;
			case LAVA -> ModContent.REALISTIC_LAVA_BLOCK;
			case SLIME -> ModContent.REALISTIC_SLIME_BLOCK;
			case LEAVES -> ModContent.REALISTIC_LEAVES;
			case SNOW -> ModContent.REALISTIC_SNOW;
			default -> ModContent.REALISTIC_WATER_BLOCK;
		};
	}

	public static String label(byte material) {
		return switch (material) {
			case SAND -> "sand";
			case LAVA -> "lava";
			case SLIME -> "slime";
			case LEAVES -> "leaves";
			case SNOW -> "snow";
			default -> "water";
		};
	}
}
