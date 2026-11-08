package me.skaffy.client.jelly;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class GelItemRenderer implements NoDataSpecialModelRenderer {
	private final JellyKind kind;

	private GelItemRenderer(JellyKind kind) {
		this.kind = kind;
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
		GelItems.capture(this.kind, poseStack, lightCoords);
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		for (int i = 0; i < 8; i++) {
			output.accept(new Vector3f(i & 1, (i >> 1) & 1, (i >> 2) & 1));
		}
	}

	public record Unbaked(String kind) implements NoDataSpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = Codec.STRING.fieldOf("kind").xmap(Unbaked::new, Unbaked::kind);

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}

		@Override
		public GelItemRenderer bake(SpecialModelRenderer.BakingContext context) {
			return new GelItemRenderer("honey".equals(this.kind) ? JellyKind.HONEY : JellyKind.SLIME);
		}
	}
}
