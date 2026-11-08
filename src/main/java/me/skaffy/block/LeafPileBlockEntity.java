package me.skaffy.block;

import java.util.Arrays;
import me.skaffy.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

public class LeafPileBlockEntity extends BlockEntity {
	private String looks = "";
	private int[] leaves = new int[0];

	public LeafPileBlockEntity(BlockPos pos, BlockState state) {
		super(ModContent.LEAF_PILE, pos, state);
	}

	public String looks() {
		return this.looks;
	}

	public int[] leaves() {
		return this.leaves;
	}

	public void set(String looks, int[] leaves) {
		if (this.looks.equals(looks) && Arrays.equals(this.leaves, leaves)) {
			return;
		}
		this.looks = looks;
		this.leaves = leaves.clone();
		this.setChanged();
		if (this.level != null && !this.level.isClientSide()) {
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
		}
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.looks = input.getStringOr("FallenLooks", "");
		this.leaves = input.getIntArray("FallenLeaves").orElse(new int[0]);
		if (this.level != null && this.level.isClientSide() && FallenLeafHooks.client != null) {
			FallenLeafHooks.client.accept(this);
		}
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		if (this.leaves.length > 0) {
			output.putString("FallenLooks", this.looks);
			output.putIntArray("FallenLeaves", this.leaves);
		}
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
		return this.saveCustomOnly(registries);
	}
}
