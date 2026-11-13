package me.skaffy.client.water;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

final class IceCrystalRenderer implements AutoCloseable {
	static final long ARGS_CRYSTAL_DRAW = 112;
	private static final long CRYSTAL_BYTES = 32;

	private final ComputeProgram cull;
	private final Map<Integer, GraphicsProgram> draws = new HashMap<>();
	private final Map<WaterRegion, VkBuf> buffers = new IdentityHashMap<>();
	private final Map<WaterRegion, Integer> capacities = new IdentityHashMap<>();
	private final Sampler linear = new Sampler(true);

	IceCrystalRenderer() {
		this.cull = new ComputeProgram("crystal_cull.comp", Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO);
	}

	static boolean wanted() {
		return WaterSettings.waterTemperature && WaterSettings.waterFreezing && WaterSettings.slushCrystals && WaterSettings.slushCrystalSize > 0.0F;
	}

	private GraphicsProgram draw(int colorFormat) {
		return this.draws.computeIfAbsent(colorFormat, format -> new GraphicsProgram("crystal.vert", "crystal.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, false, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.PREMULTIPLIED), Desc.UBO, Desc.SSBO,
			Desc.SSBO, Desc.TEX));
	}

	private VkBuf buffer(WaterRegion r) {
		int capacity = Math.max(r.fluidCapacity, 1024);
		VkBuf buf = this.buffers.get(r);
		Integer have = this.capacities.get(r);
		if (buf == null || have == null || have < capacity) {
			if (buf != null) {
				Vk.destroyLater(buf);
			}
			buf = VkBuf.device(capacity * CRYSTAL_BYTES, 0);
			this.buffers.put(r, buf);
			this.capacities.put(r, capacity);
		}
		return buf;
	}

	void record(VkCommandBuffer cb, List<WaterRenderer.Body> bodies, int width, int height, int colorFormat, long mainColorView, long mainDepthView,
		long lightmap) {
		this.buffers.keySet().removeIf(r -> {
			boolean gone = bodies.stream().noneMatch(b -> b.region() == r);
			if (gone) {
				Vk.destroyLater(this.buffers.get(r));
				this.capacities.remove(r);
			}
			return gone;
		});
		List<WaterRenderer.Body> water = bodies.stream().filter(b -> b.region().material == Material.WATER && b.region().ocean == null).toList();
		if (water.isEmpty()) {
			return;
		}
		for (WaterRenderer.Body body : water) {
			WaterRegion r = body.region();
			r.args.fill(cb, ARGS_CRYSTAL_DRAW, 4, 6);
			r.args.fill(cb, ARGS_CRYSTAL_DRAW + 4, 12, 0);
		}
		Vk.barrier(cb);
		for (WaterRenderer.Body body : water) {
			WaterRegion r = body.region();
			VkBuf crystals = this.buffer(r);
			this.cull.bind(cb, body.ubo(), r.currentParticles().part0(), Desc.ssbo(r.cellCount), Desc.ssbo(crystals), Desc.ssbo(r.args),
				Desc.ssbo(r.counters), r.currentParticles().part1());
			this.cull.push(cb, WaterRegion.C_FLUID + r.cur, this.capacities.get(r));
			this.cull.dispatchIndirect(cb, r.args, WaterRegion.ARGS_FLUID_DISPATCH);
		}
		Vk.barrier(cb);
		GraphicsProgram draw = this.draw(colorFormat);
		GraphicsProgram.begin(cb, width, height, mainColorView, null, mainDepthView);
		for (WaterRenderer.Body body : water) {
			WaterRegion r = body.region();
			draw.bind(cb, body.ubo(), Desc.ssbo(this.buffers.get(r)), Desc.ssbo(r.light()), Desc.tex(lightmap, this.linear.handle));
			draw.drawIndirect(cb, r.args, ARGS_CRYSTAL_DRAW);
		}
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		this.draws.values().forEach(GraphicsProgram::close);
		this.cull.close();
		this.buffers.values().forEach(VkBuf::close);
		this.buffers.clear();
		this.capacities.clear();
		this.linear.close();
	}
}
