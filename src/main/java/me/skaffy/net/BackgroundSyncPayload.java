package me.skaffy.net;

import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

public record BackgroundSyncPayload(Identifier dimension, byte material, long[] positions, byte[] amounts, int minX, int minZ, int maxX, int maxZ)
	implements CustomPacketPayload {
	public static final Type<BackgroundSyncPayload> TYPE = new Type<>(RealisticWater.id("background_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BackgroundSyncPayload> CODEC = StreamCodec.of(BackgroundSyncPayload::write,
		BackgroundSyncPayload::read);
	public static final int MAX_ENTRIES = 32768;
	private static final int MAX_CHUNKS_ACROSS = 64;

	private static void write(RegistryFriendlyByteBuf buf, BackgroundSyncPayload p) {
		buf.writeIdentifier(p.dimension);
		buf.writeByte(p.material);
		buf.writeVarInt(p.minX);
		buf.writeVarInt(p.minZ);
		buf.writeVarInt(p.maxX);
		buf.writeVarInt(p.maxZ);
		buf.writeVarInt(p.positions.length);
		for (int i = 0; i < p.positions.length; i++) {
			buf.writeLong(p.positions[i]);
			buf.writeByte(p.amounts[i]);
		}
	}

	private static BackgroundSyncPayload read(RegistryFriendlyByteBuf buf) {
		Identifier dimension = buf.readIdentifier();
		byte material = buf.readByte();
		int minX = buf.readVarInt();
		int minZ = buf.readVarInt();
		int maxX = buf.readVarInt();
		int maxZ = buf.readVarInt();
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] amounts = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			amounts[i] = buf.readByte();
		}
		return new BackgroundSyncPayload(dimension, material, positions, amounts, minX, minZ, maxX, maxZ);
	}

	@Override
	public Type<BackgroundSyncPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		ServerLevel level = player.level().getServer().getLevel(ResourceKey.create(Registries.DIMENSION, this.dimension));
		if (level == null) {
			return;
		}
		int cx0 = this.minX >> 4;
		int cz0 = this.minZ >> 4;
		int cx1 = Math.min(this.maxX >> 4, cx0 + MAX_CHUNKS_ACROSS);
		int cz1 = Math.min(this.maxZ >> 4, cz0 + MAX_CHUNKS_ACROSS);
		for (int cx = cx0; cx <= cx1; cx++) {
			for (int cz = cz0; cz <= cz1; cz++) {
				level.getChunkSource().addTicketWithRadius(ModContent.BACKGROUND_TICKET, new ChunkPos(cx, cz), 2);
			}
		}
		switch (this.material) {
			case SyncMaterial.SAND -> SandSyncPayload.applyIn(level, null, this.positions, this.amounts);
			case SyncMaterial.LAVA -> LavaSyncPayload.applyIn(level, null, this.positions, this.amounts);
			case SyncMaterial.SLIME -> SlimeSyncPayload.applyIn(level, null, this.positions, this.amounts);
			case SyncMaterial.LEAVES -> LeavesSyncPayload.applyIn(level, null, this.positions, this.amounts);
			default -> WaterSyncPayload.applyIn(level, null, this.positions, this.amounts);
		}
	}
}
