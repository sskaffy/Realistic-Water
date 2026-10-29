package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record GridBlockEventPayload(int entityId, int x, int y, int z, int paramA, int paramB) implements CustomPacketPayload {
	public static final Type<GridBlockEventPayload> TYPE = new Type<>(RealisticWater.id("grid_block_event"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GridBlockEventPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, GridBlockEventPayload::entityId,
		ByteBufCodecs.VAR_INT, GridBlockEventPayload::x,
		ByteBufCodecs.VAR_INT, GridBlockEventPayload::y,
		ByteBufCodecs.VAR_INT, GridBlockEventPayload::z,
		ByteBufCodecs.VAR_INT, GridBlockEventPayload::paramA,
		ByteBufCodecs.VAR_INT, GridBlockEventPayload::paramB,
		GridBlockEventPayload::new);

	@Override
	public Type<GridBlockEventPayload> type() {
		return TYPE;
	}
}
