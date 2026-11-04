package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

public record VanillaFeedPayload(long[] positions) implements CustomPacketPayload {
	public static final Type<VanillaFeedPayload> TYPE = new Type<>(RealisticWater.id("vanilla_feed"));
	public static final StreamCodec<RegistryFriendlyByteBuf, VanillaFeedPayload> CODEC = StreamCodec.of(VanillaFeedPayload::write, VanillaFeedPayload::read);
	public static final int MAX_ENTRIES = 4096;

	private static void write(RegistryFriendlyByteBuf buf, VanillaFeedPayload payload) {
		buf.writeVarInt(payload.positions.length);
		for (long p : payload.positions) {
			buf.writeLong(p);
		}
	}

	private static VanillaFeedPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
		}
		return new VanillaFeedPayload(positions);
	}

	@Override
	public Type<VanillaFeedPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		ServerLevel level = player.level();
		for (long key : this.positions) {
			BlockPos pos = BlockPos.of(key);
			if (!level.isLoaded(pos) || pos.distSqr(player.blockPosition()) > 512 * 512) {
				continue;
			}
			if (level.getBlockState(pos).getBlock() == Blocks.WATER && level.getFluidState(pos).getType() == Fluids.FLOWING_WATER) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}
}
