package me.skaffy.client.realistic;

import me.skaffy.net.UseRealisticPayload;
import me.skaffy.realistic.RealisticBoatEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;

public final class RealisticUse {
	private RealisticUse() {
	}

	public static boolean start(Minecraft mc) {
		if (!(mc.hitResult instanceof RealisticHit hit) || mc.player == null || mc.player.isSpectator()
			|| hit.getEntity() instanceof RealisticBoatEntity) {
			return false;
		}
		if (!ClientPlayNetworking.canSend(UseRealisticPayload.TYPE)) {
			me.skaffy.RealisticWater.LOGGER.warn("[use] channel not open, falling back to vanilla placement");
			return false;
		}
		boolean offhand = mc.player.getMainHandItem().isEmpty() && !mc.player.getOffhandItem().isEmpty();
		ClientPlayNetworking.send(new UseRealisticPayload(hit.getEntity().getId(), hit.x, hit.y, hit.z, hit.face,
			(float) hit.local.x, (float) hit.local.y, (float) hit.local.z, offhand));
		mc.player.swing(offhand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		return true;
	}
}
