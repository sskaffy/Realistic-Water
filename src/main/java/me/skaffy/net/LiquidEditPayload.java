package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.item.CustomLiquidBucketItem;
import me.skaffy.liquid.CustomLiquid;
import me.skaffy.liquid.CustomLiquids;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public record LiquidEditPayload(int action, CustomLiquid liquid, boolean newSlot) implements CustomPacketPayload {
	public static final int SAVE = 0;
	public static final int DELETE = 1;
	public static final int BUCKET = 2;
	public static final int INFINITE_BUCKET = 3;

	public static final Type<LiquidEditPayload> TYPE = new Type<>(RealisticWater.id("liquid_edit"));
	public static final StreamCodec<RegistryFriendlyByteBuf, LiquidEditPayload> CODEC = StreamCodec.of(LiquidEditPayload::write, LiquidEditPayload::read);

	private static void write(RegistryFriendlyByteBuf buf, LiquidEditPayload payload) {
		buf.writeByte(payload.action);
		buf.writeBoolean(payload.newSlot);
		CustomLiquid.write(buf, payload.liquid);
	}

	private static LiquidEditPayload read(RegistryFriendlyByteBuf buf) {
		int action = buf.readByte();
		boolean newSlot = buf.readBoolean();
		return new LiquidEditPayload(action, CustomLiquid.read(buf), newSlot);
	}

	@Override
	public Type<LiquidEditPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		CustomLiquids liquids = CustomLiquids.of(server);
		switch (this.action) {
			case SAVE -> {
				int slot = this.newSlot ? liquids.freeSlot() : this.liquid.slot();
				if (slot < 0) {
					player.sendSystemMessage(Component.literal("Every liquid slot of this world is in use (" + CustomLiquid.MAX_SLOTS
						+ "). Delete a liquid you no longer need first."));
					return;
				}
				liquids.put(this.liquid.withSlot(slot));
				CustomLiquids.sendToAll(server);
			}
			case DELETE -> {
				if (liquids.remove(this.liquid.slot())) {
					CustomLiquids.sendToAll(server);
				}
			}
			case BUCKET, INFINITE_BUCKET -> {
				CustomLiquid known = liquids.at(this.liquid.slot());
				if (known == null) {
					return;
				}
				ItemStack stack = CustomLiquidBucketItem.stack(known.slot(), known, this.action == INFINITE_BUCKET);
				player.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
			}
			default -> {
			}
		}
	}
}
