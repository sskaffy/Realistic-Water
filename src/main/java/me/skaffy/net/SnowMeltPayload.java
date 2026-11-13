package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticSnowBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;

public record SnowMeltPayload(long[] positions, byte[] layersLost) implements CustomPacketPayload {
	public static final Type<SnowMeltPayload> TYPE = new Type<>(RealisticWater.id("snow_melt"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SnowMeltPayload> CODEC = StreamCodec.of(SnowMeltPayload::write, SnowMeltPayload::read);
	public static final int MAX_ENTRIES = 4096;
	private static final int FLAGS = Block.UPDATE_ALL;

	private static void write(RegistryFriendlyByteBuf buf, SnowMeltPayload payload) {
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.layersLost[i]);
		}
	}

	private static SnowMeltPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] lost = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			lost[i] = buf.readByte();
		}
		return new SnowMeltPayload(positions, lost);
	}

	@Override
	public Type<SnowMeltPayload> type() {
		return TYPE;
	}

	public static int snowLayers(BlockState state) {
		if (state.is(Blocks.SNOW)) {
			return state.getValue(SnowLayerBlock.LAYERS);
		}
		if (state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)) {
			return 8;
		}
		return RealisticSnowBlock.layers(state);
	}

	public void apply(ServerPlayer player) {
		var level = player.level();
		for (int i = 0; i < this.positions.length; i++) {
			BlockPos pos = BlockPos.of(this.positions[i]);
			if (!level.isLoaded(pos) || pos.distSqr(player.blockPosition()) > 256 * 256) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			int layers = snowLayers(state);
			int lost = Math.clamp(this.layersLost[i], 0, 8);
			if (layers == 0 || lost == 0) {
				continue;
			}
			int left = layers - lost;
			if (left <= 0) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
			} else if (state.getBlock() instanceof RealisticSnowBlock) {
				level.setBlock(pos, state.setValue(RealisticSnowBlock.LAYERS, left).setValue(RealisticSnowBlock.ORIGIN, me.skaffy.block.SnowOrigin.PLACED), FLAGS);
			} else {
				level.setBlock(pos, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, left), FLAGS);
			}
		}
	}
}
