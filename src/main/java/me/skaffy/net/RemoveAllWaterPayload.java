package me.skaffy.net;

import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public record RemoveAllWaterPayload(int radiusChunks, boolean sand) implements CustomPacketPayload {
	public static final Type<RemoveAllWaterPayload> TYPE = new Type<>(RealisticWater.id("remove_all_water"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RemoveAllWaterPayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeVarInt(p.radiusChunks);
			buf.writeBoolean(p.sand);
		},
		buf -> new RemoveAllWaterPayload(buf.readVarInt(), buf.readBoolean()));

	@Override
	public Type<RemoveAllWaterPayload> type() {
		return TYPE;
	}

	public int apply(ServerPlayer player) {
		ServerLevel level = player.level();
		Block target = this.sand ? ModContent.REALISTIC_SAND : ModContent.REALISTIC_WATER_BLOCK;
		int radius = Math.clamp(this.radiusChunks, 1, 64);
		int cx = player.blockPosition().getX() >> 4;
		int cz = player.blockPosition().getZ() >> 4;
		int removed = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dz = -radius; dz <= radius; dz++) {
			for (int dx = -radius; dx <= radius; dx++) {
				LevelChunk chunk = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
				if (chunk == null) {
					continue;
				}
				LevelChunkSection[] sections = chunk.getSections();
				for (int s = 0; s < sections.length; s++) {
					LevelChunkSection section = sections[s];
					if (section.hasOnlyAir() || !section.maybeHas(state -> state.getBlock() == target)) {
						continue;
					}
					int baseY = (level.getMinSectionY() + s) << 4;
					for (int i = 0; i < 4096; i++) {
						if (section.getBlockState(i & 15, (i >> 4) & 15, i >> 8).getBlock() == target) {
							pos.set(((cx + dx) << 4) + (i & 15), baseY + ((i >> 4) & 15), ((cz + dz) << 4) + (i >> 8));
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE);
							removed++;
						}
					}
				}
			}
		}
		return removed;
	}
}
