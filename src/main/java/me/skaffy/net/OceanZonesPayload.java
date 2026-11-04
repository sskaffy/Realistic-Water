package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.ocean.OceanServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record OceanZonesPayload(int[] boxes) implements CustomPacketPayload {
	public static final Type<OceanZonesPayload> TYPE = new Type<>(RealisticWater.id("ocean_zones"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OceanZonesPayload> CODEC = StreamCodec.of(
		(buf, p) -> buf.writeVarIntArray(p.boxes),
		buf -> new OceanZonesPayload(buf.readVarIntArray(OceanServer.MAX_ZONES * 6)));

	@Override
	public Type<OceanZonesPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		OceanServer.setZones(player, OceanServer.readZones(this.boxes));
	}
}
