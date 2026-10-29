package me.skaffy.net;

import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticLeavesBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public record LeavesSyncPayload(long[] positions, byte[] layers) implements CustomPacketPayload {
	public static final Type<LeavesSyncPayload> TYPE = new Type<>(RealisticWater.id("leaves_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LeavesSyncPayload> CODEC = StreamCodec.of(LeavesSyncPayload::write, LeavesSyncPayload::read);
	public static final int MAX_ENTRIES = 32768;

	private static void write(RegistryFriendlyByteBuf buf, LeavesSyncPayload payload) {
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.layers[i]);
		}
	}

	private static LeavesSyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] layers = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			layers[i] = buf.readByte();
		}
		return new LeavesSyncPayload(positions, layers);
	}

	@Override
	public Type<LeavesSyncPayload> type() {
		return TYPE;
	}

	public static BlockState stateFor(int layers) {
		return ModContent.REALISTIC_LEAVES.defaultBlockState().setValue(RealisticLeavesBlock.LAYERS, Math.clamp(layers, 1, RealisticLeavesBlock.MAX_LAYERS));
	}

	private static final int SYNC_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	public void apply(ServerPlayer player) {
		applyIn(player.level(), player, this.positions, this.layers);
	}

	public static void applyIn(ServerLevel level, @Nullable ServerPlayer player, long[] positions, byte[] amounts) {
		for (int i = 0; i < positions.length; i++) {
			BlockPos pos = BlockPos.of(positions[i]);
			if (!level.isLoaded(pos) || player != null && pos.distSqr(player.blockPosition()) > 512 * 512) {
				continue;
			}
			BlockState current = level.getBlockState(pos);
			boolean ours = current.getBlock() == ModContent.REALISTIC_LEAVES;
			int layers = amounts[i];
			if (layers <= 0) {
				if (ours) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), SYNC_FLAGS);
				}
			} else if (ours || current.isAir()) {
				BlockState target = stateFor(layers);
				if (current != target) {
					level.setBlock(pos, target, SYNC_FLAGS);
				}
			}
		}
	}
}
