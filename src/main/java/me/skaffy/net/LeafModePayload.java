package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.trees.LeafSinking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record LeafModePayload(boolean sinking) implements CustomPacketPayload {
	public static final Type<LeafModePayload> TYPE = new Type<>(RealisticWater.id("leaf_mode"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LeafModePayload> CODEC = StreamCodec.of(
		(buf, p) -> buf.writeBoolean(p.sinking),
		buf -> new LeafModePayload(buf.readBoolean()));

	@Override
	public Type<LeafModePayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		LeafSinking.set(player, this.sinking);
	}
}
