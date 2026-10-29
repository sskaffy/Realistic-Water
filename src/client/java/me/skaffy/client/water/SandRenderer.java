package me.skaffy.client.water;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.RealisticWater;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

final class SandRenderer implements AutoCloseable {
	private static final Identifier SAND_TEXTURE = Identifier.withDefaultNamespace("textures/block/sand.png");

	private final Map<Integer, GraphicsProgram> grains = new HashMap<>();
	private final ComputeProgram cull;
	private final VkBuf texture;
	private final Sampler linear = new Sampler(true);
	private final Sampler nearest = new Sampler(false);

	SandRenderer() {
		int U = Desc.UBO;
		int S = Desc.SSBO;
		this.cull = new ComputeProgram("grain_cull.comp", U, S, S, S, S, S, S, Desc.TEX);
		this.texture = loadTexture();
	}

	private static VkBuf loadTexture() {
		return loadTexture(SAND_TEXTURE, 0xFFDBCFA3);
	}

	static VkBuf loadTexture(Identifier id, int fallback) {
		int[] argb;
		int w;
		int h;
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(id); NativeImage image = NativeImage.read(in)) {
			w = image.getWidth();
			h = Math.min(image.getHeight(), w);
			argb = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					argb[x + y * w] = image.getPixel(x, y);
				}
			}
		} catch (Exception e) {
			RealisticWater.LOGGER.warn("Couldn't read {}, using a flat colour", id, e);
			w = 1;
			h = 1;
			argb = new int[]{fallback};
		}
		long r = 0;
		long g = 0;
		long b = 0;
		for (int c : argb) {
			r += (c >> 16) & 0xFF;
			g += (c >> 8) & 0xFF;
			b += c & 0xFF;
		}
		int n = argb.length;
		int average = 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
		long bytes = (4L + n) * 4L;
		VkBuf buf = VkBuf.upload(bytes, VkBuf.STORAGE);
		ByteBuffer m = buf.mapped();
		m.putInt(0, w).putInt(4, h).putInt(8, average).putInt(12, 0);
		for (int i = 0; i < n; i++) {
			m.putInt(16 + i * 4, argb[i]);
		}
		buf.flush(0, bytes);
		return buf;
	}

	private GraphicsProgram grains(int colorFormat) {
		return this.grains.computeIfAbsent(colorFormat, format -> new GraphicsProgram("grain.vert", "grain.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO, Desc.SSBO,
			Desc.SSBO, Desc.TEX));
	}

	void record(VkCommandBuffer cb, List<WaterRenderer.Body> bodies, int width, int height, int colorFormat, long mainColorView, long mainDepthView,
		long lightmap) {
		GraphicsProgram grains = this.grains(colorFormat);
		List<Runnable> fields = new java.util.ArrayList<>(bodies.size());
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			boolean stale = r.takeFieldStale();
			fields.add(() -> {
				if (stale) {
					r.mesher.buildField(cb, body.ubo(), r.currentParticles(), r.counters, WaterRegion.C_FLUID + r.cur, r.args, r.mesher.fineBox(r.box),
						false, null, 0);
				}
				r.args.fill(cb, WaterRegion.ARGS_GRAIN_DRAW + 4, 4, 0);
			});
		}
		Vk.interleave(cb, fields);
		Vk.barrier(cb);
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			this.cull.bind(cb, body.ubo(), Desc.ssbo(r.currentParticles()), Desc.ssbo(r.mesher.densA), Desc.ssbo(r.surfaceParticles), Desc.ssbo(r.args),
				Desc.ssbo(r.counters), Desc.ssbo(r.light()), Desc.tex(WaterWorld.packShadowView != 0L ? WaterWorld.packShadowView : lightmap, this.nearest.handle));
			this.cull.push(cb, WaterRegion.C_FLUID + r.cur);
			this.cull.dispatchIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DISPATCH);
		}
		Vk.barrier(cb);
		GraphicsProgram.begin(cb, width, height, mainColorView, null, mainDepthView);
		for (WaterRenderer.Body body : bodies) {
			WaterRegion r = body.region();
			grains.bind(cb, body.ubo(), Desc.ssbo(r.surfaceParticles), Desc.ssbo(this.texture), Desc.ssbo(r.light()),
				Desc.tex(lightmap, this.linear.handle));
			grains.drawIndirect(cb, r.args, WaterRegion.ARGS_GRAIN_DRAW);
		}
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.grains.values().forEach(GraphicsProgram::close);
		this.cull.close();
		this.texture.close();
		this.linear.close();
		this.nearest.close();
	}
}
