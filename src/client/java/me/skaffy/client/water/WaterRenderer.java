package me.skaffy.client.water;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;

public final class WaterRenderer implements AutoCloseable {
	private static final int RGBA32F = VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
	private static final Identifier SLIME_TEXTURE = Identifier.withDefaultNamespace("textures/block/slime_block.png");

	private final int thicknessFormat;
	private final GraphicsProgram meshFront;
	private final GraphicsProgram dropletFront;
	private final GraphicsProgram meshThickness;
	private final GraphicsProgram dropletThickness;
	private final GraphicsProgram whitewaterProgram;
	private final Map<Integer, GraphicsProgram> composites = new HashMap<>();
	private final ComputeProgram dropletCull;
	private final Sampler nearest = new Sampler(false);
	private final Sampler linear = new Sampler(true);
	private final VkBuf slimeTexture = SandRenderer.loadTexture(SLIME_TEXTURE, 0xFF7EBF6E);

	private int width;
	private int height;
	private int colorFormat;
	private VkTex sceneDepth;
	private VkTex fluidZ;
	private VkTex sceneColor;
	private VkTex gbuffer;
	private VkTex materialId;
	private VkTex thickness;
	private VkTex foam;
	private VkTex foamSurface;

	public WaterRenderer() {
		this.thicknessFormat = blendable(VkTex.R32F) ? VkTex.R32F : VkTex.R16F;
		int U = Desc.UBO;
		int S = Desc.SSBO;
		int T = Desc.TEX;
		GraphicsProgram.State front = new GraphicsProgram.State(RGBA32F, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE,
			VkTex.RGBA16F);
		GraphicsProgram.State add = new GraphicsProgram.State(this.thicknessFormat, VK10.VK_FORMAT_UNDEFINED, false, false, VK10.VK_COMPARE_OP_ALWAYS,
			GraphicsProgram.Blend.ADD);
		Map<String, String> droplets = Map.of("DROPLETS", "1", "VIEW_BINDING", "3");
		this.meshFront = new GraphicsProgram("mesh.vert", "mesh_front.frag", Map.of(), front, U, S, S, S, S, T);
		this.dropletFront = new GraphicsProgram("particle.vert", "droplet_front.frag", droplets, front, U, S, S, S, S);
		this.meshThickness = new GraphicsProgram("mesh.vert", "mesh_thickness.frag", Map.of(), add, U, S, S, S, T);
		this.dropletThickness = new GraphicsProgram("particle.vert", "droplet_thickness.frag", droplets, add, U, S, S, S, T);
		this.whitewaterProgram = new GraphicsProgram("particle.vert", "whitewater.frag", Map.of("VIEW_BINDING", "4"),
			new GraphicsProgram.State(VkTex.RGBA16F, VK10.VK_FORMAT_UNDEFINED, false, false, VK10.VK_COMPARE_OP_ALWAYS, GraphicsProgram.Blend.ADD,
				this.thicknessFormat),
			U, S, T, T, S);
		this.dropletCull = new ComputeProgram("droplet_cull.comp", U, S, S, S, S, S, S);
	}

	private GraphicsProgram composite(int colorFormat, boolean layer) {
		return this.composites.computeIfAbsent(colorFormat * 2 + (layer ? 1 : 0), key -> new GraphicsProgram("fullscreen.vert", "composite.frag", Map.of(),
			new GraphicsProgram.State(colorFormat, VkTex.D32, true, true, VK10.VK_COMPARE_OP_ALWAYS,
				layer ? GraphicsProgram.Blend.PREMULTIPLIED : GraphicsProgram.Blend.NONE),
			Desc.UBO, Desc.TEX, Desc.TEX, Desc.TEX, Desc.TEX, Desc.TEX, Desc.TEX, Desc.SSBO, Desc.SSBO, Desc.TEX, Desc.TEX));
	}

