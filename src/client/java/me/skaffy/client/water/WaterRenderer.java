package me.skaffy.client.water;

import java.util.List;
import java.util.Map;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkTex;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;

public final class WaterRenderer implements AutoCloseable {
	private static final int RGBA32F = VK10.VK_FORMAT_R32G32B32A32_SFLOAT;

	private final int thicknessFormat;
	private final GraphicsProgram meshFront;
	private final GraphicsProgram dropletFront;
	private final GraphicsProgram meshThickness;
	private final GraphicsProgram dropletThickness;
	private final GraphicsProgram whitewaterProgram;
	private final GraphicsProgram compositeProgram;
	private final Sampler nearest = new Sampler(false);
	private final Sampler linear = new Sampler(true);

	private int width;
	private int height;
	private VkTex sceneDepth;
	private VkTex fluidZ;
	private VkTex sceneColor;
	private VkTex gbuffer;
	private VkTex thickness;
	private VkTex foam;

	public WaterRenderer() {
		this.thicknessFormat = blendable(VkTex.R32F) ? VkTex.R32F : VkTex.R16F;
		int U = Desc.UBO;
		int S = Desc.SSBO;
		int T = Desc.TEX;
		GraphicsProgram.State front = new GraphicsProgram.State(RGBA32F, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE);
		GraphicsProgram.State add = new GraphicsProgram.State(this.thicknessFormat, VK10.VK_FORMAT_UNDEFINED, false, false, VK10.VK_COMPARE_OP_ALWAYS,
			GraphicsProgram.Blend.ADD);
		Map<String, String> droplets = Map.of("DROPLETS", "1", "VIEW_BINDING", "3");
		this.meshFront = new GraphicsProgram("mesh.vert", "mesh_front.frag", Map.of(), front, U, S, S, S);
		this.dropletFront = new GraphicsProgram("particle.vert", "droplet_front.frag", droplets, front, U, S, S, S);
		this.meshThickness = new GraphicsProgram("mesh.vert", "mesh_thickness.frag", Map.of(), add, U, S, S, S, T);
		this.dropletThickness = new GraphicsProgram("particle.vert", "droplet_thickness.frag", droplets, add, U, S, S, S, T);
		this.whitewaterProgram = new GraphicsProgram("particle.vert", "whitewater.frag", Map.of("VIEW_BINDING", "4"),
			new GraphicsProgram.State(VkTex.RGBA16F, VK10.VK_FORMAT_UNDEFINED, false, false, VK10.VK_COMPARE_OP_ALWAYS, GraphicsProgram.Blend.ADD),
			U, S, T, T, S);
		this.compositeProgram = new GraphicsProgram("fullscreen.vert", "composite.frag", Map.of(),
			new GraphicsProgram.State(VkTex.RGBA8, VkTex.D32, true, true, VK10.VK_COMPARE_OP_ALWAYS, GraphicsProgram.Blend.NONE), U, T, T, T, T, T, S);
	}

