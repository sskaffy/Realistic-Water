package me.skaffy.client.water;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import net.minecraft.client.multiplayer.ClientLevel;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.List;
import me.skaffy.RealisticWater;
import me.skaffy.client.compat.ShaderCompat;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

final class ShadowCasters {
	sealed interface Casters permits Boxes, Leaves {
	}

	record Boxes(Material material, float[] boxes, int count) implements Casters {
	}

	record Leaves(float[] data, int count, Boxes piles, float[] all, int allCount) implements Casters {
	}

	private static final int OCCUPIED = 102;
	private static final float RESTING_SPEED = 0.05F;
	static final int LEAF_FLOATS = 7;
	private static final float TAU = (float) (Math.PI * 2.0);
	private static final Identifier SAND = Identifier.withDefaultNamespace("textures/block/sand.png");
	private static final Identifier SLIME = Identifier.withDefaultNamespace("textures/block/slime_block.png");
	private static final Identifier[] LEAF = {
		RealisticWater.id("textures/leaf/leaf_1.png"), RealisticWater.id("textures/leaf/leaf_2.png"),
		RealisticWater.id("textures/leaf/leaf_3.png"), RealisticWater.id("textures/leaf/leaf_4.png")
	};

	private ShadowCasters() {
	}

	static boolean wanted() {
		return WaterSettings.packShadows && ShaderCompat.VITRAIL && ShaderCompat.packRunning();
	}

	static boolean casts(Material material) {
		return material == Material.SAND || material == Material.SLIME || material == Material.LEAVES;
	}

