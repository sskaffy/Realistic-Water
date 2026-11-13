package me.skaffy.item;

import me.skaffy.block.RealisticFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

public class RealisticFlintAndSteelItem extends FlintAndSteelItem {
	public RealisticFlintAndSteelItem(Properties properties) {
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
		level.playSound(player, firePos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.4F + 0.8F);
		level.setBlock(firePos, RealisticFireBlock.placementState(level, firePos), 11);
		level.gameEvent(player, GameEvent.BLOCK_PLACE, firePos);
		if (player instanceof ServerPlayer) {
			context.getItemInHand().hurtAndBreak(1, player, context.getHand());
		}
		return InteractionResult.SUCCESS;
	}
}
