package me.skaffy.client;

import me.skaffy.ModContent;
import me.skaffy.client.water.WaterCommands;
import me.skaffy.client.water.WaterWorld;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.minecraft.client.color.block.BlockTintSources;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

public class RealisticWaterClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		registerInvisibleFluid();
		WaterWorld water = WaterWorld.get();
		ClientCommandRegistrationCallback.EVENT.register(WaterCommands::register);
		ClientTickEvents.END_CLIENT_TICK.register(water::tick);
		ClientChunkEvents.CHUNK_LOAD.register(water::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(water::onChunkUnload);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> water.clear());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> water.shutdown());
	}

	private static void registerInvisibleFluid() {
		FluidModel.Unbaked waterModel = new FluidModel.Unbaked(
			new Material(Identifier.withDefaultNamespace("block/water_still")),
			new Material(Identifier.withDefaultNamespace("block/water_flow")),
			new Material(Identifier.withDefaultNamespace("block/water_overlay")),
			BlockTintSources.water()
		);
		FluidRenderHandler invisible = new FluidRenderHandler() {
			@Override
			public void renderFluid(
				FluidRenderer fluidRenderer, BlockPos pos, BlockAndTintGetter level, FluidRenderer.Output output, BlockState blockState, FluidState fluidState
			) {
			}
		};
		FluidRenderingRegistry.register(ModContent.REALISTIC_WATER, ModContent.FLOWING_REALISTIC_WATER, waterModel, invisible);
	}
}
