package me.skaffy.net;

import me.skaffy.ModContent;
import net.minecraft.world.level.block.Block;

public final class SyncMaterial {
	public static final byte WATER = 0;
	public static final byte SAND = 1;
	public static final byte LAVA = 2;
	public static final byte SLIME = 3;
	public static final byte LEAVES = 4;
	public static final byte GRAVEL = 5;
	public static final byte CUSTOM = 6;
	public static final byte ALL_LIQUIDS = 7;

	public static boolean matches(byte material, net.minecraft.world.level.block.state.BlockState state) {
		if (me.skaffy.block.RealisticWaterBlock.isOcean(state)) {
			return false;
		}
		Block b = state.getBlock();
		return switch (material) {
			case CUSTOM -> b == ModContent.CUSTOM_LIQUID_BLOCK;
			case ALL_LIQUIDS -> b == ModContent.REALISTIC_WATER_BLOCK || b == ModContent.REALISTIC_LAVA_BLOCK || b == ModContent.REALISTIC_SLIME_BLOCK
				|| b == ModContent.CUSTOM_LIQUID_BLOCK;
			default -> b == block(material);
		};
	}

	private SyncMaterial() {
	}

	public static Block block(byte material) {
		return switch (material) {
			case SAND -> ModContent.REALISTIC_SAND;
			case LAVA -> ModContent.REALISTIC_LAVA_BLOCK;
			case SLIME -> ModContent.REALISTIC_SLIME_BLOCK;
			case LEAVES -> ModContent.REALISTIC_LEAVES;
			case GRAVEL -> ModContent.REALISTIC_GRAVEL;
			default -> ModContent.REALISTIC_WATER_BLOCK;
		};
	}

	public static String label(byte material) {
		return switch (material) {
			case SAND -> "sand";
			case LAVA -> "lava";
			case SLIME -> "slime";
			case LEAVES -> "leaves";
			case GRAVEL -> "gravel";
			case CUSTOM -> "custom liquid";
			case ALL_LIQUIDS -> "liquid";
			default -> "water";
		};
	}
}
