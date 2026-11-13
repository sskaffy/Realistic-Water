package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.block.LooseLayerBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public record LayerSyncPayload(byte material, long[] positions, byte[] layers) implements CustomPacketPayload {
	public static final Type<LayerSyncPayload> TYPE = new Type<>(RealisticWater.id("layer_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LayerSyncPayload> CODEC = StreamCodec.of(LayerSyncPayload::write, LayerSyncPayload::read);
	public static final int MAX_ENTRIES = 32768;
	private static final int SYNC_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	private static void write(RegistryFriendlyByteBuf buf, LayerSyncPayload payload) {
		buf.writeByte(payload.material);
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.layers[i]);
		}
	}

	private static LayerSyncPayload read(RegistryFriendlyByteBuf buf) {
		byte material = buf.readByte();
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] layers = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			layers[i] = buf.readByte();
		}
		return new LayerSyncPayload(material, positions, layers);
	}

	@Override
	public Type<LayerSyncPayload> type() {
		return TYPE;
	}

	public static boolean handles(byte material) {
		return material == SyncMaterial.GRAVEL;
	}

	public static BlockState stateFor(byte material, int layers) {
		return SyncMaterial.block(material).defaultBlockState().setValue(LooseLayerBlock.LAYERS, Math.clamp(layers, 1, LooseLayerBlock.MAX_LAYERS));
	}

	public static boolean replaces(byte material, BlockState current) {
		return current.isAir();
	}

	public void apply(ServerPlayer player) {
		applyIn(player.level(), player, this.material, this.positions, this.layers);
	}

	public static void applyIn(ServerLevel level, @Nullable ServerPlayer player, byte material, long[] positions, byte[] amounts) {
		if (!handles(material)) {
			return;
		}
		Block block = SyncMaterial.block(material);
		for (int i = 0; i < positions.length; i++) {
			BlockPos pos = BlockPos.of(positions[i]);
			if (!level.isLoaded(pos) || player != null && pos.distSqr(player.blockPosition()) > 512 * 512) {
				continue;
			}
			BlockState current = level.getBlockState(pos);
			boolean ours = current.getBlock() == block;
			int layers = amounts[i];
			if (layers <= 0) {
				if (ours) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), SYNC_FLAGS);
				}
			} else if (ours || replaces(material, current)) {
				BlockState target = stateFor(material, layers);
				if (current != target) {
					level.setBlock(pos, target, SYNC_FLAGS);
				}
			}
		}
	}
}
