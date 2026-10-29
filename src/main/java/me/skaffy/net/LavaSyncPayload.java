package me.skaffy.net;

import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.block.RealisticLeavesBlock;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.fluid.FluidMixing;
import me.skaffy.fluid.RealisticLavaFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

public record LavaSyncPayload(long[] positions, byte[] amounts) implements CustomPacketPayload {
	public static final Type<LavaSyncPayload> TYPE = new Type<>(RealisticWater.id("lava_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LavaSyncPayload> CODEC = StreamCodec.of(LavaSyncPayload::write, LavaSyncPayload::read);
	public static final int MAX_ENTRIES = 32768;

	private static void write(RegistryFriendlyByteBuf buf, LavaSyncPayload payload) {
		buf.writeVarInt(payload.positions.length);
		for (int i = 0; i < payload.positions.length; i++) {
			buf.writeLong(payload.positions[i]);
			buf.writeByte(payload.amounts[i]);
		}
	}

	private static LavaSyncPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
		long[] positions = new long[n];
		byte[] amounts = new byte[n];
		for (int i = 0; i < n; i++) {
			positions[i] = buf.readLong();
			amounts[i] = buf.readByte();
		}
		return new LavaSyncPayload(positions, amounts);
	}

	@Override
	public Type<LavaSyncPayload> type() {
		return TYPE;
	}

	public static BlockState stateFor(int amount) {
		BlockState full = ModContent.REALISTIC_LAVA_BLOCK.defaultBlockState().setValue(RealisticWaterBlock.INFINITE, false);
		return amount >= 8 ? full : full.setValue(LiquidBlock.LEVEL, 8 - Math.max(1, amount));
	}

	private static final int SYNC_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	public void apply(ServerPlayer player) {
		applyIn(player.level(), player, this.positions, this.amounts);
	}

	public static void applyIn(ServerLevel level, @Nullable ServerPlayer player, long[] positions, byte[] amounts) {
		for (int i = 0; i < positions.length; i++) {
			BlockPos pos = BlockPos.of(positions[i]);
			if (!level.isLoaded(pos) || player != null && pos.distSqr(player.blockPosition()) > 512 * 512) {
				continue;
			}
			BlockState current = level.getBlockState(pos);
			if (RealisticWaterBlock.isInfinite(current)) {
				continue;
			}
			boolean ours = RealisticLavaFluid.isRealistic(current.getFluidState()) && current.getBlock() == ModContent.REALISTIC_LAVA_BLOCK;
			int amount = amounts[i];
			if (amount <= 0) {
				if (ours) {
					level.setBlock(pos, Blocks.AIR.defaultBlockState(), SYNC_FLAGS);
				}
			} else if (ours || current.isAir() || current.getBlock() instanceof RealisticLeavesBlock) {
				BlockState target = stateFor(amount);
				if (current != target) {
					level.setBlock(pos, target, SYNC_FLAGS);
					FluidMixing.solidifyIfTouchingWater(level, pos);
				}
			}
		}
	}
}
