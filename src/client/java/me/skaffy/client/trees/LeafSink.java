package me.skaffy.client.trees;

import me.skaffy.client.water.WaterSettings;
import me.skaffy.net.LeafModePayload;
import me.skaffy.trees.LeafSinking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.jspecify.annotations.Nullable;

public final class LeafSink {
	private static @Nullable ClientPacketListener sentTo;
	private static boolean sent;

	private LeafSink() {
	}

	public static boolean wanted() {
		return WaterSettings.realisticTreeLeaves && WaterSettings.treeLeafSinking;
	}

	public static void tick(Minecraft mc) {
		boolean on = wanted();
		LeafSinking.localOn = on;
		LeafSinking.localTop = 0.8F;
		ClientPacketListener connection = mc.getConnection();
		if (connection == null || mc.level == null) {
			sentTo = null;
		} else if ((connection != sentTo || sent != on) && ClientPlayNetworking.canSend(LeafModePayload.TYPE)) {
			ClientPlayNetworking.send(new LeafModePayload(on));
			sentTo = connection;
			sent = on;
		}
	}
}