	static Casters leaves(ByteBuffer m, int count, int ox, int oy, int oz, int res) {
		float[] data = new float[count * LEAF_FLOATS];
		float[] all = new float[count * LEAF_FLOATS];
		int kept = 0;
		int every = 0;
		float size = WaterSettings.leafSize;
		int d = Math.max(1, WaterSettings.packShadowDetail);
		Long2IntOpenHashMap resting = new Long2IntOpenHashMap();
		for (int i = 0; i < count; i++) {
			int at = 16 + i * WaterRegion.PARTICLE_BYTES;
			if (m.getFloat(at + 12) < 0.0F) {
				continue;
			}
			float vx = m.getFloat(at + 16);
			float vy = m.getFloat(at + 20);
			float vz = m.getFloat(at + 24);
			int bits = (int) m.getFloat(at + 28);
			float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz) / res;
			float moving = smoothstep(0.05F, 0.6F, speed);
			float phase = ((bits >> 8) & 1023) / 1024.0F * TAU;
			float burn = ((bits >> 18) & 63) / 63.0F;
			int a = every * LEAF_FLOATS;
			all[a] = ox + m.getFloat(at) / res;
			all[a + 1] = oy + m.getFloat(at + 4) / res + 0.012F;
			all[a + 2] = oz + m.getFloat(at + 8) / res;
			all[a + 3] = ((bits >> 2) & 63) / 64.0F * TAU;
			all[a + 4] = speed > 3.0F ? phase : moving * 0.75F * (float) Math.sin(phase);
			all[a + 5] = size * (1.0F - 0.6F * burn);
			all[a + 6] = bits & 3;
			every++;
			if (speed < RESTING_SPEED || !WaterSettings.packShadowPerLeaf) {
				resting.addTo(BlockPos.asLong((int) Math.floor(all[a] * d), (int) Math.floor(all[a + 1] * d), (int) Math.floor(all[a + 2] * d)), 1);
				continue;
			}
			System.arraycopy(all, a, data, kept * LEAF_FLOATS, LEAF_FLOATS);
			kept++;
		}
		return new Leaves(data, kept, piles(resting, d), all, every);
	}

	private static Boxes piles(Long2IntOpenHashMap counts, int d) {
		if (counts.isEmpty()) {
			return new Boxes(Material.LEAVES, new float[0], 0);
		}
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		for (long key : counts.keySet()) {
			minX = Math.min(minX, BlockPos.getX(key));
			minY = Math.min(minY, BlockPos.getY(key));
			minZ = Math.min(minZ, BlockPos.getZ(key));
			maxX = Math.max(maxX, BlockPos.getX(key));
			maxY = Math.max(maxY, BlockPos.getY(key));
			maxZ = Math.max(maxZ, BlockPos.getZ(key));
		}
		int nx = maxX - minX + 1;
		int ny = maxY - minY + 1;
		int nz = maxZ - minZ + 1;
		float full = Math.max(1.0F, 0.5F * WaterSettings.leavesPerBlock / ((float) d * d * d));
		boolean[] open = new boolean[nx * ny * nz];
		for (Long2IntMap.Entry e : counts.long2IntEntrySet()) {
			long key = e.getLongKey();
			if (e.getIntValue() >= full) {
				open[((BlockPos.getZ(key) - minZ) * ny + BlockPos.getY(key) - minY) * nx + BlockPos.getX(key) - minX] = true;
			}
		}
		float step = 1.0F / d;
		return merge(Material.LEAVES, open, nx, ny, nz, minX * step, minY * step, minZ * step, step);
	}

	static Casters pieces(Material material, ByteBuffer m, int ox, int oy, int oz, int d, int px0, int py0, int pz0, int nx, int ny, int nz, int words) {
		boolean[] open = new boolean[nx * ny * nz];
		for (int z = 0; z < nz; z++) {
			for (int y = 0; y < ny; y++) {
				for (int x = 0; x < nx; x++) {
					int word = m.getInt(((z * ny + y) * words + (x >> 2)) * 4);
					open[(z * ny + y) * nx + x] = ((word >>> ((x & 3) * 8)) & 0xFF) >= OCCUPIED;
				}
			}
		}
		float step = 1.0F / d;
		return merge(material, open, nx, ny, nz, ox + px0 * step, oy + py0 * step, oz + pz0 * step, step);
	}

	private static Boxes merge(Material material, boolean[] open, int nx, int ny, int nz, float x0, float y0, float z0, float step) {
		float[] boxes = new float[64 * 6];
		int count = 0;
		for (int z = 0; z < nz; z++) {
			for (int y = 0; y < ny; y++) {
				for (int x = 0; x < nx; x++) {
					if (!open[(z * ny + y) * nx + x]) {
						continue;
					}
					int x1 = x + 1;
					while (x1 < nx && open[(z * ny + y) * nx + x1]) {
						x1++;
					}
					int y1 = y + 1;
					while (y1 < ny && run(open, nx, ny, x, x1, y1, y1 + 1, z, z + 1)) {
						y1++;
					}
					int z1 = z + 1;
					while (z1 < nz && run(open, nx, ny, x, x1, y, y1, z1, z1 + 1)) {
						z1++;
					}
					for (int cz = z; cz < z1; cz++) {
						for (int cy = y; cy < y1; cy++) {
							for (int cx = x; cx < x1; cx++) {
								open[(cz * ny + cy) * nx + cx] = false;
							}
						}
					}
					if ((count + 1) * 6 > boxes.length) {
						boxes = java.util.Arrays.copyOf(boxes, boxes.length * 2);
					}
					int o = count * 6;
					boxes[o] = x0 + x * step;
					boxes[o + 1] = y0 + y * step;
					boxes[o + 2] = z0 + z * step;
					boxes[o + 3] = x0 + x1 * step;
					boxes[o + 4] = y0 + y1 * step;
					boxes[o + 5] = z0 + z1 * step;
					count++;
				}
			}
		}
		return new Boxes(material, boxes, count);
	}

	private static boolean run(boolean[] open, int nx, int ny, int x0, int x1, int y0, int y1, int z0, int z1) {
		for (int z = z0; z < z1; z++) {
			for (int y = y0; y < y1; y++) {
				for (int x = x0; x < x1; x++) {
					if (!open[(z * ny + y) * nx + x]) {
						return false;
					}
				}
			}
		}
		return true;
	}

	static void submit(SubmitNodeCollector collector, Vec3 camera, ClientLevel level, List<WaterRegion> regions) {
		PoseStack pose = new PoseStack();
		boolean packDraws = PackMaterials.active();
		for (WaterRegion r : regions) {
			if (packDraws && r.packMesh != null && !r.material.isLeaves()) {
				PackMaterials.submitMesh(collector, pose, camera, level, r.packMesh);
				continue;
			}
			Casters casters = r.shadow;
			if (casters instanceof Boxes boxes && boxes.count() > 0) {
				RenderType type = RenderTypes.entitySolid(boxes.material() == Material.SLIME ? SLIME : SAND);
				collector.submitCustomGeometry(pose, type, (p, buf) -> emitBoxes(p, buf, boxes, camera));
			} else if (casters instanceof Leaves leaves) {
				if (leaves.piles().count() > 0) {
					collector.submitCustomGeometry(pose, RenderTypes.entitySolid(SAND), (p, buf) -> emitBoxes(p, buf, leaves.piles(), camera));
				}
				if (leaves.count() == 0) {
					continue;
				}
				for (int v = 0; v < LEAF.length; v++) {
					int variant = v;
					collector.submitCustomGeometry(pose, RenderTypes.entityCutout(LEAF[v]), (p, buf) -> emitLeaves(p, buf, leaves, variant, camera));
				}
			}
		}
	}

	private static void emitBoxes(PoseStack.Pose p, VertexConsumer buf, Boxes boxes, Vec3 camera) {
		float[] b = boxes.boxes();
		float cx = (float) camera.x;
		float cy = (float) camera.y;
		float cz = (float) camera.z;
		for (int i = 0; i < boxes.count(); i++) {
			int o = i * 6;
			float x0 = b[o] - cx;
			float y0 = b[o + 1] - cy;
			float z0 = b[o + 2] - cz;
			float x1 = b[o + 3] - cx;
			float y1 = b[o + 4] - cy;
			float z1 = b[o + 5] - cz;
			quad(p, buf, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0);
			quad(p, buf, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0, -1, 0);
			quad(p, buf, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1, 0, 0);
			quad(p, buf, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0);
			quad(p, buf, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1);
			quad(p, buf, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, 0, 0, -1);
		}
	}

	private static void emitLeaves(PoseStack.Pose p, VertexConsumer buf, Leaves leaves, int variant, Vec3 camera) {
		float[] d = leaves.data();
		for (int i = 0; i < leaves.count(); i++) {
			int o = i * LEAF_FLOATS;
			if ((int) d[o + 6] != variant) {
				continue;
			}
			float x = (float) (d[o] - camera.x);
			float y = (float) (d[o + 1] - camera.y);
			float z = (float) (d[o + 2] - camera.z);
			float yaw = d[o + 3];
			float tilt = d[o + 4];
			float half = d[o + 5] * 0.5F;
			float hx = (float) Math.cos(yaw) * half;
			float hz = (float) Math.sin(yaw) * half;
			float c = (float) Math.cos(tilt);
			float s = (float) Math.sin(tilt);
			float sx = -hz * c;
			float sy = half * s;
			float sz = hx * c;
			float nx = sy * hz;
			float ny = sz * hx - sx * hz;
			float nz = -sy * hx;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len > 1.0E-6F) {
				nx /= len;
				ny /= len;
				nz /= len;
			}
			vertex(p, buf, x - hx - sx, y - sy, z - hz - sz, 0.0F, 0.0F, nx, ny, nz);
			vertex(p, buf, x - hx + sx, y + sy, z - hz + sz, 0.0F, 1.0F, nx, ny, nz);
			vertex(p, buf, x + hx + sx, y + sy, z + hz + sz, 1.0F, 1.0F, nx, ny, nz);
			vertex(p, buf, x + hx - sx, y - sy, z + hz - sz, 1.0F, 0.0F, nx, ny, nz);
			vertex(p, buf, x + hx - sx, y - sy, z + hz - sz, 1.0F, 0.0F, -nx, -ny, -nz);
			vertex(p, buf, x + hx + sx, y + sy, z + hz + sz, 1.0F, 1.0F, -nx, -ny, -nz);
			vertex(p, buf, x - hx + sx, y + sy, z - hz + sz, 0.0F, 1.0F, -nx, -ny, -nz);
			vertex(p, buf, x - hx - sx, y - sy, z - hz - sz, 0.0F, 0.0F, -nx, -ny, -nz);
		}
	}

	private static void quad(PoseStack.Pose p, VertexConsumer buf, float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
		float dx, float dy, float dz, float nx, float ny, float nz) {
		vertex(p, buf, ax, ay, az, 0.0F, 0.0F, nx, ny, nz);
		vertex(p, buf, bx, by, bz, 0.0F, 1.0F, nx, ny, nz);
		vertex(p, buf, cx, cy, cz, 1.0F, 1.0F, nx, ny, nz);
		vertex(p, buf, dx, dy, dz, 1.0F, 0.0F, nx, ny, nz);
	}

	private static void vertex(PoseStack.Pose p, VertexConsumer buf, float x, float y, float z, float u, float v, float nx, float ny, float nz) {
		buf.addVertex(p, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
			.setNormal(p, nx, ny, nz);
	}

	private static float smoothstep(float a, float b, float x) {
		float t = Math.clamp((x - a) / (b - a), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}
}
