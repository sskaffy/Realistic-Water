package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public record FlameTouchPayload(boolean self, int[] entities, long[] blocks) implements CustomPacketPayload {
	public static final Type<FlameTouchPayload> TYPE = new Type<>(RealisticWater.id("flame_touch"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FlameTouchPayload> CODEC = StreamCodec.of(FlameTouchPayload::write, FlameTouchPayload::read);
	public static final int MAX_ENTITIES = 128;
	public static final int MAX_BLOCKS = 256;
	private static final double RANGE = 96.0;

	private static void write(RegistryFriendlyByteBuf buf, FlameTouchPayload payload) {
		buf.writeBoolean(payload.self);
		buf.writeVarInt(payload.entities.length);
		for (int id : payload.entities) {
			buf.writeVarInt(id);
		}
		buf.writeVarInt(payload.blocks.length);
		for (long pos : payload.blocks) {
			buf.writeLong(pos);
		}
	}

	private static FlameTouchPayload read(RegistryFriendlyByteBuf buf) {
		boolean self = buf.readBoolean();
		int[] entities = new int[Math.min(buf.readVarInt(), MAX_ENTITIES)];
		for (int i = 0; i < entities.length; i++) {
			entities[i] = buf.readVarInt();
		}
		long[] blocks = new long[Math.min(buf.readVarInt(), MAX_BLOCKS)];
		for (int i = 0; i < blocks.length; i++) {
			blocks[i] = buf.readLong();
		}
		return new FlameTouchPayload(self, entities, blocks);
	}

	@Override
	public Type<FlameTouchPayload> type() {
		return TYPE;
	}

	private static void burn(ServerLevel level, Entity entity) {
		if (entity.fireImmune() || entity.isSpectator()) {
			return;
		}
		entity.igniteForSeconds(4.0F);
		entity.hurtServer(level, level.damageSources().inFire(), 1.0F);
	}

	public void apply(ServerPlayer player, boolean authority) {
		ServerLevel level = player.level();
		if (this.self) {
			burn(level, player);
		}
		if (!authority) {
			return;
		}
		for (int id : this.entities) {
			Entity entity = level.getEntity(id);
			if (entity instanceof LivingEntity && entity != player && entity.distanceToSqr(player) <= RANGE * RANGE) {
				burn(level, entity);
			}
		}
		for (long key : this.blocks) {
			BlockPos pos = BlockPos.of(key);
			if (level.isLoaded(pos) && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= RANGE * RANGE) {
				RealisticFireBlock.flameTouch(level, pos);
			}
		}
	}
}
