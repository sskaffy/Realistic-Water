package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import me.skaffy.realistic.RealisticBlockEntity;

public record UseRealisticPayload(int entityId, int x, int y, int z, int face, float localX, float localY, float localZ, boolean offhand)
	implements CustomPacketPayload {
	public static final Type<UseRealisticPayload> TYPE = new Type<>(RealisticWater.id("use_realistic"));
	public static final StreamCodec<RegistryFriendlyByteBuf, UseRealisticPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, UseRealisticPayload::entityId,
		ByteBufCodecs.VAR_INT, UseRealisticPayload::x,
		ByteBufCodecs.VAR_INT, UseRealisticPayload::y,
		ByteBufCodecs.VAR_INT, UseRealisticPayload::z,
		ByteBufCodecs.VAR_INT, UseRealisticPayload::face,
		ByteBufCodecs.FLOAT, UseRealisticPayload::localX,
		ByteBufCodecs.FLOAT, UseRealisticPayload::localY,
		ByteBufCodecs.FLOAT, UseRealisticPayload::localZ,
		ByteBufCodecs.BOOL, UseRealisticPayload::offhand,
		UseRealisticPayload::new);

	@Override
	public Type<UseRealisticPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		if (!(player.level().getEntity(this.entityId) instanceof RealisticBlockEntity body)) {
			return;
		}
		body.useFromClient(player, this.offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, this.x, this.y, this.z, this.face,
			this.localX, this.localY, this.localZ);
	}
}
