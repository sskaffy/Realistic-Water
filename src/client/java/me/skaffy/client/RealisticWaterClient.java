package me.skaffy.client;

import me.skaffy.client.water.WaterCommands;
import me.skaffy.client.water.WaterWorld;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public class RealisticWaterClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		WaterWorld water = WaterWorld.get();
		ClientCommandRegistrationCallback.EVENT.register(WaterCommands::register);
		ClientTickEvents.END_CLIENT_TICK.register(water::tick);
		ClientChunkEvents.CHUNK_LOAD.register(water::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(water::onChunkUnload);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> water.clear());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> water.shutdown());
	}
}
