package me.skaffy.client.ice;

import java.util.HashMap;
import java.util.Map;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import me.skaffy.client.water.WaterSettings;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

final class IceRenderer implements AutoCloseable {
	private final VkBuf[] faces = new VkBuf[Vk.FRAME_RING];
	private final VkBuf[] free = new VkBuf[Vk.FRAME_RING];
	private final Map<Integer, GraphicsProgram> freeFronts = new HashMap<>();
	private final GraphicsProgram freeBack = new GraphicsProgram("ice.vert", "jelly_back.frag", Map.of("FREE", "1"),
		new GraphicsProgram.State(VkTex.R32F, VkTex.D32, true, true, VK10.VK_COMPARE_OP_LESS, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO);
	private final int[] uploaded = {-1, -1, -1};
	private final Map<Integer, GraphicsProgram> fronts = new HashMap<>();
	private final GraphicsProgram back = new GraphicsProgram("ice.vert", "jelly_back.frag", Map.of(),
		new GraphicsProgram.State(VkTex.R32F, VkTex.D32, true, true, VK10.VK_COMPARE_OP_LESS, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO);
	private final Sampler linear = new Sampler(true);
	private final Sampler nearest = new Sampler(false);
	private @Nullable VkTex sceneColor;
	private @Nullable VkTex sceneDepth;
	private @Nullable VkTex backDist;
	private @Nullable VkTex backDepth;
	private int colorFormat = -1;
	private final long start = System.nanoTime();

	private GraphicsProgram front(int format) {
		return this.fronts.computeIfAbsent(format, f -> new GraphicsProgram("ice.vert", "ice_front.frag", Map.of(),
			new GraphicsProgram.State(f, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO, Desc.TEX,
			Desc.TEX, Desc.TEX, Desc.TEX));
	}

	private GraphicsProgram freeFront(int format) {
		return this.freeFronts.computeIfAbsent(format, f -> new GraphicsProgram("ice.vert", "ice_front.frag", Map.of("FREE", "1"),
			new GraphicsProgram.State(f, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO, Desc.TEX,
			Desc.TEX, Desc.TEX, Desc.TEX));
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

	void record(VkCommandBuffer cb, Desc ubo, int[] packed, int faceCount, int version, float[] freeVerts, int freeCount, int width, int height,
		int colorFormat, long colorImage, long colorView, long depthImage, long depthView, long lightmap) {
		if (faceCount <= 0 && freeCount <= 0) {
			return;
		}
		int slot = Vk.ringSlot();
		long bytes = Math.max((long) faceCount * 16L, 16L);
		VkBuf fb = this.faces[slot];
		if (fb == null || fb.size < bytes) {
			if (fb != null) {
				Vk.destroyLater(fb);
			}
			fb = VkBuf.upload(Math.max(bytes + bytes / 2, 64L * 1024L), VkBuf.STORAGE);
			this.faces[slot] = fb;
			this.uploaded[slot] = -1;
		}
		if (this.uploaded[slot] != version) {
			fb.mapped().asIntBuffer().put(0, packed, 0, faceCount * 4);
			fb.flush(0, bytes);
			this.uploaded[slot] = version;
		}
		Desc freeDesc = null;
		if (freeCount > 0) {
			long freeBytes = (long) freeCount * IceBodies.FLOATS * 4L;
			VkBuf b = this.free[slot];
			if (b == null || b.size < freeBytes) {
				if (b != null) {
					Vk.destroyLater(b);
				}
				b = VkBuf.upload(Math.max(freeBytes + freeBytes / 2, 64L * 1024L), VkBuf.STORAGE);
				this.free[slot] = b;
			}
			b.mapped().asFloatBuffer().put(0, freeVerts, 0, freeCount * IceBodies.FLOATS);
			b.flush(0, freeBytes);
			freeDesc = Desc.ssbo(b, 0, freeBytes);
		}

		this.ensureTargets(width, height, colorFormat);
		VkTex.copy(cb, colorImage, this.sceneColor.image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, width, height);
		VkTex.copy(cb, depthImage, this.sceneDepth.image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, width, height);
		this.backDist.clear(cb, 0.0F, 0.0F, 0.0F, 0.0F);
		this.backDepth.clearDepth(cb, 1.0F);
		Vk.barrier(cb);

		Desc fd = Desc.ssbo(fb, 0, bytes);
		GraphicsProgram.begin(cb, width, height, this.backDist.view, null, this.backDepth.view);
		if (faceCount > 0) {
			this.back.bind(cb, ubo, fd);
			this.back.draw(cb, faceCount * 6, 1);
		}
		if (freeDesc != null) {
			this.freeBack.bind(cb, ubo, freeDesc);
			this.freeBack.draw(cb, freeCount, 1);
		}
		GraphicsProgram.end(cb);
		Vk.barrier(cb);

		GraphicsProgram.begin(cb, width, height, colorView, null, depthView);
		float time = (float) ((System.nanoTime() - this.start) / 1.0e9 % 1000.0);
		int[] look = {Program.f(Math.clamp(WaterSettings.realisticIceCloudiness, 0.0F, 1.0F)), Program.f(Math.clamp(WaterSettings.realisticIceBubbles, 0.0F, 1.0F)),
			Program.f(Math.clamp(WaterSettings.realisticIceCracks, 0.0F, 1.0F)), Program.f(Math.max(WaterSettings.realisticIceRefraction, 0.0F)),
			Program.f(time), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
		Desc sc = Desc.tex(this.sceneColor, this.linear.handle);
		Desc sd = Desc.tex(this.sceneDepth, this.nearest.handle);
		Desc bd = Desc.tex(this.backDist, this.nearest.handle);
		Desc lm = Desc.tex(lightmap, this.linear.handle);
		if (faceCount > 0) {
			GraphicsProgram front = this.front(colorFormat);
			front.bind(cb, ubo, fd, sc, sd, bd, lm);
			front.push(cb, look);
			front.draw(cb, faceCount * 6, 1);
		}
		if (freeDesc != null) {
			GraphicsProgram front = this.freeFront(colorFormat);
			front.bind(cb, ubo, freeDesc, sc, sd, bd, lm);
			front.push(cb, look);
			front.draw(cb, freeCount, 1);
		}
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.back.close();
		this.freeBack.close();
		this.fronts.values().forEach(GraphicsProgram::close);
		this.freeFronts.values().forEach(GraphicsProgram::close);
		for (VkBuf b : this.free) {
			if (b != null) {
				b.close();
			}
		}
		for (VkTex t : new VkTex[]{this.sceneColor, this.sceneDepth, this.backDist, this.backDepth}) {
			if (t != null) {
				t.close();
			}
		}
		for (VkBuf b : this.faces) {
			if (b != null) {
				b.close();
			}
		}
		this.linear.close();
		this.nearest.close();
	}
}
