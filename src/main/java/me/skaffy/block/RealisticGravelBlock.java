package me.skaffy.block;

import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

public class RealisticGravelBlock extends LooseLayerBlock {
	public RealisticGravelBlock(Properties properties) {
		super(properties);
	}

	public static int layers(BlockState state) {
		return state.getBlock() instanceof RealisticGravelBlock ? state.getValue(LAYERS) : 0;
	}

	@Override
	protected TagKey<Item> digTool() {
		return ItemTags.SHOVELS;
	}
}
