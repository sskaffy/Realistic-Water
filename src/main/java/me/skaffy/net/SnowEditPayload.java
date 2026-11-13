package me.skaffy.net;

import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticSnowBlock;
import me.skaffy.block.SnowOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public record SnowEditPayload(long[] positions, byte[] deltas, byte[] packed) implements CustomPacketPayload {
	public static final Type<SnowEditPayload> TYPE = new Type<>(RealisticWater.id("snow_edit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SnowEditPayload> CODEC = StreamCodec.of(SnowEditPayload::write, SnowEditPayload::read);
	public static final int MAX_ENTRIES = 4096;
	private static final int FLAGS = Block.UPDATE_ALL;

	private static void write(RegistryFriendlyByteBuf buf, SnowEditPayload p) {
		buf.writeVarInt(p.positions.length);
		for (int i = 0; i < p.positions.length; i++) {
			buf.writeLong(p.positions[i]);
			buf.writeByte(p.deltas[i]);
			buf.writeByte(p.packed[i]);
		}
	}

	private static SnowEditPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] deltas = new byte[n];
		byte[] packed = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			deltas[i] = buf.readByte();
			packed[i] = buf.readByte();
		}
		return new SnowEditPayload(positions, deltas, packed);
	}

	@Override
	public Type<SnowEditPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		ServerLevel level = player.level();
		for (int i = 0; i < this.positions.length; i++) {
			BlockPos pos = BlockPos.of(this.positions[i]);
			if (!level.isLoaded(pos) || pos.distSqr(player.blockPosition()) > 256 * 256) {
				continue;
			}
			change(level, pos, Math.clamp(this.deltas[i], -8, 8), this.packed[i] != 0);
		}
	}

	private static void change(ServerLevel level, BlockPos pos, int delta, boolean packed) {
		BlockState state = level.getBlockState(pos);
		int layers = SnowMeltPayload.snowLayers(state);
		boolean isSnow = layers > 0 && !state.is(Blocks.POWDER_SNOW);
		if (delta == 0) {
			if (isSnow && packed && state.getBlock() instanceof RealisticSnowBlock && !state.getValue(RealisticSnowBlock.PACKED)) {
				level.setBlock(pos, state.setValue(RealisticSnowBlock.PACKED, true).setValue(RealisticSnowBlock.ORIGIN, SnowOrigin.PLACED), FLAGS);
			}
			return;
		}
		if (!isSnow) {
			if (delta < 0 || !(state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty())) {
				return;
			}
			layers = 0;
		}
		int left = layers + delta;
		BlockState snow = ModContent.REALISTIC_SNOW.defaultBlockState();
		if (left <= 0) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), FLAGS);
			return;
		}
		int here = Math.min(left, RealisticSnowBlock.MAX_LAYERS);
		BlockState next = snow.setValue(RealisticSnowBlock.LAYERS, here).setValue(RealisticSnowBlock.PACKED, packed && delta < 0);
		if (!isSnow && !next.canSurvive(level, pos)) {
			return;
		}
		level.setBlock(pos, next, FLAGS);
		if (left > here) {
			change(level, pos.above(), left - here, false);
		}
	}
}
