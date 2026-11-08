package me.skaffy.client.mixin;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.client.renderer.StagedVertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StagedVertexBuffer.Draw.class)
public interface StagedDrawAccessor {
	@Accessor("format")
	VertexFormat realisticWater$format();

	@Accessor("primitiveTopology")
	PrimitiveTopology realisticWater$topology();
}
