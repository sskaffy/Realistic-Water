package me.skaffy.client.water;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.client.compat.FrameTarget;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Sampler;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class SnowfallSystem implements AutoCloseable {
	private static final int UBO_BYTES = 256;
	private static final int UBO_SLOT = 512;
	private static final int COL_SNOWS = 1;
	private static final int REFRESH_PER_TICK = 512;
	private static final float SPAN = 32.0F;
	private static final float BELOW = 12.0F;

	private final Map<Integer, GraphicsProgram> draws = new HashMap<>();
	private final Sampler linear = new Sampler(true);
	private final VkBuf ubo = VkBuf.upload((long) Vk.FRAME_RING * UBO_SLOT, VkBuf.UNIFORM);
	private @Nullable VkBuf columns;
	private int grid;
	private int[] colX = new int[0];
	private int[] colZ = new int[0];
	private int refreshCursor;
	private double time;
	private double driftX;
	private double driftZ;
	private long lastNanos;

	boolean wanted(ClientLevel level) {
		return WaterSettings.realisticSnowfall && level.getRainLevel(1.0F) > 0.01F;
	}

	void reset() {
		java.util.Arrays.fill(this.colX, Integer.MIN_VALUE);
		this.lastNanos = 0L;
	}

	void tick(ClientLevel level, Vec3 camera) {
		if (!this.wanted(level)) {
			return;
		}
		int radius = Math.max(4, WaterSettings.snowfallRadius);
		int g = 2 * radius + 2;
		if (g != this.grid || this.columns == null) {
			if (this.columns != null) {
				Vk.destroyLater(this.columns);
			}
			this.grid = g;
			this.columns = VkBuf.upload((long) g * g * 16L, VkBuf.STORAGE);
			this.colX = new int[g * g];
			this.colZ = new int[g * g];
			java.util.Arrays.fill(this.colX, Integer.MIN_VALUE);
			ByteBuffer fresh = this.columns.mapped();
			for (int i = 0; i < g * g; i++) {
				fresh.putInt(i * 16, Integer.MIN_VALUE).putInt(i * 16 + 4, Integer.MIN_VALUE).putInt(i * 16 + 8, 0).putInt(i * 16 + 12, 0);
			}
		}
		ByteBuffer m = this.columns.mapped();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int cx = Mth.floor(camera.x);
		int cz = Mth.floor(camera.z);
		for (int z = cz - radius - 1; z <= cz + radius; z++) {
			for (int x = cx - radius - 1; x <= cx + radius; x++) {
				int i = Math.floorMod(x, g) + Math.floorMod(z, g) * g;
				if (this.colX[i] != x || this.colZ[i] != z) {
					this.writeColumn(level, m, i, x, z, pos);
				}
			}
		}
		for (int k = 0; k < REFRESH_PER_TICK; k++) {
			int i = this.refreshCursor++ % (g * g);
			if (this.colX[i] != Integer.MIN_VALUE) {
				this.writeColumn(level, m, i, this.colX[i], this.colZ[i], pos);
			}
		}
		this.refreshCursor %= g * g;
		this.columns.flush(0, (long) g * g * 16L);
	}

	private void writeColumn(ClientLevel level, ByteBuffer m, int i, int x, int z, BlockPos.MutableBlockPos pos) {
		this.colX[i] = x;
		this.colZ[i] = z;
		int flags = 0;
		float topY = level.getMinY();
		int light = 0;
		if (level.hasChunk(x >> 4, z >> 4)) {
			int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
			pos.set(x, top - 1, z);
			BlockState state = level.getBlockState(pos);
			FluidState fluid = state.getFluidState();
			var shape = state.getCollisionShape(level, pos);
			double shapeTop = shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y);
			double fluidTop = fluid.isEmpty() ? 0.0 : fluid.getHeight(level, pos);
			double surface = Math.max(shapeTop, fluidTop);
			topY = (float) (top - 1 + (surface > 0.0 ? surface : 1.0));
			pos.set(x, top, z);
			if (level.getPrecipitationAt(pos) == Biome.Precipitation.SNOW) {
				flags |= COL_SNOWS;
			}
			light = level.getBrightness(LightLayer.BLOCK, pos) | level.getBrightness(LightLayer.SKY, pos) << 4;
		}
		m.putInt(i * 16, x).putInt(i * 16 + 4, z).putFloat(i * 16 + 8, topY).putInt(i * 16 + 12, flags | light << 8);
	}

	private GraphicsProgram draw(int colorFormat) {
		return this.draws.computeIfAbsent(colorFormat, format -> new GraphicsProgram("snowfall.vert", "snowfall.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, false, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.PREMULTIPLIED),
			Desc.UBO, Desc.SSBO, Desc.TEX));
	}

	void record(VkCommandBuffer cb, FrameTarget target, LevelRenderState levelState, ClientLevel level, long lightmap) {
		if (this.columns == null) {
			return;
		}
		long now = System.nanoTime();
		float dt = this.lastNanos == 0L ? 1.0F / 60.0F : (float) Math.min((now - this.lastNanos) / 1.0e9, 0.1);
		this.lastNanos = now;
		this.time = (this.time + dt) % 3600.0;
		int radius = Math.max(4, WaterSettings.snowfallRadius);
		double width = 2.0 * radius;
		float storm = level.getThunderLevel(1.0F);
		double angle = this.time * 0.03 + 0.5 * Math.sin(this.time * 0.11);
		float steady = Math.max(WaterSettings.snowfallWind, 0.0F) * (0.5F + storm);
		float wind = steady * (float) (1.0 + 0.5 * Math.sin(this.time * 0.23) + 0.3 * Math.sin(this.time * 0.61 + 1.3));
		this.driftX = (this.driftX + Math.cos(angle) * wind * dt) % (4.0 * width);
		this.driftZ = (this.driftZ + Math.sin(angle) * wind * dt) % (4.0 * width);
		float eddies = 0.3F + 0.8F * steady;
		float snowing = level.getRainLevel(1.0F);
		int flakes = (int) Math.min(Math.max(WaterSettings.snowfallDensity, 0.0F) * width * width * SPAN * snowing * (1.0F + storm), 4_000_000.0);
		if (flakes <= 0) {
			return;
		}

		CameraRenderState camera = levelState.cameraRenderState;
		Vec3 cam = camera.pos;
		int slot = Vk.ringSlot();
		ByteBuffer b = this.ubo.mapped();
		int o = slot * UBO_SLOT;
		Matrix4f proj = new Matrix4f(camera.projectionMatrix);
		Matrix4f view = new Matrix4f(proj).invert().mul(WaterWorld.LEVEL_PROJECTION).mul(camera.viewRotationMatrix);
		proj.get(o, b);
		view.get(o + 64, b);
		put(b, o + 128, (float) wrap(cam.x, width), (float) wrap(cam.y, SPAN), (float) wrap(cam.z, width), (float) this.time);
		put(b, o + 144, (float) width, SPAN, radius, BELOW);
		put(b, o + 160, Math.max(WaterSettings.snowfallSpeed, 0.01F), 0.35F, 0.9F, Math.max(WaterSettings.snowfallFlakeSize, 0.001F));
		put(b, o + 176, (float) this.driftX, (float) this.driftZ, Mth.clamp(WaterSettings.snowfallOpacity, 0.0F, 1.0F), flakes);
		put(b, o + 192, target.width(), target.height(), 0.7F, 0.0F);
		put(b, o + 208, (float) (cam.x - Math.floor(cam.x)), (float) cam.y, (float) (cam.z - Math.floor(cam.z)), 0.0F);
		b.putInt(o + 224, Mth.floor(cam.x)).putInt(o + 228, 0).putInt(o + 232, Mth.floor(cam.z)).putInt(o + 236, this.grid);
		SkyRenderState sky = levelState.skyRenderState;
		Vector3f skyColor = sky.skyColor != null ? new Vector3f(sky.skyColor) : new Vector3f(0.5F, 0.6F, 0.75F);
		put(b, o + 240, skyColor.x, skyColor.y, skyColor.z, eddies);
		this.ubo.flush(o, UBO_BYTES);

		GraphicsProgram p = this.draw(target.colorFormat());
		Vk.barrier(cb);
		GraphicsProgram.begin(cb, target.width(), target.height(), target.colorView(), null, target.depthView());
		p.bind(cb, Desc.ubo(this.ubo, o, UBO_BYTES), Desc.ssbo(this.columns), Desc.tex(lightmap, this.linear.handle));
		p.draw(cb, 6, flakes);
		GraphicsProgram.end(cb);
	}

	private static double wrap(double value, double period) {
		double m = value % period;
		return m < 0.0 ? m + period : m;
	}

	private static void put(ByteBuffer b, int o, float x, float y, float z, float w) {
		b.putFloat(o, x).putFloat(o + 4, y).putFloat(o + 8, z).putFloat(o + 12, w);
	}

	@Override
	public void close() {
		this.draws.values().forEach(GraphicsProgram::close);
		this.linear.close();
		Vk.destroyLater(this.ubo);
		if (this.columns != null) {
			Vk.destroyLater(this.columns);
		}
	}
}
