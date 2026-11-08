package me.skaffy.net;

import java.util.ArrayList;
import java.util.List;
import me.skaffy.RealisticWater;
import me.skaffy.block.LeafPileBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public record FallenLeavesPayload(List<Entry> entries) implements CustomPacketPayload {
	public record Entry(long pos, String looks, int[] leaves) {
	}

	private static final int MAX_ENTRIES = 4096;
	private static final int MAX_LEAVES = 4096;
	public static final Type<FallenLeavesPayload> TYPE = new Type<>(RealisticWater.id("fallen_leaves"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FallenLeavesPayload> CODEC = StreamCodec.of(
		(buf, p) -> {
			buf.writeVarInt(p.entries.size());
			for (Entry e : p.entries) {
				buf.writeLong(e.pos);
				buf.writeUtf(e.looks, 8192);
				buf.writeVarIntArray(e.leaves);
			}
		},
		buf -> {
			int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
			List<Entry> list = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				long pos = buf.readLong();
				String looks = buf.readUtf(8192);
				int[] leaves = buf.readVarIntArray(MAX_LEAVES);
				list.add(new Entry(pos, looks, leaves));
			}
			return new FallenLeavesPayload(list);
		});

	@Override
	public Type<FallenLeavesPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		for (Entry e : this.entries) {
			BlockPos pos = BlockPos.of(e.pos);
			if (!player.level().isLoaded(pos)) {
				continue;
			}
			if (player.level().getBlockEntity(pos) instanceof LeafPileBlockEntity pile) {
				pile.set(e.looks, e.leaves);
			}
		}
	}
}
