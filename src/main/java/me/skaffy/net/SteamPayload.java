package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SteamPayload(double x, double y, double z, float eighths) implements CustomPacketPayload {
	public static final Type<SteamPayload> TYPE = new Type<>(RealisticWater.id("steam"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SteamPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.DOUBLE, SteamPayload::x,
		ByteBufCodecs.DOUBLE, SteamPayload::y,
		ByteBufCodecs.DOUBLE, SteamPayload::z,
		ByteBufCodecs.FLOAT, SteamPayload::eighths,
		SteamPayload::new);

	@Override
	public Type<SteamPayload> type() {
		return TYPE;
	}
}
