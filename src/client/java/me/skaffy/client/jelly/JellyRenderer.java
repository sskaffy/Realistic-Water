package me.skaffy.client.jelly;

import java.nio.ByteBuffer;
import java.util.Map;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import me.skaffy.client.water.WaterSettings;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

final class JellyRenderer implements AutoCloseable {
	private final VkBuf[] verts = new VkBuf[Vk.FRAME_RING];
	private final VkBuf[] indices = new VkBuf[Vk.FRAME_RING];
	private final VkBuf params = VkBuf.upload(128L * Vk.FRAME_RING, VkBuf.STORAGE);
	private final Map<Integer, GraphicsProgram> fronts = new java.util.HashMap<>();
	private final GraphicsProgram back = new GraphicsProgram("jelly.vert", "jelly_back.frag", Map.of(),
		new GraphicsProgram.State(VkTex.R32F, VkTex.D32, true, true, VK10.VK_COMPARE_OP_LESS, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO,
		Desc.SSBO);
	private final Sampler linear = new Sampler(true);
	private final Sampler nearest = new Sampler(false);
	private @Nullable VkTex sceneColor;
	private @Nullable VkTex sceneDepth;
	private @Nullable VkTex backDist;
	private @Nullable VkTex backDepth;
	private int colorFormat = -1;

	private GraphicsProgram front(int format) {
		return this.fronts.computeIfAbsent(format, f -> new GraphicsProgram("jelly.vert", "jelly_front.frag", Map.of(),
			new GraphicsProgram.State(f, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO, Desc.SSBO,
			Desc.TEX, Desc.TEX, Desc.TEX, Desc.TEX, Desc.SSBO));
	}

	private static VkBuf fit(VkBuf[] ring, int slot, long bytes) {
		VkBuf b = ring[slot];
		if (b == null || b.size < bytes) {
			if (b != null) {
				Vk.destroyLater(b);
			}
			b = VkBuf.upload(Math.max(bytes + bytes / 2, 64L * 1024L), VkBuf.STORAGE);
			ring[slot] = b;
		}
		return b;
	}

	private void ensureTargets(int width, int height, int format) {
		if (this.sceneColor != null && this.sceneColor.width == width && this.sceneColor.height == height && this.colorFormat == format) {
			return;
		}
		for (VkTex t : new VkTex[]{this.sceneColor, this.sceneDepth, this.backDist, this.backDepth}) {
			if (t != null) {
				Vk.destroyLater(t);
			}
		}
		this.colorFormat = format;
		this.sceneColor = new VkTex(format, width, height, VkTex.SAMPLED | VkTex.COPY_DST);
		this.sceneDepth = new VkTex(VkTex.D32, width, height, VkTex.SAMPLED | VkTex.COPY_DST);
		this.backDist = new VkTex(VkTex.R32F, width, height, VkTex.COLOR | VkTex.SAMPLED | VkTex.COPY_DST);
		this.backDepth = new VkTex(VkTex.D32, width, height, VkTex.DEPTH | VkTex.COPY_DST);
	}

	private void writeParams(ByteBuffer b, int o) {
		putGel(b, o, WaterSettings.slimeGelAbsorbR, WaterSettings.slimeGelAbsorbG, WaterSettings.slimeGelAbsorbB, WaterSettings.slimeGelDensity,
			WaterSettings.slimeGelScatterR, WaterSettings.slimeGelScatterG, WaterSettings.slimeGelScatterB, WaterSettings.slimeGelHaze,
			WaterSettings.slimeGelRefraction, WaterSettings.slimeGelGlow, WaterSettings.slimeGelBubbles, WaterSettings.slimeGelGloss);
		putGel(b, o + 64, WaterSettings.honeyGelAbsorbR, WaterSettings.honeyGelAbsorbG, WaterSettings.honeyGelAbsorbB, WaterSettings.honeyGelDensity,
			WaterSettings.honeyGelScatterR, WaterSettings.honeyGelScatterG, WaterSettings.honeyGelScatterB, WaterSettings.honeyGelHaze,
			WaterSettings.slimeGelRefraction * 1.2F, WaterSettings.slimeGelGlow * 1.2F, WaterSettings.slimeGelBubbles * 1.5F, WaterSettings.slimeGelGloss * 1.2F);
	}

	private static void putGel(ByteBuffer b, int o, float ar, float ag, float ab, float density, float sr, float sg, float sb, float haze, float refraction,
		float glow, float bubbles, float gloss) {
		float max = Math.max(Math.max(sr, sg), Math.max(sb, 1.0E-3F));
		float[] v = {ar, ag, ab, density, sr, sg, sb, haze, refraction, glow, bubbles, gloss, sr / max, sg / max, sb / max, 0.0F};
		for (int i = 0; i < v.length; i++) {
			b.putFloat(o + i * 4, Math.max(v[i], 0.0F));
		}
	}

	void record(VkCommandBuffer cb, Desc ubo, JellyMesher mesh, int width, int height, int colorFormat, long colorImage, long colorView, long depthImage,
		long depthView, long lightmap) {
		if (mesh.indexCount == 0) {
			return;
		}
		int slot = Vk.ringSlot();
		long vertBytes = (long) mesh.vertexCount * JellyMesher.FLOATS * 4L;
		long indexBytes = (long) mesh.indexCount * 4L;
		VkBuf vb = fit(this.verts, slot, vertBytes);
		VkBuf ib = fit(this.indices, slot, indexBytes);
		vb.mapped().asFloatBuffer().put(0, mesh.vertices, 0, mesh.vertexCount * JellyMesher.FLOATS);
		vb.flush(0, vertBytes);
		ib.mapped().asIntBuffer().put(0, mesh.indices, 0, mesh.indexCount);
		ib.flush(0, indexBytes);
		this.writeParams(this.params.mapped(), slot * 128);
		this.params.flush(slot * 128L, 128L);

		this.ensureTargets(width, height, colorFormat);
		VkTex.copy(cb, colorImage, this.sceneColor.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, width, height);
		VkTex.copy(cb, depthImage, this.sceneDepth.image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, width, height);
		this.backDist.clear(cb, 0.0F, 0.0F, 0.0F, 0.0F);
		this.backDepth.clearDepth(cb, 1.0F);
		Vk.barrier(cb);

		Desc vd = Desc.ssbo(vb, 0, vertBytes);
		Desc id = Desc.ssbo(ib, 0, indexBytes);
		GraphicsProgram.begin(cb, width, height, this.backDist.view, null, this.backDepth.view);
		this.back.bind(cb, ubo, vd, id);
		this.back.draw(cb, mesh.indexCount, 1);
		GraphicsProgram.end(cb);
		Vk.barrier(cb);

		GraphicsProgram front = this.front(colorFormat);
		GraphicsProgram.begin(cb, width, height, colorView, null, depthView);
		front.bind(cb, ubo, vd, id, Desc.tex(this.sceneColor, this.linear.handle), Desc.tex(this.sceneDepth, this.nearest.handle),
			Desc.tex(this.backDist, this.nearest.handle), Desc.tex(lightmap, this.linear.handle), Desc.ssbo(this.params, slot * 128L, 128L));
		front.draw(cb, mesh.indexCount, 1);
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.back.close();
		this.fronts.values().forEach(GraphicsProgram::close);
		for (VkTex t : new VkTex[]{this.sceneColor, this.sceneDepth, this.backDist, this.backDepth}) {
			if (t != null) {
				t.close();
			}
		}
		for (VkBuf b : this.verts) {
			if (b != null) {
				b.close();
			}
		}
		for (VkBuf b : this.indices) {
			if (b != null) {
				b.close();
			}
		}
		this.params.close();
		this.linear.close();
		this.nearest.close();
	}
}
