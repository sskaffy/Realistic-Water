package me.skaffy.client.water;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.List;
import me.skaffy.RealisticWater;
import me.skaffy.client.compat.VitrailSea;
import me.skaffy.ocean.OceanWaves;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class PackFoam {
	private static final Identifier LACE = RealisticWater.id("textures/foam/lace.png");
	private static final Identifier FLECK = RealisticWater.id("textures/foam/fleck.png");
	private static final float WATER_TOP = 8.0F / 9.0F;
	private static final float LATENCY = 0.035F;
	private static final int LACE_TILE = 4;

	private PackFoam() {
	}

	static boolean active() {
		return OceanMode.active() && OceanMode.packDrawsSea();
	}

	static void submit(SubmitNodeCollector collector, Vec3 camera, ClientLevel level, List<WaterRegion> regions, OceanSurfaces surfaces) {
		if (!active()) {
			return;
		}
		PoseStack pose = new PoseStack();
		Long2IntOpenHashMap light = new Long2IntOpenHashMap();
		light.defaultReturnValue(-1);
		if (WaterSettings.oceanWhitecaps > 0.0F && VitrailSea.live()) {
			collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(LACE), (p, buf) -> emitWhitecaps(p, buf, camera, level, surfaces, light));
		}
		boolean any = false;
		for (WaterRegion r : regions) {
			any |= r.ocean != null && r.packFoam != null && r.packFoamCount > 0;
		}
		if (any && WaterSettings.oceanWhitewater) {
			Quaternionf cam = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.orientation;
			collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(FLECK), (p, buf) -> {
				for (WaterRegion r : regions) {
					if (r.ocean != null && r.packFoam != null) {
						emitFlecks(p, buf, r, camera, cam, level, surfaces, light);
					}
				}
			});
		}
	}

	private static void emitWhitecaps(PoseStack.Pose p, VertexConsumer buf, Vec3 camera, ClientLevel level, OceanSurfaces surfaces,
		Long2IntOpenHashMap light) {
		int[] cb = VitrailSea.camBlock();
		int radius = Math.max(8, WaterSettings.oceanWhitecapRadius);
		int size = radius * 2 + 2;
		float[] cap = new float[size * size];
		float[] lift = new float[size * size];
		java.util.Arrays.fill(cap, Float.NaN);
		float[] tmp = new float[3];
		float time = (float) ((level.getGameTime() % 24000L) / 20.0);
		float start = Math.max(WaterSettings.oceanWhitecapStart, 0.05F);
		float sumA = 0.0F;
		for (int i = 0; i < OceanWaves.GEOMETRIC; i++) {
			sumA += VitrailSea.wave(i, 2);
		}
		if (sumA * OceanWaves.AMP_MAX <= start) {
			return;
		}
		float ox = (float) (cb[0] - camera.x);
		float oy = (float) (cb[1] - camera.y);
		float oz = (float) (cb[2] - camera.z);
		for (int dz = -radius; dz < radius; dz++) {
			for (int dx = -radius; dx < radius; dx++) {
				if (dx * dx + dz * dz > radius * radius) {
					continue;
				}
				int top = surfaces.restTop(cb[0] + dx, cb[2] + dz);
				if (!OceanSurfaces.hasTop(top)) {
					continue;
				}
				int y = top - cb[1];
				float max = 0.0F;
				float[] c = new float[4];
				float[] h = new float[4];
				for (int k = 0; k < 4; k++) {
					int cx = dx + (k == 1 || k == 2 ? 1 : 0);
					int cz = dz + (k >= 2 ? 1 : 0);
					int at = (cx + radius) + (cz + radius) * size;
					if (Float.isNaN(cap[at])) {
						VitrailSea.corner(cx, cz, y, tmp);
						cap[at] = whitecap(cx, cz, cb[0] + cx, cb[2] + cz, tmp[0], sumA, start, time);
						lift[at] = tmp[1];
					}
					c[k] = cap[at];
					h[k] = lift[at];
					max = Math.max(max, c[k]);
				}
				if (max < 0.03F) {
					continue;
				}
				int l = lightAt(level, light, cb[0] + dx, top + 1, cb[2] + dz);
				float base = y + WATER_TOP + 0.04F + oy;
				float u0 = Math.floorMod(cb[0] + dx, LACE_TILE) / (float) LACE_TILE;
				float v0 = Math.floorMod(cb[2] + dz, LACE_TILE) / (float) LACE_TILE;
				float step = 1.0F / LACE_TILE;
				float x0 = dx + ox;
				float z0 = dz + oz;
				vertex(buf, p, x0, base + h[0], z0, u0, v0, c[0], l);
				vertex(buf, p, x0, base + h[3], z0 + 1, u0, v0 + step, c[3], l);
				vertex(buf, p, x0 + 1, base + h[2], z0 + 1, u0 + step, v0 + step, c[2], l);
				vertex(buf, p, x0 + 1, base + h[1], z0, u0 + step, v0, c[1], l);
			}
		}
	}

	private static float whitecap(int cx, int cz, int wx, int wz, float amp, float sumA, float start, float time) {
		if (amp <= 0.0F) {
			return 0.0F;
		}
		float height = amp * sumA;
		float big = smoothstep(start, start * 2.5F, height);
		if (big <= 0.0F) {
			return 0.0F;
		}
		float h = 0.0F;
		float trail = 0.0F;
		for (int i = 0; i < OceanWaves.GEOMETRIC; i++) {
			float a = VitrailSea.wave(i, 2);
			float phase = VitrailSea.wave(i, 4) + VitrailSea.wave(i, 0) * cx + VitrailSea.wave(i, 1) * cz;
			h += a * (float) Math.sin(phase);
			trail += a * (float) Math.sin(phase + 0.9F);
		}
		h /= sumA;
		trail /= sumA;
		float reach = 0.7F + (0.2F - 0.7F) * big;
		float crest = smoothstep(reach, reach + 0.25F, h);
		float back = 0.7F * smoothstep(reach + 0.1F, reach + 0.4F, trail);
		float n = noise(wx * 0.45F + time * 0.11F, wz * 0.45F + time * 0.07F) * 0.6F + noise(wx * 1.3F - time * 0.05F, wz * 1.3F - time * 0.13F) * 0.4F;
		float patches = smoothstep(0.25F - 0.2F * big, 0.75F, n);
		return Math.clamp(Math.max(crest, back) * patches * big * WaterSettings.oceanWhitecaps, 0.0F, 1.0F);
	}

	private static void emitFlecks(PoseStack.Pose p, VertexConsumer buf, WaterRegion r, Vec3 camera, Quaternionf cam, ClientLevel level, OceanSurfaces surfaces,
		Long2IntOpenHashMap light) {
		float[] d = r.packFoam;
		int n = Math.min(r.packFoamCount, d.length / 8);
		Vector3f left = cam.transform(new Vector3f(-1.0F, 0.0F, 0.0F));
		Vector3f up = cam.transform(new Vector3f(0.0F, 1.0F, 0.0F));
		OceanWaves.Waves waves = WaterWorld.get().calmWaves(level, 0.0F);
		float size = Math.max(WaterSettings.packFoamSize, 0.01F);
		for (int i = 0; i < n; i++) {
			int o = i * 8;
			float kind = d[o + 6];
			if (kind > 2.5F) {
				continue;
			}
			double x = d[o] + d[o + 3] * LATENCY;
			double y = d[o + 1] + d[o + 4] * LATENCY;
			double z = d[o + 2] + d[o + 5] * LATENCY;
			int bx = (int) Math.floor(x);
			int bz = (int) Math.floor(z);
			y += surfaces.amplitude(bx, bz) * waves.height(x, z);
			float alpha;
			float s;
			if (kind > 0.5F && kind < 1.5F) {
				alpha = Math.clamp(d[o + 7] * 0.8F, 0.0F, 1.0F) * 0.9F;
				s = size;
			} else {
				alpha = 0.7F;
				s = size * 0.5F;
			}
			if (alpha < 0.02F) {
				continue;
			}
			int l = lightAt(level, light, bx, (int) Math.floor(y + 0.5), bz);
			float rx = (float) (x - camera.x);
			float ry = (float) (y - camera.y);
			float rz = (float) (z - camera.z);
			if (kind > 0.5F && kind < 1.5F) {
				ry += 0.03F;
				vertex(buf, p, rx - s, ry, rz - s, 0.0F, 0.0F, alpha, l);
				vertex(buf, p, rx - s, ry, rz + s, 0.0F, 1.0F, alpha, l);
				vertex(buf, p, rx + s, ry, rz + s, 1.0F, 1.0F, alpha, l);
				vertex(buf, p, rx + s, ry, rz - s, 1.0F, 0.0F, alpha, l);
			} else {
				float lx = left.x * s;
				float ly = left.y * s;
				float lz = left.z * s;
				float ux = up.x * s;
				float uy = up.y * s;
				float uz = up.z * s;
				vertex(buf, p, rx + lx - ux, ry + ly - uy, rz + lz - uz, 0.0F, 1.0F, alpha, l);
				vertex(buf, p, rx - lx - ux, ry - ly - uy, rz - lz - uz, 1.0F, 1.0F, alpha, l);
				vertex(buf, p, rx - lx + ux, ry - ly + uy, rz - lz + uz, 1.0F, 0.0F, alpha, l);
				vertex(buf, p, rx + lx + ux, ry + ly + uy, rz + lz + uz, 0.0F, 0.0F, alpha, l);
			}
		}
	}

	private static void vertex(VertexConsumer buf, PoseStack.Pose p, float x, float y, float z, float u, float v, float alpha, int light) {
		int a = Math.clamp(Math.round(alpha * 255.0F), 0, 255);
		buf.addVertex(p, x, y, z).setColor(a << 24 | 0xFFFFFF).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
			.setNormal(p, 0.0F, 1.0F, 0.0F);
	}

	private static int lightAt(ClientLevel level, Long2IntOpenHashMap cache, int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		int known = cache.get(key);
		if (known != -1) {
			return known;
		}
		int coords = LightCoordsUtil.getLightCoords(level, BlockPos.of(key));
		cache.put(key, coords);
		return coords;
	}

	private static float smoothstep(float a, float b, float x) {
		float t = Math.clamp((x - a) / (b - a), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	private static float noise(float x, float z) {
		int ix = (int) Math.floor(x);
		int iz = (int) Math.floor(z);
		float fx = x - ix;
		float fz = z - iz;
		fx = fx * fx * (3.0F - 2.0F * fx);
		fz = fz * fz * (3.0F - 2.0F * fz);
		float a = corner(ix, iz);
		float b = corner(ix + 1, iz);
		float c = corner(ix, iz + 1);
		float e = corner(ix + 1, iz + 1);
		return (a + (b - a) * fx) + ((c + (e - c) * fx) - (a + (b - a) * fx)) * fz;
	}

	private static float corner(int x, int z) {
		int h = x * 73856093 ^ z * 19349663;
		h ^= h >>> 16;
		h *= 0x7feb352d;
		h ^= h >>> 15;
		h *= 0x846ca68b;
		h ^= h >>> 16;
		return (h >>> 8) * (1.0F / 16777216.0F);
	}
}
