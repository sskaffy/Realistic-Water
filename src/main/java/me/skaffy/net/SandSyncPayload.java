package me.skaffy.net;

import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticSandBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public record SandSyncPayload(long[] positions, byte[] layers) implements CustomPacketPayload {
	public static final Type<SandSyncPayload> TYPE = new Type<>(RealisticWater.id("sand_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SandSyncPayload> CODEC = StreamCodec.of(SandSyncPayload::write, SandSyncPayload::read);
	public static final int MAX_ENTRIES = 32768;

	private static void write(RegistryFriendlyByteBuf buf, SandSyncPayload payload) {
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.layers[i]);
		}
	}

	private static SandSyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] layers = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			layers[i] = buf.readByte();
		}
		return new SandSyncPayload(positions, layers);
	}

	@Override
	public Type<SandSyncPayload> type() {
		return TYPE;
	}

	public static BlockState stateFor(int layers) {
		return ModContent.REALISTIC_SAND.defaultBlockState().setValue(RealisticSandBlock.LAYERS, Math.clamp(layers, 1, RealisticSandBlock.MAX_LAYERS));
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
			boolean ours = current.getBlock() == ModContent.REALISTIC_SAND;
			int layers = this.layers[i];
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
