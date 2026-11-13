package me.skaffy.client.snow;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.GraphicsProgram;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.vk.VkBuf;
import me.skaffy.client.vk.VkTex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

final class SnowSplats {
	private static final int MAX = 512;
	private static final int BYTES = 64;
	private static final int GRID = 12;
	private static final long LIFETIME_TICKS = 20L * 60L * 15L;

	private record Splat(double x, double y, double z, Direction face, float radius, float thickness, float seed, BlockPos block, long born,
		int[] light, float smearX, float smearY, float smearZ) {
	}

	private final ArrayDeque<Splat> splats = new ArrayDeque<>();
	private final Map<Integer, GraphicsProgram> draws = new HashMap<>();
	private @Nullable VkBuf buffer;

	boolean isEmpty() {
		return this.splats.isEmpty();
	}

	void add(ClientLevel level, double x, double y, double z, Direction face, double vx, double vy, double vz, double surfaceShift) {
		Vec3 n = Vec3.atLowerCornerOf(face.getUnitVec3i());
		BlockPos block = BlockPos.containing(x - n.x * 0.01, y - n.y * 0.01, z - n.z * 0.01);
		double into = -(vx * n.x + vy * n.y + vz * n.z);
		double ax = vx + into * n.x;
		double ay = vy + into * n.y;
		double az = vz + into * n.z;
		double along = Math.sqrt(ax * ax + ay * ay + az * az);
		float hit = (float) Math.clamp(into, 0.0, 2.0);
		float radius = 0.12F + 0.07F * hit + level.getRandom().nextFloat() * 0.04F;
		float thickness = 0.075F - 0.015F * hit;
		float smear = (float) Math.min(0.9 * along, 1.3);
		float sx = along > 1.0e-4 ? (float) (ax / along) * smear : 0.0F;
		float sy = along > 1.0e-4 ? (float) (ay / along) * smear : 0.0F;
		float sz = along > 1.0e-4 ? (float) (az / along) * smear : 0.0F;
		int[] light = lightAt(level, BlockPos.containing(x + n.x * 0.3, y + n.y * 0.3, z + n.z * 0.3));
		this.splats.add(new Splat(x + n.x * surfaceShift, y + n.y * surfaceShift, z + n.z * surfaceShift, face, radius, thickness,
			level.getRandom().nextFloat() * 100.0F, block, level.getGameTime(), light, sx, sy, sz));
		while (this.splats.size() > MAX) {
			this.splats.poll();
		}
	}

	private static int[] lightAt(ClientLevel level, BlockPos pos) {
		return new int[]{level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos)};
	}

	void tick(ClientLevel level) {
		if (this.splats.isEmpty()) {
			return;
		}
		long now = level.getGameTime();
		boolean relight = now % 40L == 0L;
		this.splats.removeIf(s -> {
			if (now - s.born > LIFETIME_TICKS || level.hasChunkAt(s.block) && level.getBlockState(s.block).isAir()) {
				return true;
			}
			Vec3 n = Vec3.atLowerCornerOf(s.face.getUnitVec3i());
			BlockPos front = BlockPos.containing(s.x + n.x * 0.3, s.y + n.y * 0.3, s.z + n.z * 0.3);
			if (relight) {
				int[] l = lightAt(level, front);
				s.light[0] = l[0];
				s.light[1] = l[1];
				if (l[0] >= 12 && level.getRandom().nextInt(4) == 0) {
					return true;
				}
			}
			return false;
		});
	}

	void clear() {
		this.splats.clear();
	}

	private GraphicsProgram draw(int colorFormat) {
		return this.draws.computeIfAbsent(colorFormat, format -> new GraphicsProgram("snow_splat.vert", "snow_splat.frag", Map.of(),
			new GraphicsProgram.State(format, VkTex.D32, true, true, VK10.VK_COMPARE_OP_GREATER, GraphicsProgram.Blend.NONE), Desc.UBO, Desc.SSBO,
			Desc.SSBO, Desc.SSBO, Desc.SSBO, Desc.TEX, Desc.TEX));
	}

	void record(VkCommandBuffer cb, Desc camera, Desc params, Desc columns, Desc detail, Desc light, Desc shadow, int width, int height,
		int colorFormat, long colorView, long depthView) {
		if (this.splats.isEmpty()) {
			return;
		}
		if (this.buffer == null) {
			this.buffer = VkBuf.upload((long) MAX * BYTES * Vk.FRAME_RING, VkBuf.STORAGE);
		}
		Vec3 cam = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
		int bx = Mth.floor(cam.x);
		int by = Mth.floor(cam.y);
		int bz = Mth.floor(cam.z);
		long base = (long) Vk.ringSlot() * MAX * BYTES;
		ByteBuffer m = this.buffer.mapped();
		long now = Minecraft.getInstance().level != null ? Minecraft.getInstance().level.getGameTime() : 0L;
		int count = 0;
		for (Splat s : this.splats) {
			if (count >= MAX) {
				break;
			}
			int o = (int) (base + (long) count * BYTES);
			Vec3 n = Vec3.atLowerCornerOf(s.face.getUnitVec3i());
			float age = (float) Math.min((now - s.born) / (double) LIFETIME_TICKS, 1.0);
			float thickness = s.thickness * (1.0F - Math.max(age - 0.8F, 0.0F) * 5.0F);
			m.putFloat(o, (float) (s.x - bx)).putFloat(o + 4, (float) (s.y - by)).putFloat(o + 8, (float) (s.z - bz)).putFloat(o + 12, s.radius);
			m.putFloat(o + 16, (float) n.x).putFloat(o + 20, (float) n.y).putFloat(o + 24, (float) n.z).putFloat(o + 28, s.seed);
			m.putFloat(o + 32, s.light[0] / 15.0F).putFloat(o + 36, s.light[1] / 15.0F).putFloat(o + 40, 0.0F).putFloat(o + 44, Math.max(thickness, 0.005F));
			m.putFloat(o + 48, s.smearX).putFloat(o + 52, s.smearY).putFloat(o + 56, s.smearZ).putFloat(o + 60, 0.0F);
			count++;
		}
		this.buffer.flush(base, (long) count * BYTES);
		GraphicsProgram p = this.draw(colorFormat);
		GraphicsProgram.begin(cb, width, height, colorView, null, depthView);
		p.bind(cb, camera, params, columns, detail, Desc.ssbo(this.buffer, base, (long) MAX * BYTES), light, shadow);
		p.draw(cb, GRID * GRID * 6, count);
		GraphicsProgram.end(cb);
	}

	void shutdown() {
		this.draws.values().forEach(GraphicsProgram::close);
		this.draws.clear();
		if (this.buffer != null) {
			Vk.destroyLater(this.buffer);
			this.buffer = null;
		}
	}
}
