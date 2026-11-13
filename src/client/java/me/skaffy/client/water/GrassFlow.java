package me.skaffy.client.water;

import java.util.List;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.VkBuf;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class GrassFlow implements AutoCloseable {
	private final ComputeProgram splat = new ComputeProgram("grass_water.comp", Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO);

	void record(VkCommandBuffer cb, VkBuf flow, List<WaterRenderer.Body> bodies) {
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			if (r.material != Material.WATER || r.background != null || !r.hasWindow) {
				continue;
			}
			this.splat.bind(cb, body.ubo(), r.currentParticles().part0(), Desc.ssbo(r.counters), Desc.ssbo(flow), r.currentParticles().part1());
			this.splat.push(cb, WaterRegion.C_FLUID + r.cur, r.res, 0, 0, r.ox, r.oy, r.oz, 0);
			this.splat.dispatchIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DISPATCH);
		}
	}

	@Override
	public void close() {
		this.splat.close();
	}
}
