package me.skaffy.client.water;

import me.skaffy.net.SyncMaterial;

public enum Material {
	WATER,
	SAND,
	LAVA,
	SLIME,
	LEAVES,
	STEAM,
	SMOKE,
	FLAME,
	SOUL_FLAME,
	SPARK;

	float shaderId() {
		return switch (this) {
			case SAND -> 1.0F;
			case LAVA -> 2.0F;
			case SLIME -> 3.0F;
			case LEAVES -> 4.0F;
			case STEAM -> 5.0F;
			case SMOKE -> 6.0F;
			case FLAME -> 7.0F;
			case SOUL_FLAME -> 8.0F;
			case SPARK -> 9.0F;
			default -> 0.0F;
		};
	}

	boolean isGranular() {
		return this == SAND;
	}

	boolean isFluidSim() {
		return this == WATER || this == LAVA || this == SLIME;
	}

	boolean isLeaves() {
		return this == LEAVES;
	}

	boolean isGas() {
		return this == STEAM || this == SMOKE || this == FLAME || this == SOUL_FLAME || this == SPARK;
	}

	boolean isParticleOnly() {
		return this == LEAVES || this.isGas();
	}

	boolean syncs() {
		return !this.isGas();
	}

	boolean usesLayers() {
		return this == SAND || this == LEAVES;
	}

	boolean usesWhitewater() {
		return this == WATER;
	}

	byte syncId() {
		return switch (this) {
			case SAND -> SyncMaterial.SAND;
			case LAVA -> SyncMaterial.LAVA;
			case SLIME -> SyncMaterial.SLIME;
			case LEAVES -> SyncMaterial.LEAVES;
			default -> SyncMaterial.WATER;
		};
	}

	String label() {
		return switch (this) {
			case SAND -> "sand";
			case LAVA -> "lava";
			case SLIME -> "slime";
			case LEAVES -> "leaves";
			case STEAM -> "steam";
			case SMOKE -> "smoke";
			case FLAME -> "flame";
			case SOUL_FLAME -> "soul flame";
			case SPARK -> "spark";
			default -> "water";
		};
	}
}
