package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SnowBurstPayload(double x, double y, double z, float vx, float vy, float vz, byte face) implements CustomPacketPayload {
	public static final Type<SnowBurstPayload> TYPE = new Type<>(RealisticWater.id("snow_burst"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SnowBurstPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.DOUBLE, SnowBurstPayload::x,
		ByteBufCodecs.DOUBLE, SnowBurstPayload::y,
		ByteBufCodecs.DOUBLE, SnowBurstPayload::z,
		ByteBufCodecs.FLOAT, SnowBurstPayload::vx,
		ByteBufCodecs.FLOAT, SnowBurstPayload::vy,
		ByteBufCodecs.FLOAT, SnowBurstPayload::vz,
		ByteBufCodecs.BYTE, SnowBurstPayload::face,
		SnowBurstPayload::new);

	@Override
	public Type<SnowBurstPayload> type() {
		return TYPE;
	}
}
