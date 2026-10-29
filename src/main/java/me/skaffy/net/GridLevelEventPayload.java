package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record GridLevelEventPayload(int entityId, float x, float y, float z, int event, int data) implements CustomPacketPayload {
	public static final Type<GridLevelEventPayload> TYPE = new Type<>(RealisticWater.id("grid_level_event"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GridLevelEventPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, GridLevelEventPayload::entityId,
		ByteBufCodecs.FLOAT, GridLevelEventPayload::x,
		ByteBufCodecs.FLOAT, GridLevelEventPayload::y,
		ByteBufCodecs.FLOAT, GridLevelEventPayload::z,
		ByteBufCodecs.VAR_INT, GridLevelEventPayload::event,
		ByteBufCodecs.VAR_INT, GridLevelEventPayload::data,
		GridLevelEventPayload::new);

	@Override
	public Type<GridLevelEventPayload> type() {
		return TYPE;
	}
}
