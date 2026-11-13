package me.skaffy.client.fire;

import me.skaffy.client.water.WaterSettings;
import me.skaffy.net.FireSpeedPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.jspecify.annotations.Nullable;

public final class FireSpeedSync {
	private static @Nullable ClientPacketListener sentTo;
	private static float sent = -1.0F;

	private FireSpeedSync() {
	}

	public static void tick(Minecraft mc) {
		ClientPacketListener connection = mc.getConnection();
		if (connection == null || mc.level == null) {
			sentTo = null;
			return;
		}
		float wanted = WaterSettings.fireSpreadSpeed;
		if ((connection != sentTo || sent != wanted) && ClientPlayNetworking.canSend(FireSpeedPayload.TYPE)) {
			ClientPlayNetworking.send(new FireSpeedPayload(wanted));
			sentTo = connection;
			sent = wanted;
		}
	}
}
