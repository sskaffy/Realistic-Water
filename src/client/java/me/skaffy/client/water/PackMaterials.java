package me.skaffy.client.water;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.util.List;
import me.skaffy.RealisticWater;
import me.skaffy.client.compat.ShaderCompat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;

final class PackMaterials {
	record Mesh(Material material, float[] vertices, int vertexCount, int[] quads, int quadCount) {
	}

	private static final Identifier SAND = Identifier.withDefaultNamespace("textures/block/sand.png");
	private static final Identifier SLIME = Identifier.withDefaultNamespace("textures/block/slime_block.png");
	private static final Identifier[] LEAF = {
		RealisticWater.id("textures/leaf/leaf_1.png"), RealisticWater.id("textures/leaf/leaf_2.png"),
		RealisticWater.id("textures/leaf/leaf_3.png"), RealisticWater.id("textures/leaf/leaf_4.png")
	};

	private PackMaterials() {
	}

	static boolean active() {
		return WaterSettings.packDrawMaterials && ShaderCompat.VITRAIL && ShaderCompat.packRunning();
	}

	static boolean drawnByPack(Material material) {
		return material == Material.SLIME || material == Material.LEAVES;
	}

	static Mesh mesh(Material material, ByteBuffer m, int copiedVerts, int copiedQuads, int ox, int oy, int oz, int res) {
		int verts = Math.min(m.getInt(0), copiedVerts);
		int quads = Math.min(m.getInt(4), copiedQuads);
		float[] out = new float[verts * 6];
		for (int i = 0; i < verts; i++) {
			int at = 16 + i * 16;
			int o = i * 6;
			out[o] = ox + m.getFloat(at) / res;
			out[o + 1] = oy + m.getFloat(at + 4) / res;
			out[o + 2] = oz + m.getFloat(at + 8) / res;
			unpackNormal(m.getInt(at + 12), out, o + 3);
		}
		int base = 16 + copiedVerts * 16;
		int[] q = new int[quads * 4];
		int kept = 0;
		for (int i = 0; i < quads; i++) {
			int at = base + i * 16;
			int a = m.getInt(at);
			int b = m.getInt(at + 4);
			int c = m.getInt(at + 8);
			int d = m.getInt(at + 12);
			if (Integer.compareUnsigned(a, verts) >= 0 || Integer.compareUnsigned(b, verts) >= 0 || Integer.compareUnsigned(c, verts) >= 0
				|| Integer.compareUnsigned(d, verts) >= 0) {
				continue;
			}
			if (facesAgainstNormal(out, a, b, c, d)) {
				int t = b;
				b = d;
				d = t;
			}
			q[kept * 4] = a;
			q[kept * 4 + 1] = b;
			q[kept * 4 + 2] = c;
			q[kept * 4 + 3] = d;
			kept++;
		}
		return new Mesh(material, out, verts, q, kept);
	}

	private static boolean facesAgainstNormal(float[] v, int a, int b, int c, int d) {
		int ia = a * 6;
		int ib = b * 6;
		int ic = c * 6;
		int id = d * 6;
		float e1x = v[ic] - v[ia];
		float e1y = v[ic + 1] - v[ia + 1];
		float e1z = v[ic + 2] - v[ia + 2];
		float e2x = v[id] - v[ib];
		float e2y = v[id + 1] - v[ib + 1];
		float e2z = v[id + 2] - v[ib + 2];
		float nx = e1y * e2z - e1z * e2y;
		float ny = e1z * e2x - e1x * e2z;
		float nz = e1x * e2y - e1y * e2x;
		float sx = v[ia + 3] + v[ib + 3] + v[ic + 3] + v[id + 3];
		float sy = v[ia + 4] + v[ib + 4] + v[ic + 4] + v[id + 4];
		float sz = v[ia + 5] + v[ib + 5] + v[ic + 5] + v[id + 5];
		return nx * sx + ny * sy + nz * sz < 0.0F;
	}

	private static void unpackNormal(int v, float[] out, int o) {
		float ex = (v & 0x7FFF) / 32767.0F * 2.0F - 1.0F;
		float ey = ((v >>> 15) & 0x7FFF) / 32767.0F * 2.0F - 1.0F;
		float nx = ex;
		float ny = ey;
		float nz = 1.0F - Math.abs(ex) - Math.abs(ey);
		if (nz < 0.0F) {
			nx = (1.0F - Math.abs(ey)) * (ex >= 0.0F ? 1.0F : -1.0F);
			ny = (1.0F - Math.abs(ex)) * (ey >= 0.0F ? 1.0F : -1.0F);
		}
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		out[o] = nx / len;
		out[o + 1] = ny / len;
		out[o + 2] = nz / len;
	}

