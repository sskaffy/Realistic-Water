package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SnowBlastPayload(double x, double y, double z, float radius, long[] positions, byte[] layers) implements CustomPacketPayload {
	public static final Type<SnowBlastPayload> TYPE = new Type<>(RealisticWater.id("snow_blast"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SnowBlastPayload> CODEC = StreamCodec.of(SnowBlastPayload::write, SnowBlastPayload::read);
	public static final int MAX_ENTRIES = 8192;

	private static void write(RegistryFriendlyByteBuf buf, SnowBlastPayload p) {
		buf.writeDouble(p.x);
		buf.writeDouble(p.y);
		buf.writeDouble(p.z);
		buf.writeFloat(p.radius);
		buf.writeVarInt(p.positions.length);
		for (int i = 0; i < p.positions.length; i++) {
			buf.writeLong(p.positions[i]);
			buf.writeByte(p.layers[i]);
		}
	}

	private static SnowBlastPayload read(RegistryFriendlyByteBuf buf) {
		double x = buf.readDouble();
		double y = buf.readDouble();
		double z = buf.readDouble();
		float radius = buf.readFloat();
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] layers = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			layers[i] = buf.readByte();
		}
		return new SnowBlastPayload(x, y, z, radius, positions, layers);
	}

	@Override
	public Type<SnowBlastPayload> type() {
		return TYPE;
	}
}
