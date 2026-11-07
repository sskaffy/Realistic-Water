package me.skaffy.client.water;

import me.skaffy.net.SyncMaterial;

public enum Material {
	WATER,
	SAND,
	LAVA,
	SLIME,
	LEAVES,
	STEAM,
	SNOW;

	float shaderId() {
		return switch (this) {
			case SAND, SNOW -> 1.0F;
			case LAVA -> 2.0F;
			case SLIME -> 3.0F;
			case LEAVES -> 4.0F;
			case STEAM -> 5.0F;
			default -> 0.0F;
		};
	}

	boolean isGranular() {
		return this == SAND || this == SNOW;
	}

	boolean isFluidSim() {
		return this == WATER || this == LAVA || this == SLIME;
	}

	boolean isLeaves() {
		return this == LEAVES;
	}

	boolean isGas() {
		return this == STEAM;
	}

	boolean isParticleOnly() {
		return this == LEAVES || this == STEAM;
	}

	boolean syncs() {
		return this != STEAM;
	}

	boolean usesLayers() {
		return this == SAND || this == SNOW || this == LEAVES;
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
			case SNOW -> SyncMaterial.SNOW;
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
			case SNOW -> "snow";
			default -> "water";
		};
	}
}
