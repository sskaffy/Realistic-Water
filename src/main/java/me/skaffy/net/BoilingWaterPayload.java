package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.fluid.BoilingWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record BoilingWaterPayload(long[] positions, float damage) implements CustomPacketPayload {
	public static final Type<BoilingWaterPayload> TYPE = new Type<>(RealisticWater.id("boiling_water"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BoilingWaterPayload> CODEC = StreamCodec.of(BoilingWaterPayload::write, BoilingWaterPayload::read);
	public static final int MAX_ENTRIES = 4096;

	private static void write(RegistryFriendlyByteBuf buf, BoilingWaterPayload payload) {
		buf.writeFloat(payload.damage);
		buf.writeVarInt(payload.positions.length);
		for (long p : payload.positions) {
			buf.writeLong(p);
		}
	}

	private static BoilingWaterPayload read(RegistryFriendlyByteBuf buf) {
		float damage = buf.readFloat();
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
		}
		return new BoilingWaterPayload(positions, damage);
	}

	@Override
	public Type<BoilingWaterPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		BoilingWater.report(player.level(), player.blockPosition(), this.positions, this.damage);
	}
}