	private static boolean blendable(int format) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkFormatProperties props = VkFormatProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceFormatProperties(Vk.vk().getPhysicalDevice(), format, props);
			return (props.optimalTilingFeatures() & VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT) != 0;
		}
	}

	private void ensureTargets(int w, int h, int colorFormat) {
		if (w == this.width && h == this.height && colorFormat == this.colorFormat && this.sceneDepth != null) {
			return;
		}
		this.releaseTargets();
		this.width = w;
		this.height = h;
		this.colorFormat = colorFormat;
		this.sceneDepth = new VkTex(VkTex.D32, w, h, VkTex.COPY_DST | VkTex.SAMPLED);
		this.fluidZ = new VkTex(VkTex.D32, w, h, VkTex.COPY_DST | VkTex.DEPTH);
		this.sceneColor = new VkTex(colorFormat, w, h, VkTex.COPY_DST | VkTex.SAMPLED);
		this.gbuffer = new VkTex(RGBA32F, w, h, VkTex.COLOR | VkTex.SAMPLED);
		this.materialId = new VkTex(VkTex.RGBA16F, w, h, VkTex.COLOR | VkTex.SAMPLED);
		this.thickness = new VkTex(this.thicknessFormat, w, h, VkTex.COLOR | VkTex.SAMPLED);
		this.foam = new VkTex(VkTex.RGBA16F, w, h, VkTex.COLOR | VkTex.SAMPLED);
		this.foamSurface = new VkTex(this.thicknessFormat, w, h, VkTex.COLOR | VkTex.SAMPLED);
	}

	private void releaseTargets() {
		for (VkTex t : new VkTex[]{
			this.sceneDepth, this.fluidZ, this.sceneColor, this.gbuffer, this.materialId, this.thickness, this.foam, this.foamSurface
		}) {
			if (t != null) {
				Vk.destroyLater(t);
			}
		}
		this.sceneDepth = null;
	}

	public void prepare(int w, int h, int colorFormat) {
		this.ensureTargets(w, h, colorFormat);
	}

	public record Body(WaterRegion region, Desc ubo) {
	}

	public void record(VkCommandBuffer cb, List<Body> bodies, Desc view, long mainColorImage, long mainColorView, long mainDepthImage,
		long mainDepthView, long lightmap, boolean layer, int @Nullable [] area) {
		int w = this.width;
		int h = this.height;
		int[] a = area != null ? area : new int[]{0, 0, w, h};
		float[] readable = area != null
			? new float[]{(a[0] + 1.0F) / w, (a[1] + 1.0F) / h, (a[0] + a[2] - 1.0F) / w, (a[1] + a[3] - 1.0F) / h}
			: new float[]{-1.0F, -1.0F, 2.0F, 2.0F};
		List<Runnable> meshes = new java.util.ArrayList<>(bodies.size());
		for (Body body : bodies) {
			WaterRegion r = body.region();
			if (r.takeFieldStale()) {
				boolean sort = WaterSettings.surfaceGather && WaterSettings.sortParticles;
				meshes.add(() -> {
					r.mesher.build(cb, body.ubo(), r.currentParticles(), r.counters, WaterRegion.C_FLUID + r.cur, r.args, r.staticSolid, r.cellFlags,
						r.mesher.fineBox(r.box), WaterSettings.sheetFill, sort ? r.spareParticles() : null, WaterRegion.C_FLUID + 1 - r.cur);
					if (sort) {
						r.useSpareParticles();
					}
				});
			}
		}
		Vk.interleave(cb, meshes);
		Vk.barrier(cb);
		for (Body body : bodies) {
			WaterRegion r = body.region();
			this.dropletCull.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.mesher.densA), view, Desc.ssbo(r.args), Desc.ssbo(r.counters),
				Desc.ssbo(r.dropletIds));
			this.dropletCull.push(cb, WaterRegion.C_FLUID + r.cur);
			this.dropletCull.dispatchIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DISPATCH);
		}

		VkTex.copy(cb, mainDepthImage, this.sceneDepth.image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, w, h);
		VkTex.copy(cb, mainDepthImage, this.fluidZ.image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, a[0], a[1], a[2], a[3]);
		VkTex.copy(cb, mainColorImage, this.sceneColor.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, w, h);
		Vk.barrier(cb);
		Desc depthTex = Desc.tex(this.sceneDepth, this.nearest.handle);

		GraphicsProgram.setArea(a[0], a[1], a[2], a[3]);
		GraphicsProgram.begin(cb, w, h, this.gbuffer.view, new float[]{0, 0, 0, 0}, this.materialId.view, new float[]{-1, 0, 0, 0}, this.fluidZ.view);
		for (Body body : bodies) {
			WaterRegion r = body.region();
			float radius = WaterSettings.particleRadius / r.res;
			this.meshFront.bind(cb, body.ubo(), Desc.ssbo(r.mesher.verts), Desc.ssbo(r.mesher.quads), view, Desc.ssbo(r.light()),
				Desc.tex(WaterWorld.packShadowView != 0L ? WaterWorld.packShadowView : lightmap, this.nearest.handle));
			this.meshFront.drawIndirect(cb, r.mesher.meshCounters, SurfaceMesher.DRAW_ARGS_OFFSET);
			this.dropletFront.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.dropletIds), view, Desc.ssbo(r.light()));
			this.dropletFront.push(cb, Program.f(radius), Program.f(0.0F), Program.f(0.15F));
			this.dropletFront.drawIndirect(cb, r.args, WaterRegion.ARGS_DROPLET_DRAW);
		}
		GraphicsProgram.end(cb);

		GraphicsProgram.begin(cb, w, h, this.thickness.view, new float[]{0, 0, 0, 0}, 0L);
		for (Body body : bodies) {
			WaterRegion r = body.region();
			float radius = WaterSettings.particleRadius / r.res;
			this.meshThickness.bind(cb, body.ubo(), Desc.ssbo(r.mesher.verts), Desc.ssbo(r.mesher.quads), view, depthTex);
			this.meshThickness.drawIndirect(cb, r.mesher.meshCounters, SurfaceMesher.DRAW_ARGS_OFFSET);
			this.dropletThickness.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.dropletIds), view, depthTex);
			this.dropletThickness.push(cb, Program.f(radius), Program.f(0.0F), Program.f(0.15F));
			this.dropletThickness.drawIndirect(cb, r.args, WaterRegion.ARGS_DROPLET_DRAW);
		}
		GraphicsProgram.end(cb);
		Vk.barrier(cb);

		GraphicsProgram.begin(cb, w, h, this.foam.view, new float[]{0, 0, 0, 0}, this.foamSurface.view, new float[]{0, 0, 0, 0}, 0L);
		if (WaterSettings.whitewater) {
			for (Body body : bodies) {
				WaterRegion r = body.region();
				if (!r.material.usesWhitewater()) {
					continue;
				}
				this.whitewaterProgram.bind(cb, body.ubo(), Desc.ssbo(r.currentWhitewater()), depthTex, Desc.tex(this.gbuffer, this.nearest.handle), view);
				this.whitewaterProgram.push(cb, Program.f(WaterSettings.wwRadius), Program.f(0.5F), Program.f(0.3F), Program.f(WaterSettings.wwMaxPixels),
					Program.f(WaterSettings.foamSize));
				this.whitewaterProgram.drawIndirect(cb, r.args, WaterRegion.ARGS_WW_DRAW);
			}
		}
		GraphicsProgram.end(cb);
		Vk.barrier(cb);

		GraphicsProgram composite = this.composite(this.colorFormat, layer);
		GraphicsProgram.begin(cb, w, h, mainColorView, null, mainDepthView);
		Desc matTex = Desc.tex(this.materialId, this.nearest.handle);
		for (Material material : new Material[]{Material.WATER, Material.LAVA, Material.SLIME}) {
			Desc ubo = null;
			for (Body body : bodies) {
				if (body.region().material == material) {
					ubo = body.ubo();
					break;
				}
			}
			if (ubo == null) {
				continue;
			}
			composite.bind(cb, ubo,
				Desc.tex(this.gbuffer, this.nearest.handle),
				Desc.tex(this.thickness, this.linear.handle),
				Desc.tex(this.sceneColor, this.linear.handle),
				depthTex,
				Desc.tex(this.foam, this.linear.handle),
				matTex,
				view,
				Desc.ssbo(this.slimeTexture),
				Desc.tex(lightmap, this.linear.handle),
				Desc.tex(this.foamSurface, this.linear.handle));
			composite.push(cb, Program.f(readable[0]), Program.f(readable[1]), Program.f(readable[2]), Program.f(readable[3]),
				Program.f(material.shaderId()));
			composite.draw(cb, 3, 1);
		}
		GraphicsProgram.end(cb);
		GraphicsProgram.clearArea();
	}

	@Override
	public void close() {
		this.releaseTargets();
		this.composites.values().forEach(GraphicsProgram::close);
		for (AutoCloseable c : new AutoCloseable[]{
			this.meshFront, this.dropletFront, this.meshThickness, this.dropletThickness, this.whitewaterProgram, this.dropletCull,
			this.nearest, this.linear, this.slimeTexture
		}) {
			try {
				c.close();
			} catch (Exception ignored) {
			}
		}
	}
}
