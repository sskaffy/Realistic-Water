package me.skaffy.client.grass;

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
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class GrassPack {
	private static final GrassPack INSTANCE = new GrassPack();
	private static final Identifier WHITE = RealisticWater.id("textures/grass/white.png");
	private static final Identifier CARD = Identifier.withDefaultNamespace("textures/block/short_grass.png");
	private static final int ENTITY_STRIDE = 9;

	record Frame(VkBuf params, long paramsOffset, long paramsBytes, VkBuf blocks, long blocksBytes, VkBuf state, long totalUnits, int style,
		int bx, int by, int bz) {
	}

	private @Nullable Frame frame;
	private @Nullable ComputeProgram mesh;
	private @Nullable VkBuf dummyUbo;
	private @Nullable VkBuf counter;
	private @Nullable VkBuf readback;
	private @Nullable VkBuf vertices;
	private @Nullable GameVertexBuffer gameVertices;
	private long capacityQuads;
	private int stride = ENTITY_STRIDE;
	private int wantedStride = ENTITY_STRIDE;
	private boolean prepared;
	private boolean ready;
	private int drawQuads;
	private boolean failedSaid;

	private GrassPack() {
	}

	public static GrassPack get() {
		return INSTANCE;
	}

	public static boolean active() {
		return WaterSettings.grassPackDraw && ShaderCompat.VITRAIL && ShaderCompat.packRunning() && RealisticGrass.get().wanted();
	}

	void stepped(Frame f) {
		this.frame = f;
	}

	private static Identifier texture(int style) {
		return style == 2 ? CARD : WHITE;
	}

	private static RenderType renderType(int style) {
		return RenderTypes.entityCutout(texture(style));
	}

	public void submit(SubmitNodeCollector collector, Vec3 camera) {
		if (!active()) {
			return;
		}
		this.prepare(camera);
		Frame f = this.frame;
		if (!this.ready || f == null) {
			return;
		}
		collector.submitCustomGeometry(new PoseStack(), renderType(f.style()), GrassPack::placeholder);
	}

	private static void placeholder(PoseStack.Pose p, VertexConsumer buf) {
		for (int k = 0; k < 4; k++) {
			buf.addVertex(p, 0.0F, 0.0F, 0.0F).setColor(0).setUv(0.0F, 0.0F).setOverlay(OverlayTexture.NO_OVERLAY).setLight(0)
				.setNormal(p, 0.0F, 1.0F, 0.0F);
		}
	}

	public static boolean isOurs(PreparedRenderType prepared) {
		GrassPack pack = INSTANCE;
		Frame f = pack.frame;
		if (!pack.ready || f == null) {
			return false;
		}
		var view = Minecraft.getInstance().getTextureManager().getTexture(texture(f.style())).getTextureView();
		for (PreparedRenderType.Texture t : prepared.textures()) {
			if (t.textureView() == view) {
				return true;
			}
		}
		return false;
	}

	public StagedVertexBuffer.@Nullable ExecuteInfo executeInfo(StagedVertexBuffer.Draw draw, StagedVertexBuffer.ExecuteInfo original) {
		GameVertexBuffer verts = this.gameVertices;
		if (!this.ready || verts == null || this.drawQuads <= 0) {
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
		int indexCount = this.drawQuads * 6;
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
		if (f == null || f.totalUnits() <= 0L) {
			return;
		}
		try {
			ShaderCompat.runOutsidePasses(() -> this.record(f, camera));
		} catch (RuntimeException e) {
			this.ready = false;
			if (!this.failedSaid) {
				this.failedSaid = true;
				RealisticWater.LOGGER.error("Realistic grass couldn't build its shader-pack mesh; the pack won't draw it", e);
			}
		}
	}

	private static int quadsPerUnit(int style) {
		return style == 1 ? 5 * 2 : 4;
	}

	private void record(Frame f, Vec3 camera) {
		if (this.mesh == null) {
			this.mesh = new ComputeProgram("grass_pack.comp", Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO);
			this.dummyUbo = VkBuf.upload(8192, VkBuf.UNIFORM);
			this.counter = VkBuf.device(16, 0);
			this.readback = VkBuf.readback(Vk.FRAME_RING * 4L);
			for (int i = 0; i < Vk.FRAME_RING; i++) {
				this.readback.mapped().putInt(i * 4, 0);
			}
		}
		long need = f.totalUnits() * quadsPerUnit(f.style());
		if (this.vertices == null || this.capacityQuads < need || this.stride != this.wantedStride) {
			if (this.vertices != null) {
				this.gameVertices.close();
				Vk.destroyLater(this.vertices);
			}
			this.stride = this.wantedStride;
			this.capacityQuads = Math.max(need, Math.max(this.capacityQuads * 3 / 2, 65536L));
			this.capacityQuads = Math.min(this.capacityQuads, (Integer.MAX_VALUE / 6) - 64L);
			this.vertices = VkBuf.device(this.capacityQuads * 4L * this.stride * 4L, VkBuf.VERTEX);
			this.gameVertices = new GameVertexBuffer(this.vertices);
		}
		ByteBuffer rb = this.readback.mapped();
		this.readback.invalidate(0, Vk.FRAME_RING * 4L);
		long recent = 0L;
		for (int i = 0; i < Vk.FRAME_RING; i++) {
			recent = Math.max(recent, Integer.toUnsignedLong(rb.getInt(i * 4)));
		}
		this.drawQuads = (int) Math.min(this.capacityQuads, recent + recent / 4 + 4096L);
		RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(this.drawQuads * 6);

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
			Desc blocks = Desc.ssbo(f.blocks(), 0, Math.max(f.blocksBytes(), 32L));
			this.mesh.bind(cb, ubo, params, blocks, Desc.ssbo(f.state()), Desc.ssbo(this.vertices), Desc.ssbo(this.counter));
			this.mesh.push(cb, Program.f(ox), Program.f(oy), Program.f(oz), 0, this.stride, (int) this.capacityQuads, 0, this.drawQuads);
			this.mesh.dispatch(cb, ComputeProgram.groups(f.totalUnits(), 128), 1, 1);
			Vk.barrier(cb);
			this.mesh.push(cb, Program.f(ox), Program.f(oy), Program.f(oz), 0, this.stride, (int) this.capacityQuads, 1, this.drawQuads);
			this.mesh.dispatch(cb, ComputeProgram.groups(this.drawQuads, 128), 1, 1);
			Vk.barrier(cb);
			this.counter.copyTo(cb, this.readback, 0, Vk.ringSlot() * 4L, 4);
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
		this.capacityQuads = 0L;
	}
}
