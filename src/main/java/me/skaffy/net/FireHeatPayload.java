package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record FireHeatPayload(long[] positions, byte[] percent) implements CustomPacketPayload {
	public static final int MAX_ENTRIES = 512;
	public static final Type<FireHeatPayload> TYPE = new Type<>(RealisticWater.id("fire_heat"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FireHeatPayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeVarInt(p.positions.length);
			for (int i = 0; i < p.positions.length; i++) {
				buf.writeLong(p.positions[i]);
				buf.writeByte(p.percent[i]);
			}
		},
		buf -> {
			int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
			long[] positions = new long[n];
			byte[] percent = new byte[n];
			for (int i = 0; i < n; i++) {
				positions[i] = buf.readLong();
				percent[i] = buf.readByte();
			}
			return new FireHeatPayload(positions, percent);
		});

	@Override
	public Type<FireHeatPayload> type() {
		return TYPE;
	}
}
