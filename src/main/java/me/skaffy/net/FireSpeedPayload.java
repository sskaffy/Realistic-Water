package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.block.FireHeat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The fire spread speed multiplier an operator chose in the settings; it applies to the whole server. */
public record FireSpeedPayload(float speed) implements CustomPacketPayload {
	public static final Type<FireSpeedPayload> TYPE = new Type<>(RealisticWater.id("fire_speed"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FireSpeedPayload> CODEC = StreamCodec.of(
		(buf, p) -> buf.writeFloat(p.speed),
		buf -> new FireSpeedPayload(buf.readFloat()));

	@Override
	public Type<FireSpeedPayload> type() {
		return TYPE;
	}

	public void apply() {
		FireHeat.speed = Math.clamp(this.speed, 0.0F, 10.0F);
	}
}
