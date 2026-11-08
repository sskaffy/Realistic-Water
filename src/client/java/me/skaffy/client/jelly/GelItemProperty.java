package me.skaffy.client.jelly;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperty;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

public record GelItemProperty() implements ConditionalItemModelProperty {
	public static final MapCodec<GelItemProperty> MAP_CODEC = MapCodec.unit(new GelItemProperty());

	@Override
	public boolean get(ItemStack itemStack, @Nullable ClientLevel level, @Nullable LivingEntity owner, int seed, ItemDisplayContext displayContext) {
		return GelItems.drawnAsGel(displayContext);
	}

	@Override
	public MapCodec<GelItemProperty> type() {
		return MAP_CODEC;
	}

	public static void register() {
		net.minecraft.client.renderer.item.properties.conditional.ConditionalItemModelProperties.ID_MAPPER.put(
			me.skaffy.RealisticWater.id("drawn_as_gel"), MAP_CODEC);
		net.minecraft.client.renderer.special.SpecialModelRenderers.ID_MAPPER.put(me.skaffy.RealisticWater.id("wobbly_gel"), GelItemRenderer.Unbaked.MAP_CODEC);
	}
}
