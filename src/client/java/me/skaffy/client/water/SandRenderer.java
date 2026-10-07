package me.skaffy.client.water;

import java.util.List;
import java.util.Map;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkTex;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

final class SandRenderer implements AutoCloseable {
	private final GraphicsProgram surface;
	private final GraphicsProgram grains;

	SandRenderer() {
		int U = Desc.UBO;
		int S = Desc.SSBO;
		GraphicsProgram.State opaque = new GraphicsProgram.State(VkTex.RGBA8, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER,
			GraphicsProgram.Blend.NONE);
		this.surface = new GraphicsProgram("mesh.vert", "sand_mesh.frag", Map.of(), opaque, U, S, S, S);
		this.grains = new GraphicsProgram("grain.vert", "grain.frag", Map.of(), opaque, U, S, S);
	}

	void record(VkCommandBuffer cb, List<WaterRenderer.Body> bodies, int width, int height, long mainColorView, long mainDepthView) {
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			r.mesher.build(cb, body.ubo(), r.currentParticles(), r.counters, WaterRegion.C_FLUID + r.cur, r.args, r.staticSolid, r.mesher.fineBox(r.box),
				false);
		}
		Vk.barrier(cb);
		GraphicsProgram.begin(cb, width, height, mainColorView, null, mainDepthView);
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			this.surface.bind(cb, body.ubo(), Desc.ssbo(r.mesher.verts), Desc.ssbo(r.mesher.quads), Desc.ssbo(r.mesher.densA));
			this.surface.drawIndirect(cb, r.mesher.meshCounters, SurfaceMesher.DRAW_ARGS_OFFSET);
			this.grains.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.mesher.densA));
			this.grains.drawIndirect(cb, r.args, WaterRegion.ARGS_GRAIN_DRAW);
		}
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.surface.close();
		this.grains.close();
	}
}
