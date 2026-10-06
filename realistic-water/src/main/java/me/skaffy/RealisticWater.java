package me.skaffy;

import me.skaffy.net.RemoveAllWaterPayload;
import me.skaffy.net.WaterSyncPayload;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RealisticWater implements ModInitializer {
	public static final String MOD_ID = "realistic-water";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModContent.init();
		PayloadTypeRegistry.serverboundPlay().register(WaterSyncPayload.TYPE, WaterSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RemoveAllWaterPayload.TYPE, RemoveAllWaterPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(WaterSyncPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(RemoveAllWaterPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (isAuthority(context.server(), player)) {
				context.server().execute(() -> {
					int removed = payload.apply(player);
					player.sendSystemMessage(Component.literal("Removed " + removed + " realistic water blocks"));
				});
			}
		});
	}

	private static boolean isAuthority(MinecraftServer server, ServerPlayer player) {
		return server.isSingleplayerOwner(player.nameAndId()) || server.getPlayerList().isOp(player.nameAndId());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
