package me.skaffy.net;

import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.CustomLiquidBlock;
import me.skaffy.liquid.CustomLiquid;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public record CustomLiquidSyncPayload(int slot, long[] positions, byte[] amounts) implements CustomPacketPayload {
	public static final Type<CustomLiquidSyncPayload> TYPE = new Type<>(RealisticWater.id("custom_liquid_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, CustomLiquidSyncPayload> CODEC = StreamCodec.of(CustomLiquidSyncPayload::write,
		CustomLiquidSyncPayload::read);
	public static final int MAX_ENTRIES = 32768;
	private static final int SYNC_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	private static void write(RegistryFriendlyByteBuf buf, CustomLiquidSyncPayload payload) {
		buf.writeVarInt(payload.slot);
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.amounts[i]);
		}
	}

	private static CustomLiquidSyncPayload read(RegistryFriendlyByteBuf buf) {
		int slot = buf.readVarInt();
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] amounts = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			amounts[i] = buf.readByte();
		}
		return new CustomLiquidSyncPayload(slot, positions, amounts);
	}

	@Override
	public Type<CustomLiquidSyncPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		if (this.slot < 0 || this.slot >= CustomLiquid.MAX_SLOTS) {
			return;
		}
		ServerLevel level = player.level();
		for (int i = 0; i < this.positions.length; i++) {
			BlockPos pos = BlockPos.of(this.positions[i]);
			if (!level.isLoaded(pos) || pos.distSqr(player.blockPosition()) > 512 * 512) {
				continue;
			}
			BlockState current = level.getBlockState(pos);
			if (CustomLiquidBlock.isInfinite(current)) {
				continue;
			}
			boolean ours = current.getBlock() == ModContent.CUSTOM_LIQUID_BLOCK && current.getValue(CustomLiquidBlock.SLOT) == this.slot;
			int amount = this.amounts[i];
			if (amount <= 0) {
				if (ours) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), SYNC_FLAGS);
				}
			} else if (ours || current.isAir()) {
				BlockState target = CustomLiquidBlock.stateFor(this.slot, amount);
				if (current != target) {
					level.setBlock(pos, target, SYNC_FLAGS);
				}
			}
		}
	}
}
