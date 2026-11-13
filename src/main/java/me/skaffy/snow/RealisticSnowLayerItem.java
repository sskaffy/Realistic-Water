package me.skaffy.snow;

import me.skaffy.block.RealisticSnowBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public class RealisticSnowLayerItem extends BlockItem {
	public RealisticSnowLayerItem(Block block, Properties properties) {
		super(block, properties);
	}

	@Override
	protected @Nullable BlockState getPlacementState(final BlockPlaceContext context) {
		BlockState existing = context.getLevel().getBlockState(context.getClickedPos());
		int layers = RealisticSnowBlock.layers(existing);
		if (layers >= RealisticSnowBlock.MAX_LAYERS) {
			return null;
		}
		BlockState state = this.getBlock().defaultBlockState().setValue(RealisticSnowBlock.LAYERS, layers + 1);
		return this.canPlace(context, state) ? state : null;
	}
}
