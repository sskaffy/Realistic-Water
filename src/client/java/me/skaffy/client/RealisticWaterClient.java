package me.skaffy.client;

import me.skaffy.ModContent;
import me.skaffy.client.realistic.ClientBodies;
import me.skaffy.client.realistic.ConverterScreen;
import me.skaffy.client.realistic.DynamicLights;
import me.skaffy.client.realistic.RealisticBlockRenderer;
import me.skaffy.client.realistic.RealisticBoatRenderer;
import me.skaffy.client.realistic.RealisticPlatform;
import me.skaffy.client.compat.ShaderCompat;
import me.skaffy.client.mixin.FogRendererAccessor;
import me.skaffy.client.water.SlimeFogEnvironment;
import me.skaffy.client.water.SteamParticle;
import net.fabricmc.fabric.api.client.particle.v1.ParticleProviderRegistry;
import me.skaffy.client.water.WaterCommands;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterSettingsScreen;
import me.skaffy.client.water.WaterWorld;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.gui.screens.MenuScreens;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import me.skaffy.net.BodyPushPayload;
import me.skaffy.net.SteamPayload;
import me.skaffy.realistic.RealisticBlockEntity;
import me.skaffy.net.GridBlockEntityPayload;
import me.skaffy.net.GridBlockEventPayload;
import me.skaffy.net.GridLevelEventPayload;
import me.skaffy.net.GridSoundPayload;
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
		WaterSettings.load();
		ShaderCompat.logEnvironment();
		registerInvisibleFluid();
		FogRendererAccessor.realisticWater$environments().addFirst(new SlimeFogEnvironment());
		EntityRendererRegistry.register(ModContent.REALISTIC_BLOCK_ENTITY, RealisticBlockRenderer::new);
		EntityRendererRegistry.register(ModContent.REALISTIC_BOAT, RealisticBoatRenderer::new);
		MenuScreens.register(ModContent.CONVERTER_MENU, ConverterScreen::new);
		WaterWorld water = WaterWorld.get();
		ClientCommandRegistrationCallback.EVENT.register(WaterCommands::register);
		ClientTickEvents.END_CLIENT_TICK.register(water::tick);
		ClientTickEvents.END_CLIENT_TICK.register(WaterSettingsScreen::tick);
		ClientTickEvents.START_CLIENT_TICK.register(ClientBodies::startTick);
		ClientTickEvents.END_CLIENT_TICK.register(ClientBodies::endTick);
		ClientTickEvents.END_CLIENT_TICK.register(RealisticPlatform::tick);
		ClientTickEvents.END_CLIENT_TICK.register(DynamicLights::tick);
		ClientPlayNetworking.registerGlobalReceiver(SteamPayload.TYPE, (payload, context) -> context.client().execute(
			() -> WaterWorld.get().addSteam(payload.x(), payload.y(), payload.z(), payload.eighths())));
		ClientPlayNetworking.registerGlobalReceiver(BodyPushPayload.TYPE, (payload, context) -> context.client().execute(() -> ClientBodies.receive(payload)));
		ClientPlayNetworking.registerGlobalReceiver(GridBlockEntityPayload.TYPE, (payload, context) -> context.client().execute(() -> {
			if (context.client().level != null && context.client().level.getEntity(payload.entityId()) instanceof RealisticBlockEntity body) {
				body.applyClientBlockEntity(payload.x(), payload.y(), payload.z(), payload.tag().orElse(null));
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(GridBlockEventPayload.TYPE, (payload, context) -> context.client().execute(() -> {
			if (context.client().level != null && context.client().level.getEntity(payload.entityId()) instanceof RealisticBlockEntity body) {
				body.applyClientBlockEvent(payload.x(), payload.y(), payload.z(), payload.paramA(), payload.paramB());
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(GridSoundPayload.TYPE, (payload, context) -> context.client().execute(() -> {
			if (context.client().level != null) {
				me.skaffy.client.realistic.GridEffects.sound(context.client().level, payload);
			}
		}));
		ClientPlayNetworking.registerGlobalReceiver(GridLevelEventPayload.TYPE, (payload, context) -> context.client().execute(() -> {
			if (context.client().level != null) {
				me.skaffy.client.realistic.GridEffects.levelEvent(context.client().level, payload);
			}
		}));
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
		FluidModel.Unbaked lavaModel = new FluidModel.Unbaked(
			new Material(Identifier.withDefaultNamespace("block/lava_still")),
			new Material(Identifier.withDefaultNamespace("block/lava_flow")),
			new Material(Identifier.withDefaultNamespace("block/lava_overlay")),
			BlockTintSources.constant(0xFFFFFF)
		);
		FluidRenderingRegistry.register(ModContent.REALISTIC_LAVA, ModContent.FLOWING_REALISTIC_LAVA, lavaModel, invisible);
		FluidModel.Unbaked slimeModel = new FluidModel.Unbaked(
			new Material(Identifier.withDefaultNamespace("block/water_still")),
			new Material(Identifier.withDefaultNamespace("block/water_flow")),
			new Material(Identifier.withDefaultNamespace("block/water_overlay")),
			BlockTintSources.constant(0x7EDB5C)
		);
		FluidRenderingRegistry.register(ModContent.REALISTIC_SLIME, ModContent.FLOWING_REALISTIC_SLIME, slimeModel, invisible);
		ParticleProviderRegistry.getInstance().register(ModContent.STEAM, SteamParticle.Provider::new);
	}
}
