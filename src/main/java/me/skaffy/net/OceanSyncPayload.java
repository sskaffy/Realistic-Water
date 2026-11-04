package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.ocean.OceanServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record OceanSyncPayload(long[] positions, byte[] amounts) implements CustomPacketPayload {
	public static final Type<OceanSyncPayload> TYPE = new Type<>(RealisticWater.id("ocean_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, OceanSyncPayload> CODEC = StreamCodec.of(OceanSyncPayload::write, OceanSyncPayload::read);
	public static final int MAX_ENTRIES = 32768;

	private static void write(RegistryFriendlyByteBuf buf, OceanSyncPayload payload) {
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.amounts[i]);
		}
	}

	private static OceanSyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] amounts = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			amounts[i] = buf.readByte();
		}
		return new OceanSyncPayload(positions, amounts);
	}

	@Override
	public Type<OceanSyncPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		OceanServer.applySync(player, this.positions, this.amounts);
	}
}
