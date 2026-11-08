package me.skaffy.block;

import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

public class RealisticLeavesBlock extends LooseLayerBlock implements net.minecraft.world.level.block.EntityBlock {
	public RealisticLeavesBlock(Properties properties) {
		super(properties);
	}

	public static int layers(BlockState state) {
		return state.getBlock() instanceof RealisticLeavesBlock ? state.getValue(LAYERS) : 0;
	}

	@Override
	public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(net.minecraft.core.BlockPos pos, BlockState state) {
		return new LeafPileBlockEntity(pos, state);
	}

	@Override
	protected TagKey<Item> digTool() {
		return ItemTags.HOES;
	}
}
