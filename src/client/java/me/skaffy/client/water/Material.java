package me.skaffy.client.water;

import me.skaffy.ModContent;
import me.skaffy.block.LooseLayerBlock;
import me.skaffy.net.SyncMaterial;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public enum Material {
	WATER,
	SAND,
	LAVA,
	SLIME,
	LEAVES,
	STEAM,
	GRAVEL,
	SMOKE,
	FLAME,
	SOUL_FLAME,
	SPARK;

	float shaderId() {
		return switch (this) {
			case SAND, GRAVEL -> 1.0F;
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
		return this == SAND || this == GRAVEL;
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

	boolean isFireGas() {
		return this == SMOKE || this == FLAME || this == SOUL_FLAME || this == SPARK;
	}

	boolean isParticleOnly() {
		return this == LEAVES || this.isGas();
	}

	boolean syncs() {
		return !this.isGas();
	}

	boolean usesLayers() {
		return this.isGranular() || this == LEAVES;
	}

	boolean usesWhitewater() {
		return this == WATER;
	}

	@Nullable Block layerBlock() {
		return switch (this) {
			case SAND -> ModContent.REALISTIC_SAND;
			case GRAVEL -> ModContent.REALISTIC_GRAVEL;
			case LEAVES -> ModContent.REALISTIC_LEAVES;
			default -> null;
		};
	}

	int layers(BlockState state) {
		Block block = this.layerBlock();
		return block != null && state.getBlock() == block ? state.getValue(LooseLayerBlock.LAYERS) : 0;
	}

	byte syncId() {
		return switch (this) {
			case SAND -> SyncMaterial.SAND;
			case LAVA -> SyncMaterial.LAVA;
			case SLIME -> SyncMaterial.SLIME;
			case LEAVES -> SyncMaterial.LEAVES;
			case GRAVEL -> SyncMaterial.GRAVEL;
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
			case GRAVEL -> "gravel";
			case SMOKE -> "smoke";
			case FLAME -> "flame";
			case SOUL_FLAME -> "soul flame";
			case SPARK -> "spark";
			default -> "water";
		};
	}
}
