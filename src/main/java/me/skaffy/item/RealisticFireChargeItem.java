package me.skaffy.item;

import me.skaffy.block.RealisticFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FireChargeItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

public class RealisticFireChargeItem extends FireChargeItem {
	public RealisticFireChargeItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(final UseOnContext context) {
		Level level = context.getLevel();
		BlockPos clicked = context.getClickedPos();
		BlockState state = level.getBlockState(clicked);
		if (CampfireBlock.canLight(state) || CandleBlock.canLight(state) || CandleCakeBlock.canLight(state)) {
			return super.useOn(context);
		}
		BlockPos firePos = clicked.relative(context.getClickedFace());
		if (!BaseFireBlock.canBePlacedAt(level, firePos, context.getHorizontalDirection())) {
			return InteractionResult.FAIL;
		}
		Player player = context.getPlayer();
		level.playSound(null, firePos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0F, (level.getRandom().nextFloat() - level.getRandom().nextFloat()) * 0.2F + 1.0F);
		level.setBlockAndUpdate(firePos, RealisticFireBlock.placementState(level, firePos));
		level.gameEvent(player, GameEvent.BLOCK_PLACE, firePos);
		context.getItemInHand().consume(1, player);
		return InteractionResult.SUCCESS;
	}
}
