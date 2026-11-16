package me.skaffy.net;

import me.skaffy.RealisticWater;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

public record HotTouchPayload(float self, int[] entities, float[] heats) implements CustomPacketPayload {
	public static final Type<HotTouchPayload> TYPE = new Type<>(RealisticWater.id("hot_touch"));
	public static final StreamCodec<RegistryFriendlyByteBuf, HotTouchPayload> CODEC = StreamCodec.of(HotTouchPayload::write, HotTouchPayload::read);
	public static final int MAX_ENTITIES = 128;
	public static final float LAVA_DAMAGE = 4.0F;
	private static final double RANGE = 64.0;

	private static void write(RegistryFriendlyByteBuf buf, HotTouchPayload payload) {
		buf.writeFloat(payload.self);
		buf.writeVarInt(payload.entities.length);
		for (int i = 0; i < payload.entities.length; i++) {
			buf.writeVarInt(payload.entities[i]);
			buf.writeFloat(payload.heats[i]);
		}
	}

	private static HotTouchPayload read(RegistryFriendlyByteBuf buf) {
		float self = buf.readFloat();
		int n = Math.min(buf.readVarInt(), MAX_ENTITIES);
		int[] entities = new int[n];
		float[] heats = new float[n];
		for (int i = 0; i < n; i++) {
			entities[i] = buf.readVarInt();
			heats[i] = buf.readFloat();
		}
		return new HotTouchPayload(self, entities, heats);
	}

	@Override
	public Type<HotTouchPayload> type() {
		return TYPE;
	}

	public static boolean heatable(Block block) {
		return block == Blocks.IRON_BLOCK || block == Blocks.STONE || block == Blocks.COBBLESTONE || block == Blocks.DEEPSLATE
			|| block == Blocks.COBBLED_DEEPSLATE;
	}

	private static boolean touchesHeatable(ServerLevel level, Entity entity) {
		AABB box = entity.getBoundingBox().inflate(0.2);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
			for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++) {
				for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
					if (heatable(level.getBlockState(pos.set(x, y, z)).getBlock())) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static void burn(ServerLevel level, Entity entity, float heat) {
		if (!(heat > 0.0F) || entity.fireImmune() || entity.isSpectator() || !touchesHeatable(level, entity)) {
			return;
		}
		float h = Math.min(heat, 1.0F);
		if (h >= 0.95F) {
			entity.igniteForSeconds(15.0F);
			entity.hurtServer(level, level.damageSources().lava(), LAVA_DAMAGE);
		} else {
			if (h >= 0.6F) {
				entity.igniteForSeconds(15.0F * h * h);
			}
			entity.hurtServer(level, level.damageSources().hotFloor(), Math.max(0.5F, LAVA_DAMAGE * h));
		}
	}

	public void apply(ServerPlayer player, boolean authority) {
		ServerLevel level = player.level();
		burn(level, player, this.self);
		if (!authority) {
			return;
		}
		for (int i = 0; i < this.entities.length; i++) {
			Entity entity = level.getEntity(this.entities[i]);
			if (entity instanceof LivingEntity && entity != player && entity.distanceToSqr(player) <= RANGE * RANGE) {
				burn(level, entity, this.heats[i]);
			}
		}
	}
}
