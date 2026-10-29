package me.skaffy;

import me.skaffy.net.BodyPushPayload;
import me.skaffy.net.BodyStatePayload;
import me.skaffy.net.BreakRealisticPayload;
import me.skaffy.net.FluidMixPayload;
import me.skaffy.net.BoilingWaterPayload;
import me.skaffy.fluid.BoilingWater;
import me.skaffy.net.FlowPayload;
import me.skaffy.net.GridBlockEntityPayload;
import me.skaffy.net.GridBlockEventPayload;
import me.skaffy.net.GridLevelEventPayload;
import me.skaffy.net.GridSoundPayload;
import me.skaffy.net.UseRealisticPayload;
import me.skaffy.realistic.GridSpace;
import me.skaffy.realistic.RealisticPhysics;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import me.skaffy.net.LavaSyncPayload;
import me.skaffy.net.LeavesSyncPayload;
import me.skaffy.net.RemoveAllWaterPayload;
import me.skaffy.net.SandSyncPayload;
import me.skaffy.net.SlimeSyncPayload;
import me.skaffy.net.SyncMaterial;
import me.skaffy.net.WaterSyncPayload;
import me.skaffy.net.PortalTransitPayload;
import me.skaffy.net.BackgroundSyncPayload;
import me.skaffy.net.SteamPayload;
import me.skaffy.portal.PortalTransit;
import net.minecraft.network.chat.Component;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
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
		PayloadTypeRegistry.serverboundPlay().register(SandSyncPayload.TYPE, SandSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LavaSyncPayload.TYPE, LavaSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SlimeSyncPayload.TYPE, SlimeSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LeavesSyncPayload.TYPE, LeavesSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RemoveAllWaterPayload.TYPE, RemoveAllWaterPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(FluidMixPayload.TYPE, FluidMixPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BoilingWaterPayload.TYPE, BoilingWaterPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PortalTransitPayload.TYPE, PortalTransitPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BackgroundSyncPayload.TYPE, BackgroundSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BreakRealisticPayload.TYPE, BreakRealisticPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(UseRealisticPayload.TYPE, UseRealisticPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(FlowPayload.TYPE, FlowPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BodyStatePayload.TYPE, BodyStatePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BodyPushPayload.TYPE, BodyPushPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BodyPushPayload.TYPE, BodyPushPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GridBlockEntityPayload.TYPE, GridBlockEntityPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GridBlockEventPayload.TYPE, GridBlockEventPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GridSoundPayload.TYPE, GridSoundPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(GridLevelEventPayload.TYPE, GridLevelEventPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SteamPayload.TYPE, SteamPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(BodyStatePayload.TYPE, (payload, context) -> context.server().execute(() -> payload.apply(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(BodyPushPayload.TYPE, (payload, context) -> context.server().execute(() -> payload.apply(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(FlowPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerTickEvents.END_LEVEL_TICK.register(RealisticPhysics::tick);
		ServerTickEvents.END_LEVEL_TICK.register(BoilingWater::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			RealisticPhysics.clear();
			GridSpace.clear();
			PortalTransit.clear();
			BoilingWater.clear();
		});
		ServerLevelEvents.UNLOAD.register((server, level) -> {
			RealisticPhysics.forget(level);
			GridSpace.forget(level);
			BoilingWater.forget(level);
		});
		ServerPlayNetworking.registerGlobalReceiver(BreakRealisticPayload.TYPE, (payload, context) -> context.server().execute(() -> payload.apply(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(UseRealisticPayload.TYPE, (payload, context) -> context.server().execute(() -> payload.apply(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(WaterSyncPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(SandSyncPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(LavaSyncPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(SlimeSyncPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(LeavesSyncPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(BackgroundSyncPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(PortalTransitPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(BoilingWaterPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(FluidMixPayload.TYPE, (payload, context) -> {
			if (isAuthority(context.server(), context.player())) {
				context.server().execute(() -> payload.apply(context.player()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(RemoveAllWaterPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
				context.server().execute(() -> {
					int removed = payload.apply(player);
					player.sendSystemMessage(Component.literal("Removed " + removed + " realistic " + SyncMaterial.label(payload.material()) + " blocks"));
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
