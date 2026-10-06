package me.skaffy.net;

import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.fluid.RealisticWaterFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

public record WaterSyncPayload(long[] positions, byte[] amounts) implements CustomPacketPayload {
	public static final Type<WaterSyncPayload> TYPE = new Type<>(RealisticWater.id("water_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, WaterSyncPayload> CODEC = StreamCodec.of(WaterSyncPayload::write, WaterSyncPayload::read);
	public static final int MAX_ENTRIES = 32768;

	private static void write(RegistryFriendlyByteBuf buf, WaterSyncPayload payload) {
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.amounts[i]);
		}
	}

	private static WaterSyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] amounts = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			amounts[i] = buf.readByte();
		}
		return new WaterSyncPayload(positions, amounts);
	}

	@Override
	public Type<WaterSyncPayload> type() {
		return TYPE;
	}

	public static BlockState stateFor(int amount) {
		BlockState full = ModContent.REALISTIC_WATER_BLOCK.defaultBlockState().setValue(RealisticWaterBlock.INFINITE, false);
		return amount >= 8 ? full : full.setValue(LiquidBlock.LEVEL, 8 - Math.max(1, amount));
	}

	private static final int SYNC_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	public void apply(ServerPlayer player) {
		ServerLevel level = player.level();
		for (int i = 0; i < this.positions.length; i++) {
			BlockPos pos = BlockPos.of(this.positions[i]);
			if (!level.isLoaded(pos) || pos.distSqr(player.blockPosition()) > 512 * 512) {
				continue;
			}
			BlockState current = level.getBlockState(pos);
			if (RealisticWaterBlock.isInfinite(current)) {
				continue;
			}
			boolean ours = RealisticWaterFluid.isRealistic(current.getFluidState()) && current.getBlock() == ModContent.REALISTIC_WATER_BLOCK;
			int amount = this.amounts[i];
			if (amount <= 0) {
				if (ours) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), SYNC_FLAGS);
				}
			} else if (ours || current.isAir()) {
				BlockState target = stateFor(amount);
				if (current != target) {
					level.setBlock(pos, target, SYNC_FLAGS);
				}
			}
		}
	}
}