	private static boolean blendable(int format) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFormatProperties props = VkFormatProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceFormatProperties(Vk.vk().getPhysicalDevice(), format, props);
			return (props.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT) != 0;
		}
	}

	private void ensureTargets(int w, int h) {
		if (w == this.width && h == this.height && this.sceneDepth != null) {
			return;
		}
		this.releaseTargets();
		this.width = w;
		this.height = h;
		this.sceneDepth = new VkTex(VkTex.D32, w, h, VkTex.COPY_DST | VkTex.SAMPLED);
		this.fluidZ = new VkTex(VkTex.D32, w, h, VkTex.COPY_DST | VkTex.DEPTH);
		this.sceneColor = new VkTex(VkTex.RGBA8, w, h, VkTex.COPY_DST | VkTex.SAMPLED);
		this.gbuffer = new VkTex(RGBA32F, w, h, VkTex.COLOR | VkTex.SAMPLED);
		this.thickness = new VkTex(this.thicknessFormat, w, h, VkTex.COLOR | VkTex.SAMPLED);
		this.foam = new VkTex(VkTex.RGBA16F, w, h, VkTex.COLOR | VkTex.SAMPLED);
	}

	private void releaseTargets() {
		for (VkTex t : new VkTex[]{this.sceneDepth, this.fluidZ, this.sceneColor, this.gbuffer, this.thickness, this.foam}) {
			if (t != null) {
				Vk.destroyLater(t);
			}
		}
		this.sceneDepth = null;
	}

	public void prepare(int w, int h) {
		this.ensureTargets(w, h);
	}

	public record Body(WaterRegion region, Desc ubo) {
	}

	public void record(VkCommandBuffer cb, List<Body> bodies, Desc view, long mainColorImage, long mainColorView, long mainDepthImage,
		long mainDepthView) {
		int w = this.width;
		int h = this.height;
		for (Body body : bodies) {
			WaterRegion r = body.region();
			r.mesher.build(cb, body.ubo(), r.currentParticles(), r.counters, WaterRegion.C_FLUID + r.cur, r.args, r.staticSolid, r.mesher.fineBox(r.box),
				WaterSettings.sheetFill);
		}

		VkTex.copy(cb, mainDepthImage, this.sceneDepth.image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, w, h);
		VkTex.copy(cb, mainDepthImage, this.fluidZ.image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, w, h);
		VkTex.copy(cb, mainColorImage, this.sceneColor.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, w, h);
		Vk.barrier(cb);
		Desc depthTex = Desc.tex(this.sceneDepth, this.nearest.handle);

		GraphicsProgram.begin(cb, w, h, this.gbuffer.view, new float[]{0, 0, 0, 0}, this.fluidZ.view);
		for (Body body : bodies) {
			WaterRegion r = body.region();
			float radius = WaterSettings.particleRadius / r.res;
			this.meshFront.bind(cb, body.ubo(), Desc.ssbo(r.mesher.verts), Desc.ssbo(r.mesher.quads), view);
			this.meshFront.drawIndirect(cb, r.mesher.meshCounters, SurfaceMesher.DRAW_ARGS_OFFSET);
			this.dropletFront.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.mesher.densA), view);
			this.dropletFront.push(cb, Program.f(radius), Program.f(0.0F), Program.f(0.15F));
			this.dropletFront.drawIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DRAW);
		}
		GraphicsProgram.end(cb);

		GraphicsProgram.begin(cb, w, h, this.thickness.view, new float[]{0, 0, 0, 0}, 0L);
		for (Body body : bodies) {
			WaterRegion r = body.region();
			float radius = WaterSettings.particleRadius / r.res;
			this.meshThickness.bind(cb, body.ubo(), Desc.ssbo(r.mesher.verts), Desc.ssbo(r.mesher.quads), view, depthTex);
			this.meshThickness.drawIndirect(cb, r.mesher.meshCounters, SurfaceMesher.DRAW_ARGS_OFFSET);
			this.dropletThickness.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.mesher.densA), view, depthTex);
			this.dropletThickness.push(cb, Program.f(radius), Program.f(0.0F), Program.f(0.15F));
			this.dropletThickness.drawIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DRAW);
		}
		GraphicsProgram.end(cb);
		Vk.barrier(cb);

		GraphicsProgram.begin(cb, w, h, this.foam.view, new float[]{0, 0, 0, 0}, 0L);
		if (WaterSettings.whitewater) {
			for (Body body : bodies) {
				WaterRegion r = body.region();
				this.whitewaterProgram.bind(cb, body.ubo(), Desc.ssbo(r.currentWhitewater()), depthTex, Desc.tex(this.gbuffer, this.nearest.handle), view);
				this.whitewaterProgram.push(cb, Program.f(WaterSettings.wwRadius), Program.f(0.5F), Program.f(0.3F), Program.f(WaterSettings.wwMaxPixels),
					Program.f(WaterSettings.foamSize));
				this.whitewaterProgram.drawIndirect(cb, r.args, WaterRegion.ARGS_WW_DRAW);
			}
		}
		GraphicsProgram.end(cb);
		Vk.barrier(cb);

		GraphicsProgram.begin(cb, w, h, mainColorView, null, mainDepthView);
		this.compositeProgram.bind(cb, bodies.getFirst().ubo(),
			Desc.tex(this.gbuffer, this.nearest.handle),
			Desc.tex(this.thickness, this.linear.handle),
			Desc.tex(this.sceneColor, this.linear.handle),
			depthTex,
			Desc.tex(this.foam, this.linear.handle),
			view);
		this.compositeProgram.draw(cb, 3, 1);
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.releaseTargets();
		for (AutoCloseable c : new AutoCloseable[]{
			this.meshFront, this.dropletFront, this.meshThickness, this.dropletThickness, this.whitewaterProgram, this.compositeProgram,
			this.nearest, this.linear
		}) {
			try {
				c.close();
			} catch (Exception ignored) {
			}
		}
	}
}
