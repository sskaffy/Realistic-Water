package me.skaffy.client.water;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.client.compat.FrameTarget;
import me.skaffy.client.vk.ComputeProgram;
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
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

final class RainSystem implements AutoCloseable {
	private static final int UBO_BYTES = 304;
	private static final int COL_RAINS = 1;
	private static final int COL_WATER = 2;
	private static final int COL_LAVA = 8;
	static final int EVENT_CAPACITY = 8192;

	record Kicks(VkBuf events, int originX, int originZ, int capacity) {
	}
	private static final int REFRESH_PER_TICK = 512;
	private static final float SPAWN_ABOVE = 22.0F;
	private static final float BELOW = 14.0F;
	private static final int ORIGIN_DRIFT = 512;

	private final ComputeProgram step = new ComputeProgram("rain_step.comp", Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.SSBO);
	private final ComputeProgram splashStep = new ComputeProgram("rain_splash_step.comp", Desc.UBO, Desc.SSBO);
	private final Map<Integer, GraphicsProgram[]> draws = new HashMap<>();
	private final Sampler linear = new Sampler(true);
	private final VkBuf ubo = VkBuf.upload((long) Vk.FRAME_RING * 512L, VkBuf.UNIFORM);
	private @Nullable VkBuf drops;
	private @Nullable VkBuf splashes;
	private final VkBuf cursor = VkBuf.device(16, 0);
	private int capacity;
	private int splashCapacity;
	private @Nullable VkBuf columns;
	private int grid;
	private int[] colX = new int[0];
	private int[] colZ = new int[0];
	private int refreshCursor;
	private boolean originSet;
	private int originX;
	private int originZ;
	private double time;
	private long lastNanos;
	private int seed = 1;
	private final VkBuf[] events = new VkBuf[Vk.FRAME_RING];
	private final int[][] eventOrigin = new int[Vk.FRAME_RING][];
	private int lastSlot = -1;
	private final java.util.List<double[]> steam = new java.util.ArrayList<>();

	boolean wanted(ClientLevel level) {
		return WaterSettings.realisticRain && level.getRainLevel(1.0F) > 0.01F;
	}

	@Nullable Kicks kicks() {
		if (this.lastSlot < 0 || this.events[this.lastSlot] == null || this.eventOrigin[this.lastSlot] == null) {
			return null;
		}
		int[] o = this.eventOrigin[this.lastSlot];
		return new Kicks(this.events[this.lastSlot], o[0], o[1], EVENT_CAPACITY);
	}

	java.util.List<double[]> takeSteam() {
		java.util.List<double[]> out = new java.util.ArrayList<>(this.steam);
		this.steam.clear();
		return out;
	}

	void reset() {
		this.originSet = false;
		this.lastSlot = -1;
		java.util.Arrays.fill(this.colX, Integer.MIN_VALUE);
		this.lastNanos = 0L;
	}

	void tick(ClientLevel level, Vec3 camera) {
		if (!this.wanted(level)) {
			this.lastSlot = -1;
			return;
		}
		int radius = Math.max(4, WaterSettings.rainRadius);
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
			ByteBuffer m = this.columns.mapped();
			for (int i = 0; i < g * g; i++) {
				m.putInt(i * 16, Integer.MIN_VALUE).putInt(i * 16 + 4, Integer.MIN_VALUE).putInt(i * 16 + 8, 0).putInt(i * 16 + 12, 0);
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
			boolean water = !fluid.isEmpty() && fluid.is(FluidTags.WATER);
			boolean lava = !fluid.isEmpty() && fluid.is(FluidTags.LAVA) || state.is(Blocks.MAGMA_BLOCK) || CampfireBlock.isLitCampfire(state);
			pos.set(x, top, z);
			if (level.getPrecipitationAt(pos) == Biome.Precipitation.RAIN) {
				flags |= COL_RAINS;
			}
			if (water) {
				flags |= COL_WATER;
			}
			if (lava) {
				flags |= COL_LAVA;
			}
			light = level.getBrightness(LightLayer.BLOCK, pos) | level.getBrightness(LightLayer.SKY, pos) << 4;
		}
		m.putInt(i * 16, x).putInt(i * 16 + 4, z).putFloat(i * 16 + 8, topY).putInt(i * 16 + 12, flags | light << 8);
	}