	static void submit(SubmitNodeCollector collector, Vec3 camera, ClientLevel level, List<WaterRegion> regions) {
		PoseStack pose = new PoseStack();
		Long2IntOpenHashMap light = new Long2IntOpenHashMap();
		light.defaultReturnValue(-1);
		for (WaterRegion r : regions) {
			if (r.packMesh != null) {
				submitMesh(collector, pose, camera, level, r.packMesh, light);
			}
			if (r.material.isLeaves() && r.shadow instanceof ShadowCasters.Leaves leaves && leaves.allCount() > 0) {
				for (int v = 0; v < LEAF.length; v++) {
					int variant = v;
					collector.submitCustomGeometry(pose, RenderTypes.entityCutout(LEAF[v]), (p, buf) -> emitLeaves(p, buf, leaves, variant, camera, level, light));
				}
			}
		}
	}

	static void submitMesh(SubmitNodeCollector collector, PoseStack pose, Vec3 camera, ClientLevel level, Mesh mesh) {
		Long2IntOpenHashMap light = new Long2IntOpenHashMap();
		light.defaultReturnValue(-1);
		submitMesh(collector, pose, camera, level, mesh, light);
	}

	private static void submitMesh(SubmitNodeCollector collector, PoseStack pose, Vec3 camera, ClientLevel level, Mesh mesh, Long2IntOpenHashMap light) {
		if (mesh.quadCount() == 0) {
			return;
		}
		Identifier texture = mesh.material() == Material.SLIME ? SLIME : SAND;
		collector.submitCustomGeometry(pose, RenderTypes.entityCutout(texture), (p, buf) -> emitMesh(p, buf, mesh, camera, level, light));
	}

	private static int lightAt(ClientLevel level, Long2IntOpenHashMap cache, double x, double y, double z) {
		long key = BlockPos.asLong((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
		int known = cache.get(key);
		if (known != -1) {
			return known;
		}
		int coords = LightCoordsUtil.getLightCoords(level, BlockPos.of(key));
		cache.put(key, coords);
		return coords;
	}

	private static void emitMesh(PoseStack.Pose p, VertexConsumer buf, Mesh mesh, Vec3 camera, ClientLevel level, Long2IntOpenHashMap light) {
		float[] v = mesh.vertices();
		int[] q = mesh.quads();
		for (int i = 0; i < mesh.quadCount(); i++) {
			for (int k = 0; k < 4; k++) {
				int o = q[i * 4 + k] * 6;
				float x = v[o];
				float y = v[o + 1];
				float z = v[o + 2];
				float nx = v[o + 3];
				float ny = v[o + 4];
				float nz = v[o + 5];
				float ax = Math.abs(nx);
				float ay = Math.abs(ny);
				float az = Math.abs(nz);
				float u;
				float t;
				if (ay >= ax && ay >= az) {
					u = x;
					t = z;
				} else if (ax >= az) {
					u = z;
					t = -y;
				} else {
					u = x;
					t = -y;
				}
				buf.addVertex(p, (float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z)).setColor(-1)
					.setUv(u - (float) Math.floor(u), t - (float) Math.floor(t)).setOverlay(OverlayTexture.NO_OVERLAY)
					.setLight(lightAt(level, light, x + nx * 0.5, y + ny * 0.5, z + nz * 0.5)).setNormal(p, nx, ny, nz);
			}
		}
	}

	private static void emitLeaves(PoseStack.Pose p, VertexConsumer buf, ShadowCasters.Leaves leaves, int variant, Vec3 camera, ClientLevel level,
		Long2IntOpenHashMap light) {
		float[] d = leaves.all();
		for (int i = 0; i < leaves.allCount(); i++) {
			int o = i * ShadowCasters.LEAF_FLOATS;
			if ((int) d[o + 6] != variant) {
				continue;
			}
			float wx = d[o];
			float wy = d[o + 1];
			float wz = d[o + 2];
			float x = (float) (wx - camera.x);
			float y = (float) (wy - camera.y);
			float z = (float) (wz - camera.z);
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
			if (ny < 0.0F) {
				nx = -nx;
				ny = -ny;
				nz = -nz;
			}
			int lit = lightAt(level, light, wx, wy + 0.25, wz);
			leafVertex(p, buf, x - hx - sx, y - sy, z - hz - sz, 0.0F, 1.0F, lit, nx, ny, nz);
			leafVertex(p, buf, x - hx + sx, y + sy, z - hz + sz, 0.0F, 0.0F, lit, nx, ny, nz);
			leafVertex(p, buf, x + hx + sx, y + sy, z + hz + sz, 1.0F, 0.0F, lit, nx, ny, nz);
			leafVertex(p, buf, x + hx - sx, y - sy, z + hz - sz, 1.0F, 1.0F, lit, nx, ny, nz);
		}
	}

	private static void leafVertex(PoseStack.Pose p, VertexConsumer buf, float x, float y, float z, float u, float v, int light, float nx, float ny,
		float nz) {
		buf.addVertex(p, x, y, z).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(p, nx, ny, nz);
	}
}
