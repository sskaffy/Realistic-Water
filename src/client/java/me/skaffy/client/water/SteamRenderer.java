package me.skaffy.client.water;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.VkTex;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

final class SteamRenderer implements AutoCloseable {
	private final Map<Integer, GraphicsProgram> steam = new HashMap<>();
	private final Sampler linear = new Sampler(true);

	private GraphicsProgram steam(int colorFormat) {
		return this.steam.computeIfAbsent(colorFormat, format -> new GraphicsProgram("steam.vert", "steam.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, false, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.PREMULTIPLIED), Desc.UBO,
			Desc.SSBO, Desc.SSBO, Desc.TEX));
	}

	void record(VkCommandBuffer cb, List<WaterRenderer.Body> bodies, int width, int height, int colorFormat, long mainColorView, long mainDepthView,
		long lightmap) {
		GraphicsProgram steam = this.steam(colorFormat);
		GraphicsProgram.begin(cb, width, height, mainColorView, null, mainDepthView);
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			steam.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.light()), Desc.tex(lightmap, this.linear.handle));
			steam.drawIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DRAW);
		}
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.steam.values().forEach(GraphicsProgram::close);
		this.linear.close();
	}
}
