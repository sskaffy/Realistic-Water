package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.ocean.OceanServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record OceanModePayload(boolean enabled, int radius, float waveHeight) implements CustomPacketPayload {
	public static final Type<OceanModePayload> TYPE = new Type<>(RealisticWater.id("ocean_mode"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OceanModePayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeBoolean(p.enabled);
			buf.writeVarInt(p.radius);
			buf.writeFloat(p.waveHeight);
		},
		buf -> new OceanModePayload(buf.readBoolean(), buf.readVarInt(), buf.readFloat()));

	@Override
	public Type<OceanModePayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		OceanServer.setMode(player, this.enabled, this.radius, this.waveHeight);
	}
}
