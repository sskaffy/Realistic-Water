package me.skaffy.client.grass;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class GrassRenderer implements AutoCloseable {
	private static final long PARAMS_BYTES = 8192;
	private static final float NEAR_CASCADE = 12.0F;
	private static final long STATE_BYTES = 10 * 16;
	private static final long GRAB_BYTES = 32 + 64 * 32;


	private final ComputeProgram step;
	private final Map<Integer, GraphicsProgram> draws = new HashMap<>();
	private final VkBuf params = VkBuf.upload(PARAMS_BYTES * Vk.FRAME_RING, VkBuf.STORAGE);
	private final VkBuf[] blocks = new VkBuf[Vk.FRAME_RING];
	private final long[] blockCapacity = new long[Vk.FRAME_RING];
	private VkBuf state;
	private long stateUnits;
	private final Sampler linear = new Sampler(true);
	private double windAngle = 0.6;

	public GrassRenderer() {
		this.step = new ComputeProgram("grass_step.comp", Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO);
	}

	private GraphicsProgram draw(int colorFormat) {
		return this.draws.computeIfAbsent(colorFormat, format -> new GraphicsProgram("grass.vert", "grass.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO,
			Desc.SSBO, Desc.SSBO, Desc.TEX, Desc.TEX, Desc.TEX));
	}

	private GraphicsProgram ground(int colorFormat) {
		return this.grounds.computeIfAbsent(colorFormat, format -> new GraphicsProgram("fullscreen.vert", "grass_ground.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, false, false, VK10.VK_COMPARE_OP_ALWAYS, GraphicsProgram.Blend.PREMULTIPLIED), Desc.UBO,
			Desc.SSBO, Desc.SSBO, Desc.TEX, Desc.TEX));
	}

	private final Map<Integer, GraphicsProgram> grounds = new HashMap<>();
	private final VkBuf grabBuf = VkBuf.device(GRAB_BYTES, 0);
	private final VkBuf floor = VkBuf.upload((long) RealisticGrass.FLOOR * RealisticGrass.FLOOR * (RealisticGrass.FLOOR / 32) * 4L * Vk.FRAME_RING,
		VkBuf.STORAGE);
	private final VkBuf flow = VkBuf.device((1L << 18) * 16L, 0);
	private int grabStage;
	private @org.jspecify.annotations.Nullable GraphicsProgram shadowDraw;
	private @org.jspecify.annotations.Nullable VkTex shadowColor;
	private @org.jspecify.annotations.Nullable VkTex shadowDepth;
	private @org.jspecify.annotations.Nullable VkTex sceneDepth;
	private final Sampler nearest = new Sampler(false);

	static boolean shadowsWanted() {
		return switch (WaterSettings.grassShadows) {
			case 1 -> me.skaffy.client.compat.ShaderCompat.packRunning();
			case 2 -> true;
			default -> false;
		};
	}

	private static org.joml.@org.jspecify.annotations.Nullable Vector3f lightDirection(Minecraft mc) {
		var sky = mc.gameRenderer.gameRenderState().levelRenderState.skyRenderState;
		org.joml.Vector3f sun = new org.joml.Matrix3f().rotateY((float) Math.toRadians(-90.0)).rotateX(sky.sunAngle)
			.transform(new org.joml.Vector3f(0.0F, 1.0F, 0.0F)).normalize();
		if (sun.y < 0.0F) {
			sun.negate();
		}
		return sun.y < 0.08F ? null : sun;
	}

	private static org.joml.Matrix4f shadowMatrix(org.joml.Vector3f sun, float radius, int res, int bx, int by, int bz) {
		org.joml.Matrix4d view = new org.joml.Matrix4d().lookAt(sun.x * radius * 2.0, sun.y * radius * 2.0, sun.z * radius * 2.0, 0.0, 0.0, 0.0,
			Math.abs(sun.y) > 0.99F ? 1.0 : 0.0, Math.abs(sun.y) > 0.99F ? 0.0 : 1.0, 0.0);
		org.joml.Matrix4d proj = new org.joml.Matrix4d().setOrtho(-radius, radius, -radius, radius, 0.0, radius * 4.0, true);
		org.joml.Matrix4d vp = proj.mul(view);
		org.joml.Vector4d origin = vp.transform(new org.joml.Vector4d(-bx, -by, -bz, 1.0));
		double texel = 2.0 / res;
		double dx = Math.round(origin.x / texel) * texel - origin.x;
		double dy = Math.round(origin.y / texel) * texel - origin.y;
		return new org.joml.Matrix4f(new org.joml.Matrix4d().translation(dx, dy, 0.0).mul(vp));
	}

	private static int verticesPerUnit(int style) {
		return switch (style) {
			case 1 -> 5 * 2 * 6;
			case 2 -> 4 * 6;
			default -> 4 * 6;
		};
	}

	public void record(VkCommandBuffer cb, Desc ubo, int width, int height, int colorFormat, long colorView, long depthImage, long depthView,
		long lightmap, long packShadowView, float frameDt, java.util.function.@org.jspecify.annotations.Nullable Consumer<VkBuf> waterSplat) {
		Minecraft mc = Minecraft.getInstance();
		RealisticGrass grass = RealisticGrass.get();
		ClientLevel level = mc.level;
		if (level == null || grass.slotCount <= 0) {
			return;
		}
		int ring = Vk.ringSlot();
		int units = RealisticGrass.unitsPerBlock();
		int style = Mth.clamp(WaterSettings.grassStyle, 0, 2);
		long totalUnits = (long) grass.slotCount * units;

		long need = (long) grass.slotCount * 32L;
		if (this.blocks[ring] == null || this.blockCapacity[ring] < need) {
			if (this.blocks[ring] != null) {
				Vk.destroyLater(this.blocks[ring]);
			}
			this.blockCapacity[ring] = Math.max(need, 64L * 1024L);
			this.blocks[ring] = VkBuf.upload(this.blockCapacity[ring], VkBuf.STORAGE);
		}
		grass.writeBlocks(this.blocks[ring].mapped(), 0);
		this.blocks[ring].flush(0, need);

		boolean fresh = false;
		if (this.state == null || this.stateUnits < totalUnits) {
			if (this.state != null) {
				Vk.destroyLater(this.state);
			}
			this.stateUnits = Math.max(totalUnits, 65536L);
			this.state = VkBuf.device(this.stateUnits * STATE_BYTES, 0);
			fresh = true;
		}
		if (fresh) {
			this.state.fill(cb, 0);
		}
		this.flow.fill(cb, 0);
		Vk.barrier(cb);
		if (waterSplat != null) {
			waterSplat.accept(this.flow);
		}
		if (grass.grabPending) {
			grass.grabPending = false;
			this.grabBuf.fill(cb, 0);
			Vk.barrier(cb);
			this.grabBuf.fill(cb, 0, 4, 0xFFFFFFFF);
			this.grabStage = 1;
		} else if (!grass.grabHeld) {
			this.grabStage = 0;
		} else if (this.grabStage == 1) {
			this.grabStage = 2;
		} else if (this.grabStage == 2) {
			this.grabStage = grass.grabMulti ? 3 : 4;
		} else if (this.grabStage == 3) {
			this.grabStage = 4;
		}
		Vk.barrier(cb);

		float dt = Math.min(Math.max(frameDt, 0.0F), 1.0F / 30.0F);
		grass.time += dt;
		Vec3 cam = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		int bx = Mth.floor(cam.x);
		int by = Mth.floor(cam.y);
		int bz = Mth.floor(cam.z);
		float partial = mc.gameRenderer.gameRenderState().levelRenderState.worldPartialTicks;
		List<float[]> colliders = grass.colliders(level, cam, bx, by, bz, partial);
		ByteBuffer p = this.params.mapped();
		int o = (int) (ring * PARAMS_BYTES);
		p.putInt(o, bx).putInt(o + 4, by).putInt(o + 8, bz).putInt(o + 12, grass.slotCount);
		p.putInt(o + 16, units).putInt(o + 20, style).putInt(o + 24, colliders.size()).putInt(o + 28, Mth.clamp(WaterSettings.grassPush, 0, 1));
		float recovery = WaterSettings.grassTrails ? 1.0F / Math.max(WaterSettings.grassTrailTime, 0.5F) : 2.5F;
		p.putFloat(o + 32, grass.time % 3600.0F).putFloat(o + 36, dt).putFloat(o + 40, recovery).putFloat(o + 44, 12.0F);
		this.windAngle += dt * 0.004;
		float weather = level.isThundering() ? 2.4F : level.isRaining() ? 1.7F : 1.0F;
		float wx = (float) Math.cos(this.windAngle + 0.35 * Math.sin(grass.time * 0.013));
		float wz = (float) Math.sin(this.windAngle + 0.35 * Math.sin(grass.time * 0.013));
		p.putFloat(o + 48, wx).putFloat(o + 52, wz).putFloat(o + 56, Math.max(WaterSettings.grassWind, 0.0F) * weather).putFloat(o + 60, 0.07F);
		float stiffness = Math.max(WaterSettings.grassStiffness, 0.05F);
		p.putFloat(o + 64, 260.0F * stiffness).putFloat(o + 68, 0.7F)
			.putFloat(o + 72, Math.max(WaterSettings.grassHeight, 0.05F)).putFloat(o + 76, Math.max(WaterSettings.grassWidth, 0.05F));
		float radius = Math.max(8, WaterSettings.grassRadius);
		p.putFloat(o + 80, radius).putFloat(o + 84, radius * 0.55F).putFloat(o + 88, WaterSettings.grassCartoon ? 1.0F : 0.0F).putFloat(o + 92, 0.0F);
		org.joml.Vector3f sun = lightDirection(mc);
		boolean shadows = shadowsWanted() && sun != null;
		int res = Mth.clamp(WaterSettings.grassShadowResolution, 256, 8192);
		float span = radius + 4.0F;
		org.joml.Matrix4f shadowVP = shadows ? shadowMatrix(sun, span, res, bx, by, bz) : new org.joml.Matrix4f();
		shadowVP.get(o + 96, p);
		float nearSpan = Math.min(NEAR_CASCADE, span);
		org.joml.Matrix4f nearVP = shadows ? shadowMatrix(sun, nearSpan, res, bx, by, bz) : new org.joml.Matrix4f();
		nearVP.get(o + 6416, p);
		p.putFloat(o + 6480, shadows ? nearSpan : 0.0F).putFloat(o + 6484, 0.0F).putFloat(o + 6488, 0.03F / (nearSpan * 4.0F)).putFloat(o + 6492, 0.0F);
		grass.updateFloor(level, bx, by, bz);
		p.putInt(o + 6496, grass.floorX).putInt(o + 6500, grass.floorY).putInt(o + 6504, grass.floorZ).putInt(o + 6508, grass.floorValid ? 1 : 0);
		long floorBytes = (long) grass.floorBits.length * 4L;
		this.floor.mapped().asIntBuffer().put((int) (ring * floorBytes / 4L), grass.floorBits);
		this.floor.flush(ring * floorBytes, floorBytes);
		net.minecraft.client.player.LocalPlayer player = mc.player;
		Vec3 eye = player != null ? player.getEyePosition(partial) : cam;
		Vec3 look = player != null ? player.getViewVector(partial) : new Vec3(0.0, 0.0, 1.0);
		p.putFloat(o + 176, (float) (eye.x - bx)).putFloat(o + 180, (float) (eye.y - by)).putFloat(o + 184, (float) (eye.z - bz))
			.putFloat(o + 188, this.grabStage);
		p.putFloat(o + 192, (float) look.x).putFloat(o + 196, (float) look.y).putFloat(o + 200, (float) look.z)
			.putFloat(o + 204, grass.grabMulti ? Mth.clamp(WaterSettings.grassGrabRadius, 0.01F, 2.0F) : 0.0F);
		p.putFloat(o + 208, Math.max(WaterSettings.grassLieTime, 1.0F)).putFloat(o + 212, Math.max(WaterSettings.grassRegrowTime, 0.1F))
			.putFloat(o + 216, Math.max(WaterSettings.grassPullStrength, 0.01F)).putFloat(o + 220, 0.0F);
		for (int k = 0; k < 12; k++) {
			p.putInt(o + 224 + k * 4, grass.grabSlots[k]);
		}
		p.putFloat(o + 160, shadows ? 1.0F : 0.0F).putFloat(o + 164, Mth.clamp(WaterSettings.grassShadowStrength, 0.0F, 1.0F))
			.putFloat(o + 168, 1.0F / res).putFloat(o + 172, 0.04F / (span * 4.0F));
		for (int i = 0; i < colliders.size(); i++) {
			float[] c = colliders.get(i);
			int co = o + 272 + i * 48;
			for (int f = 0; f < 12; f++) {
				p.putFloat(co + f * 4, c[f]);
			}
		}
		this.params.flush(ring * PARAMS_BYTES, PARAMS_BYTES);
		Desc paramDesc = Desc.ssbo(this.params, ring * PARAMS_BYTES, PARAMS_BYTES);
		Desc blockDesc = Desc.ssbo(this.blocks[ring], 0, Math.max(need, 32L));
		Desc stateDesc = Desc.ssbo(this.state);

		long floorSlot = (long) grass.floorBits.length * 4L;
		this.step.bind(cb, ubo, paramDesc, blockDesc, stateDesc, Desc.ssbo(this.grabBuf), Desc.ssbo(this.flow), Desc.ssbo(this.floor, ring * floorSlot, floorSlot));
		this.step.dispatch(cb, ComputeProgram.groups(totalUnits, 128), 1, 1);
		Vk.barrier(cb);
		GrassPack.get().stepped(new GrassPack.Frame(this.params, ring * PARAMS_BYTES, PARAMS_BYTES, this.blocks[ring], need, this.state, totalUnits,
			style, bx, by, bz));
		if (GrassPack.active()) {
			return;
		}

		Desc lightmapTex = Desc.tex(lightmap, this.linear.handle);
		Desc packTex = Desc.tex(packShadowView != 0L ? packShadowView : lightmap, this.nearest.handle);
		int instances = (int) Math.min(totalUnits, Integer.MAX_VALUE);
		Desc shadowTex = lightmapTex;
		if (shadows) {
			if (this.shadowColor == null || this.shadowColor.height != res) {
				if (this.shadowColor != null) {
					Vk.destroyLater(this.shadowColor);
					Vk.destroyLater(this.shadowDepth);
				}
				this.shadowColor = new VkTex(VkTex.R32F, res * 2, res, VkTex.COLOR | VkTex.SAMPLED | VkTex.COPY_DST);
				this.shadowDepth = new VkTex(VkTex.D32, res * 2, res, VkTex.DEPTH | VkTex.COPY_DST);
			}
			if (this.shadowDraw == null) {
				this.shadowDraw = new GraphicsProgram("grass.vert", "grass.frag", Map.of("SHADOW", "1"),
					new GraphicsProgram.State(VkTex.R32F, VkTex.D32, true, true, VK10.VK_COMPARE_OP_LESS, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO,
					Desc.SSBO, Desc.SSBO, Desc.TEX, Desc.TEX, Desc.TEX);
			}
			this.shadowColor.clear(cb, 1.0F, 1.0F, 1.0F, 1.0F);
			this.shadowDepth.clearDepth(cb, 1.0F);
			Vk.barrier(cb);
			GraphicsProgram.begin(cb, res * 2, res, this.shadowColor.view, null, this.shadowDepth.view);
			this.shadowDraw.bind(cb, ubo, paramDesc, blockDesc, stateDesc, lightmapTex, lightmapTex, lightmapTex);
			for (int cascade = 0; cascade < 2; cascade++) {
				this.shadowDraw.push(cb, cascade);
				this.shadowDraw.draw(cb, verticesPerUnit(style), instances);
			}
			GraphicsProgram.end(cb);
			Vk.barrier(cb);
			shadowTex = Desc.tex(this.shadowColor, this.nearest.handle);

			if (this.sceneDepth == null || this.sceneDepth.width != width || this.sceneDepth.height != height) {
				if (this.sceneDepth != null) {
					Vk.destroyLater(this.sceneDepth);
				}
				this.sceneDepth = new VkTex(VkTex.D32, width, height, VkTex.SAMPLED | VkTex.COPY_DST);
			}
			VkTex.copy(cb, depthImage, this.sceneDepth.image, VK10.VK_IMAGE_ASPECT_DEPTH_BIT, width, height);
			Vk.barrier(cb);
			GraphicsProgram ground = this.ground(colorFormat);
			GraphicsProgram.begin(cb, width, height, colorView, null, 0L);
			ground.bind(cb, ubo, paramDesc, blockDesc, Desc.tex(this.sceneDepth, this.nearest.handle), shadowTex);
			ground.draw(cb, 3, 1);
			GraphicsProgram.end(cb);
			Vk.barrier(cb);
		}

		GraphicsProgram draw = this.draw(colorFormat);
		GraphicsProgram.begin(cb, width, height, colorView, null, depthView);
		draw.bind(cb, ubo, paramDesc, blockDesc, stateDesc, lightmapTex, shadowTex, packTex);
		draw.draw(cb, verticesPerUnit(style), instances);
		GraphicsProgram.end(cb);
	}

	@Override
	public void close() {
		GrassPack.get().close();
		this.draws.values().forEach(GraphicsProgram::close);
		this.grounds.values().forEach(GraphicsProgram::close);
		if (this.shadowDraw != null) {
			this.shadowDraw.close();
		}
		for (VkTex tex : new VkTex[]{this.shadowColor, this.shadowDepth, this.sceneDepth}) {
			if (tex != null) {
				tex.close();
			}
		}
		this.nearest.close();
		this.step.close();
		this.params.close();
		this.grabBuf.close();
		this.floor.close();
		this.flow.close();
		for (VkBuf b : this.blocks) {
			if (b != null) {
				b.close();
			}
		}
		if (this.state != null) {
			this.state.close();
		}
		this.linear.close();
	}
}
