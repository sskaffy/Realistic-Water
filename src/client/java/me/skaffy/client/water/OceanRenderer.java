package me.skaffy.client.water;

import java.util.List;
import java.util.Map;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

final class OceanRenderer implements AutoCloseable {
	private static final int RGBA32F = VK10.VK_FORMAT_R32G32B32A32_SFLOAT;

	record Draw(OceanRenderer renderer, Desc ubo, VkBuf entries, int count, boolean background, List<WaterRegion> zones, int camX, int camY, int camZ,
		@Nullable VkBuf colors) {
		void front(VkCommandBuffer cb) {
			if (this.entries != null && this.count > 0) {
				this.renderer.front.bind(cb, this.ubo, Desc.ssbo(this.entries));
				this.renderer.front.draw(cb, this.count * 6, 1);
			}
			for (WaterRegion r : this.zones) {
				if (drawable(r)) {
					this.renderer.zoneFront.bind(cb, this.ubo, Desc.ssbo(r.seaHeights), Desc.ssbo(r.light()));
					this.push(this.renderer.zoneFront, cb, r);
					this.renderer.zoneFront.draw(cb, r.seaW * r.seaD * 6, 1);
				}
			}
		}

		void thickness(VkCommandBuffer cb, Desc sceneDepth) {
			if (this.entries != null && this.count > 0) {
				this.renderer.thickness.bind(cb, this.ubo, Desc.ssbo(this.entries), sceneDepth);
				this.renderer.thickness.draw(cb, this.count * 6, 1);
			}
			for (WaterRegion r : this.zones) {
				if (drawable(r)) {
					this.renderer.zoneThickness.bind(cb, this.ubo, Desc.ssbo(r.seaHeights), Desc.ssbo(r.light()), sceneDepth);
					this.push(this.renderer.zoneThickness, cb, r);
					this.renderer.zoneThickness.draw(cb, r.seaW * r.seaD * 6, 1);
				}
			}
		}

		private void push(GraphicsProgram program, VkCommandBuffer cb, WaterRegion r) {
			OceanZone z = r.ocean;
			program.push(cb,
				z.x0 - this.camX, z.z0 - this.camZ, r.seaW, r.seaD,
				r.res, z.seaY - this.camY, Program.f(Math.max(WaterSettings.oceanEdgeBand, 0.0F)), 0,
				r.ox - this.camX, r.oy - this.camY, r.oz - this.camZ, 0,
				r.sx, r.sy, r.sz, 0);
		}
	}

	static boolean drawable(WaterRegion r) {
		return r.ocean != null && r.seaHeights != null && r.seaW > 0 && r.light() != null && r.hasWindow;
	}

	private final GraphicsProgram front;
	private final GraphicsProgram thickness;
	private final GraphicsProgram zoneFront;
	private final GraphicsProgram zoneThickness;

	OceanRenderer(int thicknessFormat) {
		GraphicsProgram.State front = new GraphicsProgram.State(RGBA32F, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE,
			VkTex.RGBA16F);
		GraphicsProgram.State add = new GraphicsProgram.State(thicknessFormat, VK10.VK_FORMAT_UNDEFINED, false, false, VK10.VK_COMPARE_OP_ALWAYS,
			GraphicsProgram.Blend.ADD);
		Map<String, String> zone = Map.of("ZONE", "1");
		this.front = new GraphicsProgram("ocean.vert", "ocean_front.frag", Map.of(), front, Desc.UBO, Desc.SSBO);
		this.thickness = new GraphicsProgram("ocean.vert", "ocean_thickness.frag", Map.of(), add, Desc.UBO, Desc.SSBO, Desc.TEX);
		this.zoneFront = new GraphicsProgram("ocean_zone.vert", "ocean_front.frag", zone, front, Desc.UBO, Desc.SSBO, Desc.SSBO);
		this.zoneThickness = new GraphicsProgram("ocean_zone.vert", "ocean_thickness.frag", zone, add, Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.TEX);
	}

	@Override
	public void close() {
		this.front.close();
		this.thickness.close();
		this.zoneFront.close();
		this.zoneThickness.close();
	}
}
