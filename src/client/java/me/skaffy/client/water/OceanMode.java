package me.skaffy.client.water;

import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.client.compat.ShaderCompat;
import me.skaffy.client.vk.Vk;
import me.skaffy.net.OceanModePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

public final class OceanMode {
	private static volatile boolean active;
	private static volatile boolean packSea;
	private static boolean applied;
	private static boolean failed;
	private static @Nullable ClientPacketListener sentTo;
	private static boolean sentEnabled;
	private static int sentRadius = -1;
	private static float sentWaveHeight = -1.0F;

	private OceanMode() {
	}

	public static boolean active() {
		return active;
	}

	public static boolean isVanillaWater(FluidState fluid) {
		return fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER;
	}

	public static boolean packDrawsSea() {
		return packSea;
	}

	public static boolean drawsSea() {
		return active && !packSea;
	}

	public static float waveHeight() {
		return packSea && !packShowsWaves() ? 0.0F : Math.max(WaterSettings.oceanWaveHeight, 0.0F);
	}

	private static float serverWaveHeight() {
		return packSea ? 0.0F : Math.max(WaterSettings.oceanWaveHeight, 0.0F);
	}

	public static boolean packShowsWaves() {
		return ShaderCompat.VITRAIL && me.skaffy.client.compat.VitrailSea.hooked();
	}

	public static boolean hidesVanilla(BlockAndTintGetter level, BlockPos pos, FluidState fluid) {
		return drawsSea() && isVanillaWater(fluid) && !isVanillaWater(level.getBlockState(pos.above()).getFluidState());
	}

	public static boolean drawsOceanBlockAsVanilla(BlockState state) {
		return !drawsSea() && RealisticWaterBlock.isOcean(state);
	}

	public static void tick(Minecraft mc) {
		boolean wanted = WaterSettings.realisticOcean && !failed && Vk.tryDevice() != null;
		if (wanted != applied) {
			applied = wanted;
			active = wanted;
			WaterWorld.get().oceanToggled(wanted);
			if (mc.level != null) {
				mc.levelExtractor.allChanged();
			}
		}
		boolean pack = wanted && WaterSettings.oceanPackDrawsSea && ShaderCompat.packRunning();
		if (pack != packSea) {
			packSea = pack;
			if (mc.level != null) {
				mc.levelExtractor.allChanged();
			}
		}
		ClientPacketListener connection = mc.getConnection();
		if (connection == null || mc.level == null) {
			sentTo = null;
			return;
		}
		int radius = Math.max(WaterSettings.oceanServerRadius, 0);
		float height = serverWaveHeight();
		if (connection == sentTo && sentEnabled == wanted && sentRadius == radius && sentWaveHeight == height) {
			return;
		}
		if (!ClientPlayNetworking.canSend(OceanModePayload.TYPE)) {
			return;
		}
		ClientPlayNetworking.send(new OceanModePayload(wanted, radius, height));
		sentTo = connection;
		sentEnabled = wanted;
		sentRadius = radius;
		sentWaveHeight = height;
	}

	static void disable() {
		failed = true;
	}
}
