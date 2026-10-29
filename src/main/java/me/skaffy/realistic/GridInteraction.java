package me.skaffy.realistic;

import java.util.function.Supplier;
import me.skaffy.RealisticWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public final class GridInteraction {
	private GridInteraction() {
	}

	public static <T> T asGridPlayer(Player player, RealisticBlockEntity body, GridSpace.Slot slot, Supplier<T> inside) {
		Vec3 wasPos = player.position();
		float wasYRot = player.getYRot();
		float wasXRot = player.getXRot();
		float wasHeadRot = player.getYHeadRot();
		Vector3d gridPos = body.body.toGrid(wasPos.x, wasPos.y, wasPos.z, new Vector3d());
		Vec3 look = player.getLookAngle();
		Vector3d gridLook = body.body.dirToGrid(look.x, look.y, look.z, new Vector3d());
		double yaw = -Math.atan2(gridLook.x, gridLook.z);
		double pitch = -Math.atan2(gridLook.y, Math.sqrt(gridLook.x * gridLook.x + gridLook.z * gridLook.z));
		try {
			player.setPosRaw(slot.originX + gridPos.x, slot.originY + gridPos.y, slot.originZ + gridPos.z);
			player.setYRot((float) Math.toDegrees(yaw));
			player.setXRot((float) Math.toDegrees(pitch));
			player.setYHeadRot(player.getYRot());
			return inside.get();
		} finally {
			player.setPosRaw(wasPos.x, wasPos.y, wasPos.z);
			player.setYRot(wasYRot);
			player.setXRot(wasXRot);
			player.setYHeadRot(wasHeadRot);
		}
	}

	public static InteractionResult place(ServerLevel level, Player player, InteractionHand hand, Block block, RealisticBlockEntity body,
		GridSpace.Slot slot, RealisticBlockEntity.GridPick pick) {
		BlockItem item = RealisticBlockItem.itemFor(block);
		if (item == null) {
			return InteractionResult.FAIL;
		}
		BlockPos against = slot.world(pick.x(), pick.y(), pick.z());
		if (level.getBlockState(against).isAir()) {
			RealisticWater.LOGGER.warn("Realistic block at grid {} is not mirrored yet; placement skipped", against);
			return InteractionResult.FAIL;
		}
		BlockHitResult hit = new BlockHitResult(gridPoint(slot, pick), pick.face(), against, false);
		ItemStack held = player.getItemInHand(hand);
		ItemStack vanilla = new ItemStack(item, Math.max(1, held.getCount()));
		player.setItemInHand(hand, vanilla);
		try {
			return asGridPlayer(player, body, slot, () -> item.place(new BlockPlaceContext(new UseOnContext(player, hand, hit))));
		} finally {
			player.setItemInHand(hand, held);
		}
	}

	public static InteractionResult use(ServerLevel level, Player player, InteractionHand hand, RealisticBlockEntity body, GridSpace.Slot slot,
		RealisticBlockEntity.GridPick pick) {
		BlockPos at = slot.world(pick.x(), pick.y(), pick.z());
		BlockHitResult hit = new BlockHitResult(gridPoint(slot, pick), pick.face(), at, false);
		return asGridPlayer(player, body, slot, () -> {
			BlockState state = level.getBlockState(at);
			ItemStack held = player.getItemInHand(hand);
			InteractionResult withItem = state.useItemOn(held, level, player, hand, hit);
			if (withItem != InteractionResult.PASS && !(withItem instanceof InteractionResult.TryEmptyHandInteraction)) {
				return withItem;
			}
			return state.useWithoutItem(level, player, hit);
		});
	}

	private static Vec3 gridPoint(GridSpace.Slot slot, RealisticBlockEntity.GridPick pick) {
		Vector3d local = pick.local();
		return new Vec3(slot.originX + local.x, slot.originY + local.y, slot.originZ + local.z);
	}

	public static @Nullable Direction faceOf(RealisticBlockEntity.@Nullable GridPick pick) {
		return pick == null ? null : pick.face();
	}
}
