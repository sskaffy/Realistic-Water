package me.skaffy.client.mixin;

import me.skaffy.client.jelly.JellyField;
import me.skaffy.client.jelly.JellyWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererJellyMixin {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void realisticWater$standInDent(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		var level = Minecraft.getInstance().level;
		if (level != null && JellyWorld.on()) {
			float sink = JellyField.sinkAt(level, state.x, state.y, state.z, partialTicks);
			if (sink > 0.0F) {
				var below = level.getBlockState(net.minecraft.core.BlockPos.containing(state.x, state.y - 0.02, state.z));
				double rest = below.getBlock() instanceof me.skaffy.block.WobblyHoneyBlock ? me.skaffy.block.WobblyBlocks.topDent(entity, true)
					: below.getBlock() instanceof me.skaffy.block.WobblySlimeBlock ? me.skaffy.block.WobblyBlocks.topDent(entity, false) : 0.0;
				state.y -= sink - rest;
			}
		}
	}
}
