package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.portal.PortalTransit;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public record PortalTransitPayload(Identifier dimension, byte material, long[] portals, float[] eighths) implements CustomPacketPayload {
	public static final Type<PortalTransitPayload> TYPE = new Type<>(RealisticWater.id("portal_transit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PortalTransitPayload> CODEC = StreamCodec.of(PortalTransitPayload::write, PortalTransitPayload::read);
	public static final int MAX_ENTRIES = 256;

	private static void write(RegistryFriendlyByteBuf buf, PortalTransitPayload payload) {
		buf.writeIdentifier(payload.dimension);
		buf.writeByte(payload.material);
		buf.writeVarInt(payload.portals.length);
		for (int i = 0; i < payload.portals.length; i++) {
			buf.writeLong(payload.portals[i]);
			buf.writeFloat(payload.eighths[i]);
		}
	}

	private static PortalTransitPayload read(RegistryFriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		byte material = buf.readByte();
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] portals = new long[n];
		float[] eighths = new float[n];
		for (int i = 0; i < n; i++) {
			portals[i] = buf.readLong();
			eighths[i] = buf.readFloat();
		}
		return new PortalTransitPayload(dimension, material, portals, eighths);
	}

	@Override
	public Type<PortalTransitPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		ServerLevel level = player.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, this.dimension));
		if (level == null) {
			return;
		}
		boolean here = level == player.level();
		for (int i = 0; i < this.portals.length; i++) {
			BlockPos pos = BlockPos.of(this.portals[i]);
			if (here && pos.distSqr(player.blockPosition()) > 512 * 512 || !(this.eighths[i] > 0.0F) || !Float.isFinite(this.eighths[i])) {
				continue;
			}
			PortalTransit.material(level, pos, this.material, this.eighths[i]);
		}
	}
}
