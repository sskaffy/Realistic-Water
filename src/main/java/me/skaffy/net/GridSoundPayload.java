package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.core.Holder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.sounds.SoundEvent;

public record GridSoundPayload(int entityId, float x, float y, float z, Holder<SoundEvent> sound, int source, float volume, float pitch)
	implements CustomPacketPayload {
	public static final Type<GridSoundPayload> TYPE = new Type<>(RealisticWater.id("grid_sound"));
	public static final StreamCodec<RegistryFriendlyByteBuf, GridSoundPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, GridSoundPayload::entityId,
		ByteBufCodecs.FLOAT, GridSoundPayload::x,
		ByteBufCodecs.FLOAT, GridSoundPayload::y,
		ByteBufCodecs.FLOAT, GridSoundPayload::z,
		SoundEvent.STREAM_CODEC, GridSoundPayload::sound,
		ByteBufCodecs.VAR_INT, GridSoundPayload::source,
		ByteBufCodecs.FLOAT, GridSoundPayload::volume,
		ByteBufCodecs.FLOAT, GridSoundPayload::pitch,
		GridSoundPayload::new);

	@Override
	public Type<GridSoundPayload> type() {
		return TYPE;
	}
}