	private GraphicsProgram[] draws(int colorFormat) {
		return this.draws.computeIfAbsent(colorFormat, format -> {
			GraphicsProgram.State state = new GraphicsProgram.State(format, VkTex.D32, true, false, VK10.VK_COMPARE_OP_GREATER,
				GraphicsProgram.Blend.PREMULTIPLIED);
			return new GraphicsProgram[]{
				new GraphicsProgram("rain.vert", "rain.frag", Map.of(), state, Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.TEX),
				new GraphicsProgram("rain_splash.vert", "rain_splash.frag", Map.of(), state, Desc.UBO, Desc.SSBO, Desc.SSBO, Desc.TEX)
			};
		});
	}

	void record(VkCommandBuffer cb, FrameTarget target, LevelRenderState levelState, ClientLevel level, long lightmap) {
		if (this.columns == null) {
			return;
		}
		long now = System.nanoTime();
		float dt = this.lastNanos == 0L ? 1.0F / 60.0F : (float) Math.min((now - this.lastNanos) / 1.0e9, 0.1);
		this.lastNanos = now;
		this.time += dt;
		int radius = Math.max(4, WaterSettings.rainRadius);
		float side = 2.0F * radius;
		int wantedCapacity = Math.max(256, (int) Math.ceil(Math.max(WaterSettings.rainDensity, 0.0F) * side * side * 2.0F));
		if (this.drops == null || wantedCapacity > this.capacity) {
			if (this.drops != null) {
				Vk.destroyLater(this.drops);
				Vk.destroyLater(this.splashes);
			}
			this.capacity = wantedCapacity;
			this.splashCapacity = wantedCapacity * 2;
			this.drops = VkBuf.device((long) this.capacity * 32L, 0);
			this.splashes = VkBuf.device((long) this.splashCapacity * 32L, 0);
		}
		float rain = level.getRainLevel(1.0F);
		float storm = level.getThunderLevel(1.0F);
		int alive = Math.min(this.capacity, (int) (Math.max(WaterSettings.rainDensity, 0.0F) * side * side * rain * (1.0F + storm)));

		CameraRenderState camera = levelState.cameraRenderState;
		Vec3 cam = camera.pos;
		float shiftX = 0.0F;
		float shiftZ = 0.0F;
		int camX = Mth.floor(cam.x);
		int camZ = Mth.floor(cam.z);
		if (!this.originSet || Math.abs(camX - this.originX) > ORIGIN_DRIFT || Math.abs(camZ - this.originZ) > ORIGIN_DRIFT) {
			if (this.originSet) {
				shiftX = this.originX - camX;
				shiftZ = this.originZ - camZ;
			}
			this.originX = camX;
			this.originZ = camZ;
			this.originSet = true;
		}

		int slot = Vk.ringSlot();
		this.readEvents(slot);
		if (this.events[slot] == null) {
			this.events[slot] = VkBuf.upload(16L + EVENT_CAPACITY * 16L, VkBuf.STORAGE);
		}
		this.events[slot].mapped().putInt(0, 0);
		this.events[slot].flush(0, 16);
		this.eventOrigin[slot] = new int[]{this.originX, this.originZ};
		this.lastSlot = slot;
		ByteBuffer b = this.ubo.mapped();
		int o = slot * 512;
		Matrix4f proj = new Matrix4f(camera.projectionMatrix);
		Matrix4f view = new Matrix4f(proj).invert().mul(WaterWorld.LEVEL_PROJECTION).mul(camera.viewRotationMatrix);
		proj.get(o, b);
		view.get(o + 64, b);
		put(b, o + 128, (float) (cam.x - this.originX), (float) cam.y, (float) (cam.z - this.originZ), (float) (this.time % 3600.0));
		put(b, o + 144, shiftX, 0.0F, shiftZ, dt);
		put(b, o + 160, WaterSettings.rainFallSpeed, radius, SPAWN_ABOVE, BELOW);
		double angle = this.time * 0.05;
		float wind = WaterSettings.rainWind * (0.25F + storm);
		put(b, o + 176, (float) Math.cos(angle) * wind, 0.0F, (float) Math.sin(angle) * wind, storm);
		put(b, o + 192, WaterSettings.rainStreakTime, WaterSettings.rainOpacity, 0.012F, 1.0F);
		put(b, o + 208, this.grid, this.originX, this.originZ, 0.0F);
		put(b, o + 224, target.width(), target.height(), 1.0F / target.width(), 1.0F / target.height());
		put(b, o + 240, WaterSettings.rainSplashDroplets, 2.2F, 0.35F, 0.5F);
		put(b, o + 256, EVENT_CAPACITY, 0.0F, 0.0F, 0.0F);
		SkyRenderState sky = levelState.skyRenderState;
		Vector3f skyColor = sky.skyColor != null ? new Vector3f(sky.skyColor) : new Vector3f(0.5F, 0.6F, 0.75F);
		put(b, o + 272, skyColor.x, skyColor.y, skyColor.z, 0.0F);
		b.putInt(o + 288, this.capacity).putInt(o + 292, this.splashCapacity).putInt(o + 296, this.seed++).putInt(o + 300, alive);
		this.ubo.flush(o, UBO_BYTES);
		Desc u = Desc.ubo(this.ubo, o, UBO_BYTES);

		Vk.barrier(cb);
		this.step.bind(cb, u, Desc.ssbo(this.drops), Desc.ssbo(this.splashes), Desc.ssbo(this.cursor), Desc.ssbo(this.columns),
			Desc.ssbo(this.events[slot]));
		this.step.dispatch(cb, ComputeProgram.groups(this.capacity, 256), 1, 1);
		Vk.barrier(cb);
		this.splashStep.bind(cb, u, Desc.ssbo(this.splashes));
		this.splashStep.dispatch(cb, ComputeProgram.groups(this.splashCapacity, 256), 1, 1);
		Vk.barrier(cb);

		GraphicsProgram[] p = this.draws(target.colorFormat());
		Desc light = Desc.tex(lightmap, this.linear.handle);
		GraphicsProgram.begin(cb, target.width(), target.height(), target.colorView(), null, target.depthView());
		if (alive > 0) {
			p[0].bind(cb, u, Desc.ssbo(this.drops), Desc.ssbo(this.columns), light);
			p[0].draw(cb, 6, alive);
		}
		p[1].bind(cb, u, Desc.ssbo(this.splashes), Desc.ssbo(this.columns), light);
		p[1].draw(cb, 6, this.splashCapacity);
		GraphicsProgram.end(cb);
	}

