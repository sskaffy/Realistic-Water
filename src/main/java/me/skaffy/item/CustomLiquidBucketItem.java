package me.skaffy.item;

import java.util.List;
import me.skaffy.ModContent;
import me.skaffy.block.CustomLiquidBlock;
import me.skaffy.liquid.CustomLiquid;
import me.skaffy.liquid.CustomLiquids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

public class CustomLiquidBucketItem extends BucketItem {
	private static final ThreadLocal<Integer> PLACING = ThreadLocal.withInitial(() -> -1);

	private final boolean infinite;

	public CustomLiquidBucketItem(FlowingFluid content, Properties properties, boolean infinite) {
		super(content, properties);
		this.infinite = infinite;
	}

	public static int slot(ItemStack stack) {
		Integer slot = stack.get(ModContent.LIQUID_SLOT);
		return slot == null ? 0 : Math.min(Math.max(slot, 0), CustomLiquid.MAX_SLOTS - 1);
	}

	public static ItemStack stackFor(LevelAccessor level, int slot, boolean infinite) {
		return stack(slot, level instanceof Level l ? CustomLiquids.get(l, slot) : null, infinite);
	}

	public static ItemStack stack(int slot, @Nullable CustomLiquid liquid, boolean infinite) {
		ItemStack stack = new ItemStack(infinite ? ModContent.INFINITE_CUSTOM_LIQUID_BUCKET : ModContent.CUSTOM_LIQUID_BUCKET);
		stack.set(ModContent.LIQUID_SLOT, slot);
		describe(stack, liquid != null ? liquid : CustomLiquid.missing(slot), infinite);
		return stack;
	}

	private static void describe(ItemStack stack, CustomLiquid liquid, boolean infinite) {
		Component name = Component.translatable(infinite ? "item.realistic-water.infinite_custom_liquid_bucket.named"
			: "item.realistic-water.custom_liquid_bucket.named", liquid.name());
		CustomModelData look = new CustomModelData(List.of(), List.of(), List.of(), List.of(liquid.color()));
		if (!name.equals(stack.get(DataComponents.ITEM_NAME))) {
			stack.set(DataComponents.ITEM_NAME, name);
		}
		if (!look.equals(stack.get(DataComponents.CUSTOM_MODEL_DATA))) {
			stack.set(DataComponents.CUSTOM_MODEL_DATA, look);
		}
	}

	@Override
	public void inventoryTick(final ItemStack stack, final ServerLevel level, final Entity owner, final @Nullable EquipmentSlot slot) {
		if (level.getGameTime() % 40L != 0L) {
			return;
		}
		int liquidSlot = slot(stack);
		CustomLiquid liquid = CustomLiquids.get(level, liquidSlot);
		describe(stack, liquid != null ? liquid : CustomLiquid.missing(liquidSlot), this.infinite);
	}

	@Override
	public InteractionResult use(final Level level, final Player player, final InteractionHand hand) {
		PLACING.set(slot(player.getItemInHand(hand)));
		try {
			return super.use(level, player, hand);
		} finally {
			PLACING.set(-1);
		}
	}

	@Override
	public boolean emptyContents(final @Nullable LivingEntity user, final Level level, final BlockPos pos, final @Nullable BlockHitResult hitResult) {
		BlockState blockState = level.getBlockState(pos);
		boolean mayReplace = blockState.canBeReplaced(this.content);
		boolean shiftKeyDown = user != null && user.isShiftKeyDown();
		boolean canPlace = blockState.isAir() || mayReplace && (!shiftKeyDown || hitResult == null);
		if (!canPlace) {
			return hitResult != null && this.emptyContents(user, level, hitResult.getBlockPos().relative(hitResult.getDirection()), null);
		}
		if (!level.isClientSide() && mayReplace && !blockState.liquid()) {
			level.destroyBlock(pos, true);
		}
		int slot = Math.max(PLACING.get(), 0);
		BlockState placed = this.infinite ? CustomLiquidBlock.springState(slot) : CustomLiquidBlock.stateFor(slot, 8);
		if (!level.setBlock(pos, placed, 11)) {
			return false;
		}
		this.playEmptySound(user, level, pos);
		return true;
	}

	@Override
	public void checkExtraContent(final @Nullable LivingEntity user, final Level level, final ItemStack stack, final BlockPos pos) {
		BlockState placed = level.getBlockState(pos);
		int slot = slot(stack);
		if (CustomLiquidBlock.slot(placed) >= 0 && CustomLiquidBlock.slot(placed) != slot) {
			level.setBlock(pos, placed.setValue(CustomLiquidBlock.SLOT, slot), 11);
		}
	}

	@Override
	protected void playEmptySound(final @Nullable LivingEntity user, final LevelAccessor level, final BlockPos pos) {
		level.playSound(user, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
		level.gameEvent(user, GameEvent.FLUID_PLACE, pos);
	}
}
