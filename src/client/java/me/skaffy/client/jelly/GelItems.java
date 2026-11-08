package me.skaffy.client.jelly;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.RealisticWater;
import me.skaffy.client.compat.FrameTarget;
import me.skaffy.client.compat.ShaderCompat;
import me.skaffy.client.vk.Desc;
import me.skaffy.client.vk.Vk;
import me.skaffy.client.water.WaterWorld;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class GelItems {
	private enum Phase { NONE, WORLD, HAND }

	private record Capture(JellyKind kind, Matrix4f pose, Matrix3f normal, int light) {
	}

	private static final int MAX = 512;
	private static Phase phase = Phase.NONE;
	private static final List<Capture> world = new ArrayList<>();
	private static final List<Capture> hand = new ArrayList<>();
	private static final JellyMesher handMesher = new JellyMesher();
	private static @Nullable JellyRenderer handRenderer;
	private static boolean failed;
	private static boolean failedSaid;

	private GelItems() {
	}

	public static boolean drawnAsGel(ItemDisplayContext context) {
		return !failed && JellyWorld.on() && context != ItemDisplayContext.GUI && context != ItemDisplayContext.NONE && Vk.tryDevice() != null
			&& !WaterWorld.get().drawingFailed();
	}

	public static void beginWorld() {
		world.clear();
		phase = Phase.WORLD;
	}

	public static void endWorld() {
		phase = Phase.NONE;
	}

	public static void beginHand() {
		hand.clear();
		phase = Phase.HAND;
	}

	static void capture(JellyKind kind, PoseStack poseStack, int light) {
		List<Capture> to = switch (phase) {
			case WORLD -> world;
			case HAND -> hand;
			case NONE -> null;
		};
		if (to == null || to.size() >= MAX) {
			return;
		}
		PoseStack.Pose last = poseStack.last();
		to.add(new Capture(kind, new Matrix4f(last.pose()), new Matrix3f(last.normal()), light));
	}

	static boolean hasWorld() {
		return !world.isEmpty();
	}

	static void appendWorld(JellyMesher mesher, Vec3 cam, int bx, int by, int bz) {
		append(mesher, world, (float) (cam.x - bx), (float) (cam.y - by), (float) (cam.z - bz));
	}

	private static void append(JellyMesher mesher, List<Capture> list, float offX, float offY, float offZ) {
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		for (Capture c : list) {
			int first = mesher.vertexCount;
			int light = ((c.light >> 4) & 15) * 16 + ((c.light >> 20) & 15);
			mesher.block(BlockPos.ZERO, c.kind, 63, false, true, 0.0F, 0.0F, 0.0F, light, 0.0F, null);
			float[] v = mesher.vertices;
			for (int k = first; k < mesher.vertexCount; k++) {
				int o = k * JellyMesher.FLOATS;
				c.pose.transformPosition(p.set(v[o], v[o + 1], v[o + 2]));
				c.normal.transform(n.set(v[o + 3], v[o + 4], v[o + 5]));
				if (n.lengthSquared() > 1.0e-12F) {
					n.normalize();
				}
				v[o] = p.x + offX;
				v[o + 1] = p.y + offY;
				v[o + 2] = p.z + offZ;
				v[o + 3] = n.x;
				v[o + 4] = n.y;
				v[o + 5] = n.z;
			}
		}
	}

	public static void drawHand(Matrix4f handProjection, Matrix4f viewRotation, GpuTextureView depth) {
		phase = Phase.NONE;
		if (hand.isEmpty() || failed || !JellyWorld.on()) {
			hand.clear();
			return;
		}
		ShaderCompat.runOutsidePasses(() -> {
			Minecraft mc = Minecraft.getInstance();
			FrameTarget target = FrameTarget.of(mc.gameRenderer.mainRenderTarget().getColorTextureView(), depth, false);
			if (target == null) {
				return;
			}
			try {
				Desc ubo = WaterWorld.get().writeHandGlobals(handProjection, viewRotation, target.width(), target.height());
				if (ubo == null) {
					return;
				}
				handMesher.reset();
				append(handMesher, hand, 0.0F, 0.0F, 0.0F);
				if (handMesher.indexCount == 0) {
					return;
				}
				long lightmap = mc.gameRenderer.lightmap() instanceof VulkanGpuTextureView view ? view.vkImageView() : target.depthView();
				if (handRenderer == null) {
					handRenderer = new JellyRenderer();
				}
				VkCommandBuffer cb = Vk.encoder().allocateAndBeginTransientCommandBuffer();
				Vk.beginRecording(cb);
				try {
					Vk.barrier(cb);
					handRenderer.record(cb, ubo, handMesher, target.width(), target.height(), target.colorFormat(), target.colorImage(), target.colorView(),
						target.depthImage(), target.depthView(), lightmap);
					Vk.barrier(cb);
				} finally {
					Vk.endRecording();
				}
				Vk.check(VK10.vkEndCommandBuffer(cb), "vkEndCommandBuffer");
				Vk.encoder().execute(cb);
			} catch (RuntimeException e) {
				failed = true;
				if (!failedSaid) {
					failedSaid = true;
					RealisticWater.LOGGER.error("Held gel items couldn't be drawn; showing their block model", e);
				}
			}
		});
		hand.clear();
	}

	public static void shutdown() {
		if (handRenderer != null) {
			handRenderer.close();
			handRenderer = null;
		}
		world.clear();
		hand.clear();
	}
}
