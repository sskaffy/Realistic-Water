package me.skaffy.client.trees;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import me.skaffy.client.water.WaterSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class TreeRenderer implements AutoCloseable {
	static final long PARAMS_BYTES = 256;
	static final int SLOT_BYTES = 48;

	private final ComputeProgram cull = new ComputeProgram("tree_cull.comp", Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO);
	private final ComputeProgram sim = new ComputeProgram("tree_sim.comp", Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO,
		Desc.SSBO, Desc.SSBO);
	private final VkBuf[] mods = new VkBuf[Vk.FRAME_RING];
	private VkBuf state;
	private VkBuf motion;
	private VkBuf impulse;
	private long simSlots;
	private VkBuf table;
	private long tableEntries;
	private final VkBuf noEvents = VkBuf.device(32, 0);
	private double windAngle = 0.9;
	private final Map<Integer, GraphicsProgram> leafDraws = new HashMap<>();
	private final Map<Integer, GraphicsProgram> stickDraws = new HashMap<>();
	private final VkBuf params = VkBuf.upload(PARAMS_BYTES * Vk.FRAME_RING, VkBuf.STORAGE);
	private final VkBuf[] blocks = new VkBuf[Vk.FRAME_RING];
	private final long[] blockCapacity = new long[Vk.FRAME_RING];
	private final VkBuf args = VkBuf.device(64, VkBuf.INDIRECT);
	private final VkBuf wanted = VkBuf.readback(Vk.FRAME_RING * 8L);
	private VkBuf leafList;
	private VkBuf stickList;
	private long leafCap;
	private long stickCap;
	private final Sampler linear = new Sampler(true);
	private final Sampler nearest = new Sampler(false);
	private float time;
	static volatile float currentTime;
	private final VkBuf motionReadback = VkBuf.readback(Vk.FRAME_RING * 64L * 16L);
	private final int[][] motionSlots = new int[Vk.FRAME_RING][];

	public record RainView(Desc params, Desc blocks, Desc table, Desc motion, long frame) {
	}

	private static volatile @org.jspecify.annotations.Nullable RainView rainView;

	public static @org.jspecify.annotations.Nullable RainView rainView() {
		RainView v = rainView;
		return v != null && Vk.frame() - v.frame <= 1L ? v : null;
	}

	public TreeRenderer() {
		for (int i = 0; i < Vk.FRAME_RING * 2; i++) {
			this.wanted.mapped().putInt(i * 4, 0);
		}
	}

	private GraphicsProgram leaves(int colorFormat) {
		return this.leafDraws.computeIfAbsent(colorFormat, format -> new GraphicsProgram("tree_leaf.vert", "tree.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO,
			Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.TEX, Desc.TEX, Desc.SSBO, Desc.SSBO));
	}

	private GraphicsProgram sticks(int colorFormat) {
		return this.stickDraws.computeIfAbsent(colorFormat, format -> new GraphicsProgram("tree_stick.vert", "tree.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO,
			Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.TEX, Desc.TEX, Desc.SSBO, Desc.SSBO));
	}

	static void writeParams(ByteBuffer p, int o, int bx, int by, int bz, int slots, float time, float dt, long leafCap, long stickCap, float windX,
		float windZ, float wind, float rainX, float rainY, float rainZ, int tableMask, int events) {
		p.putInt(o, bx).putInt(o + 4, by).putInt(o + 8, bz).putInt(o + 12, slots);
		int perBlock = Mth.clamp(WaterSettings.treeLeavesPerBlock, 1, 1024);
		if (WaterSettings.treeVanillaTextures) {
			perBlock = Math.max(1, Math.round(perBlock * 0.125F));
		}
		p.putInt(o + 16, perBlock).putInt(o + 20, Mth.clamp(WaterSettings.treeSideSticks, 0, 6))
			.putInt(o + 24, WaterSettings.treeVanillaTextures ? 1 : 0).putInt(o + 28, 0);
		float radius = Math.max(8, WaterSettings.treeLeafRadius);
		p.putFloat(o + 32, radius).putFloat(o + 36, Math.min(Math.max(WaterSettings.treeLeafDetailRange, 0.0F), radius))
			.putFloat(o + 40, Mth.clamp(WaterSettings.treeLeafFarDensity, 0.02F, 1.0F)).putFloat(o + 44, Math.max(WaterSettings.treeLeafSize, 0.05F));
		p.putFloat(o + 48, time % 3600.0F).putFloat(o + 52, dt).putFloat(o + 56, Mth.clamp(WaterSettings.treeShadowLeaves, 0.0F, 1.0F))
			.putFloat(o + 60, 0.0F);
		p.putInt(o + 64, (int) Math.min(leafCap, Integer.MAX_VALUE)).putInt(o + 68, (int) Math.min(stickCap, Integer.MAX_VALUE)).putInt(o + 72, 0)
			.putInt(o + 76, 0);
		p.putFloat(o + 80, windX).putFloat(o + 84, windZ).putFloat(o + 88, wind).putFloat(o + 92, 0.0F);
		p.putFloat(o + 96, rainX).putFloat(o + 100, rainY).putFloat(o + 104, rainZ).putFloat(o + 108, Math.max(WaterSettings.treeRainHits, 0.0F));
		p.putInt(o + 112, tableMask).putInt(o + 116, events).putInt(o + 120, 0).putInt(o + 124, 0);
	}

	public void record(VkCommandBuffer cb, Desc ubo, int width, int height, int colorFormat, long colorView, long depthView, long lightmap,
		long packShadowView, float frameDt, me.skaffy.client.water.RainSystem.@org.jspecify.annotations.Nullable Kicks rain) {
		Minecraft mc = Minecraft.getInstance();
		RealisticTrees trees = RealisticTrees.get();
		TreeAtlas atlas = trees.atlas();
		if (mc.level == null || trees.slotCount <= 0 || atlas == null) {
			return;
		}
		int ring = Vk.ringSlot();
		long need = (long) trees.slotCount * SLOT_BYTES;
		if (this.blocks[ring] == null || this.blockCapacity[ring] < need) {
			if (this.blocks[ring] != null) {
				Vk.destroyLater(this.blocks[ring]);
			}
			this.blockCapacity[ring] = Math.max(need, 64L * 1024L);
			this.blocks[ring] = VkBuf.upload(this.blockCapacity[ring], VkBuf.STORAGE);
		}
		float[] modList = TreeGrab.get().prepare(trees);
		long modBytes = Math.max(modList.length * 4L, 16L);
		if (this.mods[ring] == null || this.mods[ring].size < modBytes) {
			if (this.mods[ring] != null) {
				Vk.destroyLater(this.mods[ring]);
			}
			this.mods[ring] = VkBuf.upload(Math.max(modBytes * 2L, 16L * 1024L), VkBuf.STORAGE);
		}
		this.mods[ring].mapped().asFloatBuffer().put(0, modList);
		this.mods[ring].flush(0, modBytes);
		Desc modDesc = Desc.ssbo(this.mods[ring], 0, modBytes);
		trees.writeBlocks(this.blocks[ring].mapped(), 0);
		this.blocks[ring].flush(0, need);

		ByteBuffer w = this.wanted.mapped();
		this.wanted.invalidate(0, Vk.FRAME_RING * 8L);
		long wantLeaves = 0L;
		long wantSticks = 0L;
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			wantLeaves = Math.max(wantLeaves, Integer.toUnsignedLong(w.getInt(i * 8)));
			wantSticks = Math.max(wantSticks, Integer.toUnsignedLong(w.getInt(i * 8 + 4)));
		}
		if (this.leafList == null || this.leafCap < wantLeaves) {
			if (this.leafList != null) {
				Vk.destroyLater(this.leafList);
			}
			this.leafCap = Math.max(Math.max(wantLeaves + wantLeaves / 4, this.leafCap * 3 / 2), 1L << 20);
			this.leafList = VkBuf.device(this.leafCap * 4L, 0);
		}
		if (this.stickList == null || this.stickCap < wantSticks) {
			if (this.stickList != null) {
				Vk.destroyLater(this.stickList);
			}
			this.stickCap = Math.max(Math.max(wantSticks + wantSticks / 4, this.stickCap * 3 / 2), 1L << 16);
			this.stickList = VkBuf.device(this.stickCap * 4L, 0);
		}

		float dt = Math.min(Math.max(frameDt, 0.0F), 1.0F / 30.0F);
		this.time += dt;
		Vec3 cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		int bx = Mth.floor(cam.x);
		int by = Mth.floor(cam.y);
		int bz = Mth.floor(cam.z);
		if (this.state == null || this.simSlots < trees.slotCount) {
			for (VkBuf b : new VkBuf[]{this.state, this.motion, this.impulse}) {
				if (b != null) {
					Vk.destroyLater(b);
				}
			}
			this.simSlots = Math.max((long) trees.slotCount + trees.slotCount / 2, 16384L);
			this.state = VkBuf.device(this.simSlots * 32L, 0);
			this.motion = VkBuf.device(this.simSlots * 96L, 0);
			this.impulse = VkBuf.device(this.simSlots * 16L, 0);
			this.state.fill(cb, 0);
			this.motion.fill(cb, 0);
			this.impulse.fill(cb, 0);
		}
		long entries = Long.highestOneBit(Math.max((long) trees.slotCount * 2L, 1024L) - 1L) << 1;
		if (this.table == null || this.tableEntries != entries) {
			if (this.table != null) {
				Vk.destroyLater(this.table);
			}
			this.tableEntries = entries;
			this.table = VkBuf.device(entries * 8L, 0);
		}
		this.windAngle += dt * 0.004;
		float weather = mc.level.isThundering() ? 2.4F : mc.level.isRaining() ? 1.7F : 1.0F;
		float wx = (float) Math.cos(this.windAngle + 0.35 * Math.sin(this.time * 0.013));
		float wz = (float) Math.sin(this.windAngle + 0.35 * Math.sin(this.time * 0.013));
		int events = rain != null ? rain.capacity() : 0;
		float rx = rain != null ? (float) (rain.originX() - bx) : 0.0F;
		float rz = rain != null ? (float) (rain.originZ() - bz) : 0.0F;
		int o = (int) (ring * PARAMS_BYTES);
		writeParams(this.params.mapped(), o, bx, by, bz, trees.slotCount, this.time, dt, this.leafCap, this.stickCap, wx, wz,
			Math.max(WaterSettings.treeWind, 0.0F) * weather, rx, (float) -by, rz, (int) (entries - 1L), events);
		this.params.flush(ring * PARAMS_BYTES, PARAMS_BYTES);
		Desc paramDesc = Desc.ssbo(this.params, ring * PARAMS_BYTES, PARAMS_BYTES);
		Desc blockDesc = Desc.ssbo(this.blocks[ring], 0, Math.max(need, (long) SLOT_BYTES));

		this.table.fill(cb, 0xFFFFFFFF);
		Vk.barrier(cb);
		this.sim.bind(cb, ubo, paramDesc, blockDesc, Desc.ssbo(this.table), Desc.ssbo(this.state), Desc.ssbo(this.motion), Desc.ssbo(this.impulse),
			Desc.ssbo(rain != null ? rain.events() : this.noEvents), modDesc);
		int slotGroups = ComputeProgram.groups(trees.slotCount, 64);
		this.sim.push(cb, 1);
		this.sim.dispatch(cb, slotGroups, 1, 1);
		Vk.barrier(cb);
		if (rain != null) {
			this.sim.push(cb, 2);
			this.sim.dispatch(cb, ComputeProgram.groups(rain.capacity(), 64), 1, 1);
			Vk.barrier(cb);
		}
		this.sim.push(cb, 3);
		this.sim.dispatch(cb, slotGroups, 1, 1);
		Vk.barrier(cb);
		this.sim.push(cb, 4);
		this.sim.dispatch(cb, slotGroups, 1, 1);
		Vk.barrier(cb);
		currentTime = this.time % 3600.0F;
		int[] was = this.motionSlots[ring];
		if (was != null) {
			this.motionReadback.invalidate(ring * 64L * 16L, 64L * 16L);
			ByteBuffer rb = this.motionReadback.mapped();
			it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<float[]> read = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
			for (int k = 0; k < was.length; k++) {
				int at = (int) (ring * 64L * 16L) + k * 16;
				read.put(was[k], new float[]{rb.getFloat(at), rb.getFloat(at + 4), rb.getFloat(at + 8), rb.getFloat(at + 12)});
			}
			TreeGrab.get().motionRead(read);
		}
		int[] want = TreeGrab.get().interest();
		int n = Math.min(want.length, 64);
		int copied = 0;
		int[] slotsNow = new int[n];
		for (int k = 0; k < n; k++) {
			if (want[k] >= 0 && want[k] < trees.slotCount) {
				this.motion.copyTo(cb, this.motionReadback, want[k] * 96L, ring * 64L * 16L + copied * 16L, 16L);
				slotsNow[copied++] = want[k];
			}
		}
		this.motionSlots[ring] = copied > 0 ? java.util.Arrays.copyOf(slotsNow, copied) : null;

		rainView = new RainView(paramDesc, blockDesc, Desc.ssbo(this.table), Desc.ssbo(this.motion), Vk.frame());
		TreePack.get().prepared(new TreePack.Frame(this.params, ring * PARAMS_BYTES, PARAMS_BYTES, this.blocks[ring], need, trees.slotCount, bx, by,
			bz, this.motion, modDesc));
		if (TreePack.active()) {
			return;
		}

		this.args.fill(cb, 0);
		Vk.barrier(cb);
		this.cull.bind(cb, ubo, paramDesc, blockDesc, Desc.ssbo(this.leafList), Desc.ssbo(this.stickList), Desc.ssbo(this.args));
		this.cull.push(cb, 0);
		this.cull.dispatch(cb, ComputeProgram.groups(trees.slotCount, 64), 1, 1);
		Vk.barrier(cb);
		this.cull.push(cb, 1);
		this.cull.dispatch(cb, 1, 1, 1);
		Vk.barrier(cb);
		this.args.copyTo(cb, this.wanted, 32, ring * 8L, 8);

		Desc lightmapTex = Desc.tex(lightmap, this.linear.handle);
		Desc packTex = Desc.tex(packShadowView != 0L ? packShadowView : lightmap, this.nearest.handle);
		Desc atlasDesc = Desc.ssbo(atlas.pixels);
		GraphicsProgram.begin(cb, width, height, colorView, null, depthView);
		GraphicsProgram leaves = this.leaves(colorFormat);
		Desc motionDesc = Desc.ssbo(this.motion);
		leaves.bind(cb, ubo, paramDesc, blockDesc, Desc.ssbo(this.leafList), atlasDesc, lightmapTex, packTex, motionDesc, modDesc);
		leaves.drawIndirect(cb, this.args, 0);
		GraphicsProgram sticks = this.sticks(colorFormat);
		sticks.bind(cb, ubo, paramDesc, blockDesc, Desc.ssbo(this.stickList), atlasDesc, lightmapTex, packTex, motionDesc, modDesc);
		sticks.drawIndirect(cb, this.args, 16);
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		TreePack.get().close();
		this.leafDraws.values().forEach(GraphicsProgram::close);
		this.stickDraws.values().forEach(GraphicsProgram::close);
		this.cull.close();
		this.sim.close();
		this.noEvents.close();
		for (VkBuf b : new VkBuf[]{this.state, this.motion, this.impulse, this.table}) {
			if (b != null) {
				b.close();
			}
		}
		this.params.close();
		this.motionReadback.close();
		for (VkBuf b : this.mods) {
			if (b != null) {
				b.close();
			}
		}
		this.args.close();
		this.wanted.close();
		for (VkBuf b : this.blocks) {
			if (b != null) {
				b.close();
			}
		}
		if (this.leafList != null) {
			this.leafList.close();
		}
		if (this.stickList != null) {
			this.stickList.close();
		}
		this.linear.close();
		this.nearest.close();
	}
}