	private void readEvents(int slot) {
		VkBuf buf = this.events[slot];
		int[] origin = this.eventOrigin[slot];
		if (buf == null || origin == null || WaterSettings.rainLavaSteam <= 0.0F) {
			return;
		}
		buf.invalidate(0, 16);
		int n = Math.min(buf.mapped().getInt(0), EVENT_CAPACITY);
		if (n <= 0) {
			return;
		}
		buf.invalidate(16, n * 16L);
		ByteBuffer m = buf.mapped();
		it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap perBlock = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
		java.util.Map<Long, double[]> where = new java.util.HashMap<>();
		for (int i = 0; i < n; i++) {
			int at = 16 + i * 16;
			if (m.getFloat(at + 12) < 1.5F) {
				continue;
			}
			double x = m.getFloat(at) + origin[0];
			double y = m.getFloat(at + 4);
			double z = m.getFloat(at + 8) + origin[1];
			long key = BlockPos.asLong(Mth.floor(x), Mth.floor(y), Mth.floor(z));
			perBlock.addTo(key, 1);
			where.putIfAbsent(key, new double[]{x, y, z});
		}
		for (it.unimi.dsi.fastutil.longs.Long2IntMap.Entry e : perBlock.long2IntEntrySet()) {
			double[] p = where.get(e.getLongKey());
			this.steam.add(new double[]{p[0], p[1], p[2], e.getIntValue() * WaterSettings.rainLavaSteam});
		}
	}

	private static void put(ByteBuffer b, int o, float x, float y, float z, float w) {
		b.putFloat(o, x).putFloat(o + 4, y).putFloat(o + 8, z).putFloat(o + 12, w);
	}

	@Override
	public void close() {
		this.draws.values().forEach(programs -> {
			for (GraphicsProgram g : programs) {
				g.close();
			}
		});
		this.step.close();
		this.splashStep.close();
		this.linear.close();
		for (AutoCloseable c : new AutoCloseable[]{this.ubo, this.drops, this.splashes, this.cursor, this.columns, this.events[0], this.events[1],
			this.events[2]}) {
			if (c != null) {
				Vk.destroyLater(c);
			}
		}
	}
}
