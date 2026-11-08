package me.skaffy.client.trees;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.nio.ByteBuffer;
import me.skaffy.RealisticWater;
import me.skaffy.client.compat.ShaderCompat;
import me.skaffy.client.mixin.StagedDrawAccessor;
import me.skaffy.client.vk.ComputeProgram;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GameVertexBuffer;
import me.skaffy.client.vk.Program;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.water.WaterSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class TreePack {
	private static final TreePack INSTANCE = new TreePack();
	private static final int ENTITY_STRIDE = 9;

	record Frame(VkBuf params, long paramsOffset, long paramsBytes, VkBuf blocks, long blocksBytes, int slots, int bx, int by, int bz, VkBuf motion,
		Desc mods) {
	}

	private @Nullable Frame frame;
	private @Nullable ComputeProgram mesh;
	private @Nullable VkBuf dummyUbo;
	private @Nullable VkBuf counter;
	private @Nullable VkBuf readback;
	private @Nullable VkBuf vertices;
	private @Nullable GameVertexBuffer gameVertices;
	private long capA;
	private long capB;
	private int stride = ENTITY_STRIDE;
	private int wantedStride = ENTITY_STRIDE;
	private boolean prepared;
	private boolean ready;
	private int drawA;
	private int drawB;
	private boolean failedSaid;

	private TreePack() {
	}

	public static TreePack get() {
		return INSTANCE;
	}

	public static boolean active() {
		return WaterSettings.treePackDraw && ShaderCompat.VITRAIL && ShaderCompat.packRunning() && RealisticTrees.get().wanted();
	}

	void prepared(Frame f) {
		this.frame = f;
	}

	public void submit(SubmitNodeCollector collector, Vec3 camera) {
		this.submit(collector, camera, TreeAtlas.ID);
	}

	public void submitShadow(SubmitNodeCollector collector, Vec3 camera) {
		this.submit(collector, camera, TreeAtlas.SHADOW_ID);
	}

	private void submit(SubmitNodeCollector collector, Vec3 camera, net.minecraft.resources.Identifier texture) {
		if (!active()) {
			return;
		}
		this.prepare(camera);
		if (!this.ready || this.frame == null) {
			return;
		}
		collector.submitCustomGeometry(new PoseStack(), RenderTypes.entityCutout(texture), TreePack::placeholder);
	}

	private static void placeholder(PoseStack.Pose p, VertexConsumer buf) {
		for (int k = 0; k < 4; k++) {
			buf.addVertex(p, 0.0F, 0.0F, 0.0F).setColor(0).setUv(0.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0)
				.setNormal(p, 0.0F, 1.0F, 0.0F);
		}
	}

	public static int whichDraw(PreparedRenderType prepared) {
		TreePack pack = INSTANCE;
		if (!pack.ready || pack.frame == null || RealisticTrees.get().atlas() == null) {
			return 0;
		}
		var textures = Minecraft.getInstance().getTextureManager();
		var main = textures.getTexture(TreeAtlas.ID).getTextureView();
		var shadow = textures.getTexture(TreeAtlas.SHADOW_ID).getTextureView();
		for (PreparedRenderType.Texture t : prepared.textures()) {
			if (t.textureView() == main) {
				return 1;
			}
			if (t.textureView() == shadow) {
				return 2;
			}
		}
		return 0;
	}

	public StagedVertexBuffer.@Nullable ExecuteInfo executeInfo(StagedVertexBuffer.Draw draw, boolean shadow) {
		GameVertexBuffer verts = this.gameVertices;
		long quads = shadow ? this.drawA : this.capA + this.drawB;
		if (!this.ready || verts == null || quads <= 0L || quads > Integer.MAX_VALUE / 6) {
			return null;
		}
		int size = ((StagedDrawAccessor) draw).realisticWater$format().getVertexSize();
		if (size % 4 != 0 || size / 4 < ENTITY_STRIDE) {
			return null;
		}
		if (size / 4 != this.stride) {
			this.wantedStride = size / 4;
			return null;
		}
		PrimitiveTopology topology = ((StagedDrawAccessor) draw).realisticWater$topology();
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(topology);
		int indexCount = (int) quads * 6;
		if (!indices.hasStorage(indexCount)) {
			return null;
		}
		return new StagedVertexBuffer.ExecuteInfo(verts, null, indices.type(), 0, 0, indexCount, topology);
	}

	public void beginFrame() {
		this.prepared = false;
	}

	private void prepare(Vec3 camera) {
		if (this.prepared) {
			return;
		}
		this.prepared = true;
		this.ready = false;
		Frame f = this.frame;
		if (f == null || f.slots() <= 0) {
			return;
		}
		try {
			ShaderCompat.runOutsidePasses(() -> this.record(f, camera));
		} catch (RuntimeException e) {
			this.ready = false;
			if (!this.failedSaid) {
				this.failedSaid = true;
				RealisticWater.LOGGER.error("Realistic tree leaves couldn't build their shader-pack mesh; the pack won't draw them", e);
			}
		}
	}

	private void record(Frame f, Vec3 camera) {
		if (this.mesh == null) {
			this.mesh = new ComputeProgram("tree_pack.comp", Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO);
			this.dummyUbo = VkBuf.upload(8192, VkBuf.UNIFORM);
			this.counter = VkBuf.device(16, 0);
			this.readback = VkBuf.readback(Vk.FRAME_RING * 8L);
			for (int i = 0; i < Vk.FRAME_RING * 2; i++) {
				this.readback.mapped().putInt(i * 4, 0);
			}
		}
		ByteBuffer rb = this.readback.mapped();
		this.readback.invalidate(0, Vk.FRAME_RING * 8L);
		long recentA = 0L;
		long recentB = 0L;
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			recentA = Math.max(recentA, Integer.toUnsignedLong(rb.getInt(i * 8)));
			recentB = Math.max(recentB, Integer.toUnsignedLong(rb.getInt(i * 8 + 4)));
		}
		long guess = Math.min((long) f.slots() * Math.max(WaterSettings.treeLeavesPerBlock, 1) / 4, 1L << 22);
		long needA = Math.max(recentA + recentA / 4 + 4096L, guess / 2);
		long needB = Math.max(recentB + recentB / 4 + 4096L, guess);
		if (this.vertices == null || this.capA < needA || this.capB < needB || this.stride != this.wantedStride) {
			if (this.vertices != null) {
				this.gameVertices.close();
				Vk.destroyLater(this.vertices);
			}
			this.stride = this.wantedStride;
			long limit = (Integer.MAX_VALUE / 6) / 2 - 64L;
			this.capA = Math.min(Math.max(needA, Math.max(this.capA * 3 / 2, 32768L)), limit);
			this.capB = Math.min(Math.max(needB, Math.max(this.capB * 3 / 2, 32768L)), limit);
			this.vertices = VkBuf.device((this.capA + this.capB) * 4L * this.stride * 4L, VkBuf.VERTEX);
			this.gameVertices = new GameVertexBuffer(this.vertices);
		}
		this.drawA = (int) Math.min(this.capA, recentA + recentA / 4 + 4096L);
		this.drawB = (int) Math.min(this.capB, recentB + recentB / 4 + 4096L);
		RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer((int) (this.capA + this.drawB) * 6);

		float ox = (float) (f.bx() - camera.x);
		float oy = (float) (f.by() - camera.y);
		float oz = (float) (f.bz() - camera.z);
		VkCommandBuffer cb = Vk.encoder().allocateAndBeginTransientCommandBuffer();
		Vk.beginRecording(cb);
		try {
			Vk.barrier(cb);
			this.counter.fill(cb, 0);
			Vk.barrier(cb);
			Desc ubo = Desc.ubo(this.dummyUbo, 0, 8192);
			Desc params = Desc.ssbo(f.params(), f.paramsOffset(), f.paramsBytes());
			Desc blocks = Desc.ssbo(f.blocks(), 0, Math.max(f.blocksBytes(), (long) TreeRenderer.SLOT_BYTES));
			this.mesh.bind(cb, ubo, params, blocks, Desc.ssbo(this.vertices), Desc.ssbo(this.counter), Desc.ssbo(f.motion()), f.mods());
			int total = (int) (this.capA + this.capB);
			this.mesh.push(cb, Program.f(ox), Program.f(oy), Program.f(oz), 0, this.stride, total, 0, this.drawB, (int) this.capA, 0, 0, 0);
			this.mesh.dispatch(cb, ComputeProgram.groups(f.slots(), 64), 1, 1);
			Vk.barrier(cb);
			this.mesh.push(cb, Program.f(ox), Program.f(oy), Program.f(oz), 0, this.stride, total, 1, this.drawB, (int) this.capA, 0, 0, 0);
			this.mesh.dispatch(cb, ComputeProgram.groups(this.capA + this.drawB, 64), 1, 1);
			Vk.barrier(cb);
			this.counter.copyTo(cb, this.readback, 0, Vk.ringSlot() * 8L, 8);
			Vk.barrier(cb);
		} finally {
			Vk.endRecording();
		}
		Vk.check(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer");
		Vk.encoder().execute(cb);
		this.ready = true;
	}

	public void close() {
		this.ready = false;
		this.frame = null;
		this.prepared = false;
		if (this.gameVertices != null) {
			this.gameVertices.close();
			this.gameVertices = null;
		}
		for (AutoCloseable c : new AutoCloseable[]{this.mesh, this.dummyUbo, this.counter, this.readback, this.vertices}) {
			if (c != null) {
				Vk.destroyLater(c);
			}
		}
		this.mesh = null;
		this.dummyUbo = null;
		this.counter = null;
		this.readback = null;
		this.vertices = null;
		this.capA = 0L;
		this.capB = 0L;
	}
}
