package me.skaffy.net;

import java.util.ArrayList;
import java.util.List;
import me.skaffy.RealisticWater;
import me.skaffy.liquid.CustomLiquid;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record LiquidDefsPayload(List<CustomLiquid> liquids) implements CustomPacketPayload {
	public static final Type<LiquidDefsPayload> TYPE = new Type<>(RealisticWater.id("liquid_defs"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LiquidDefsPayload> CODEC = StreamCodec.of(LiquidDefsPayload::write, LiquidDefsPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, LiquidDefsPayload payload) {
		buf.writeVarInt(payload.liquids.size());
		for (CustomLiquid liquid : payload.liquids) {
			CustomLiquid.write(buf, liquid);
		}
	}

	private static LiquidDefsPayload read(RegistryFriendlyByteBuf buf) {
		int n = Math.min(buf.readVarInt(), CustomLiquid.MAX_SLOTS);
		List<CustomLiquid> liquids = new ArrayList<>(n);
		for (int k = 0; k < n; k++) {
			liquids.add(CustomLiquid.read(buf));
		}
		return new LiquidDefsPayload(liquids);
	}

	@Override
	public Type<LiquidDefsPayload> type() {
		return TYPE;
	}
}
