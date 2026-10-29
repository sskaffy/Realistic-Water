package me.skaffy.client.water;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.RealisticWater;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

final class LeafRenderer implements AutoCloseable {
	private static final int VARIANTS = 4;
	private static final int[] FALLBACK = {0xFFEA4D38, 0xFFEA8438, 0xFF6B823D, 0xFF875031};

	private final Map<Integer, GraphicsProgram> leaves = new HashMap<>();
	private final VkBuf textures = loadTextures();
	private final Sampler linear = new Sampler(true);
	private final Sampler nearest = new Sampler(false);

	private static VkBuf loadTextures() {
		int w = 0;
		int h = 0;
		int[][] texels = new int[VARIANTS][];
		for (int v = 0; v < VARIANTS; v++) {
			Identifier id = RealisticWater.id("textures/leaf/leaf_" + (v + 1) + ".png");
			try (InputStream in = Minecraft.getInstance().getResourceManager().open(id); NativeImage image = NativeImage.read(in)) {
				if (v == 0) {
					w = image.getWidth();
					h = image.getHeight();
				}
				int[] t = new int[w * h];
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						t[x + y * w] = x < image.getWidth() && y < image.getHeight() ? image.getPixel(x, y) : 0;
					}
				}
				texels[v] = t;
			} catch (Exception e) {
				RealisticWater.LOGGER.warn("Couldn't read {}, using a flat colour", id, e);
			}
		}
		if (w == 0 || h == 0) {
			w = 1;
			h = 1;
		}
		int n = w * h;
		long bytes = (4L + (long) VARIANTS * n) * 4L;
		VkBuf buf = VkBuf.upload(bytes, VkBuf.STORAGE);
		ByteBuffer m = buf.mapped();
		m.putInt(0, w).putInt(4, h).putInt(8, VARIANTS).putInt(12, 0);
		for (int v = 0; v < VARIANTS; v++) {
			for (int i = 0; i < n; i++) {
				int argb = texels[v] != null && texels[v].length == n ? texels[v][i] : FALLBACK[v];
				m.putInt(16 + (v * n + i) * 4, argb);
			}
		}
		buf.flush(0, bytes);
		return buf;
	}

	private GraphicsProgram leaves(int colorFormat) {
		return this.leaves.computeIfAbsent(colorFormat, format -> new GraphicsProgram("leaf.vert", "leaf.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO, Desc.SSBO,
			Desc.SSBO, Desc.TEX, Desc.SSBO, Desc.TEX));
	}

	void record(VkCommandBuffer cb, List<WaterRenderer.Body> bodies, int width, int height, int colorFormat, long mainColorView, long mainDepthView,
		long lightmap) {
		GraphicsProgram leaves = this.leaves(colorFormat);
		GraphicsProgram.begin(cb, width, height, mainColorView, null, mainDepthView);
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			leaves.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(this.textures), Desc.ssbo(r.light()),
				Desc.tex(lightmap, this.linear.handle), Desc.ssbo(r.staticSolid),
				Desc.tex(WaterWorld.packShadowView != 0L ? WaterWorld.packShadowView : lightmap, this.nearest.handle));
			leaves.drawIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DRAW);
		}
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.leaves.values().forEach(GraphicsProgram::close);
		this.textures.close();
		this.linear.close();
		this.nearest.close();
	}
}
