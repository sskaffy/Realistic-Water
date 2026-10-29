package me.skaffy.net;

import java.util.Optional;
import me.skaffy.RealisticWater;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record GridBlockEntityPayload(int entityId, int x, int y, int z, Optional<CompoundTag> tag) implements CustomPacketPayload {
	public static final Type<GridBlockEntityPayload> TYPE = new Type<>(RealisticWater.id("grid_block_entity"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GridBlockEntityPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, GridBlockEntityPayload::entityId,
		ByteBufCodecs.VAR_INT, GridBlockEntityPayload::x,
		ByteBufCodecs.VAR_INT, GridBlockEntityPayload::y,
		ByteBufCodecs.VAR_INT, GridBlockEntityPayload::z,
		ByteBufCodecs.OPTIONAL_COMPOUND_TAG.cast(), GridBlockEntityPayload::tag,
		GridBlockEntityPayload::new);

	@Override
	public Type<GridBlockEntityPayload> type() {
		return TYPE;
	}
}
