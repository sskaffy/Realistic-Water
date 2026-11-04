package me.skaffy.client.vk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import me.skaffy.client.mixin.FrontendGpuDeviceAccessor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;

public final class Vk {
	public static final int STAGE_ALL = 0x00010000;
	public static final int ACCESS_READ_WRITE = 0x00008000 | 0x00010000;
	public static final int FRAME_RING = 3;

	private static @Nullable VulkanDevice device;
	private static final List<VkTex> pendingTransitions = new ArrayList<>();
	private static final List<VkBuf> pendingZero = new ArrayList<>();
	private static @Nullable VkCommandBuffer recording;
	private static final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
	private static long frame;

	private record Deferred(long frame, AutoCloseable resource) {
	}

	private Vk() {
	}

	public static @Nullable VulkanDevice tryDevice() {
		if (device != null) {
			return device;
		}
		GpuDevice gpu = RenderSystem.getDevice();
		if (gpu instanceof FrontendGpuDevice frontend) {
			GpuDeviceBackend backend = ((FrontendGpuDeviceAccessor) frontend).realisticWater$getBackend();
			if (backend instanceof VulkanDevice vulkan) {
				device = vulkan;
			}
		}
		return device;
	}

	public static VulkanDevice device() {
		VulkanDevice d = tryDevice();
		if (d == null) {
			throw new IllegalStateException("Realistic Water needs the Vulkan graphics backend");
		}
		return d;
	}

	public static VkDevice vk() {
		return device().vkDevice();
	}

	public static long vma() {
		return device().vma();
	}

	public static VulkanCommandEncoder encoder() {
		return device().createCommandEncoder();
	}

	public static long frame() {
		return frame;
	}

	public static int ringSlot() {
		return (int) (frame % FRAME_RING);
	}

	public static void check(int result, String what) {
		if (result == VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY || result == VK10.VK_ERROR_OUT_OF_HOST_MEMORY) {
			throw new VkOutOfMemory(what + " failed with VkResult " + result + " (out of memory)");
		}
		if (result != VK10.VK_SUCCESS) {
			throw new IllegalStateException(what + " failed with VkResult " + result);
		}
	}

	public static void barrier(VkCommandBuffer cb) {
		Fiber fiber = FIBER.get();
		if (fiber != null) {
			fiber.park();
			return;
		}
		realBarrier(cb);
	}

	private static void realBarrier(VkCommandBuffer cb) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default();
			barrier.srcAccessMask(ACCESS_READ_WRITE);
			barrier.dstAccessMask(ACCESS_READ_WRITE);
			VK10.vkCmdPipelineBarrier(cb, STAGE_ALL, STAGE_ALL, 0, barrier, null, null);
		}
	}

	private static final ThreadLocal<Fiber> FIBER = new ThreadLocal<>();
	private static final ExecutorService RECORDERS = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "Realistic Water recorder");
		thread.setDaemon(true);
		return thread;
	});

	public static void interleave(VkCommandBuffer cb, List<Runnable> tasks) {
		if (tasks.size() < 2 || FIBER.get() != null) {
			for (Runnable task : tasks) {
				task.run();
			}
			return;
		}
		List<Fiber> fibers = new ArrayList<>(tasks.size());
		for (Runnable task : tasks) {
			fibers.add(new Fiber(task));
		}
		while (true) {
			boolean running = false;
			for (Fiber fiber : fibers) {
				if (!fiber.done) {
					fiber.resume();
				}
				running |= !fiber.done;
			}
			if (!running) {
				break;
			}
			realBarrier(cb);
		}
		for (Fiber fiber : fibers) {
			if (fiber.error != null) {
				if (fiber.error instanceof RuntimeException e) {
					throw e;
				}
				throw new IllegalStateException(fiber.error);
			}
		}
	}

	private static final class Fiber {
		private final Semaphore go = new Semaphore(0);
		private final Semaphore back = new Semaphore(0);
		private volatile boolean done;
		private volatile @Nullable Throwable error;

		Fiber(Runnable task) {
			RECORDERS.execute(() -> {
				FIBER.set(this);
				this.go.acquireUninterruptibly();
				try {
					task.run();
				} catch (Throwable t) {
					this.error = t;
				} finally {
					FIBER.remove();
					this.done = true;
					this.back.release();
				}
			});
		}

		void resume() {
			this.go.release();
			this.back.acquireUninterruptibly();
		}

		void park() {
			this.back.release();
			this.go.acquireUninterruptibly();
		}
	}

	static void queueTransition(VkTex tex) {
		pendingTransitions.add(tex);
		if (recording != null) {
			flushPending(recording);
		}
	}

	static void queueZero(VkBuf buf) {
		pendingZero.add(buf);
		if (recording != null) {
			flushPending(recording);
		}
	}

	static void forget(VkBuf buf) {
		pendingZero.remove(buf);
	}

	public static void beginRecording(VkCommandBuffer cb) {
		recording = cb;
		flushPending(cb);
	}

	public static void endRecording() {
		recording = null;
	}

	public static void flushTransitions(VkCommandBuffer cb) {
		flushPending(cb);
	}

	private static void flushPending(VkCommandBuffer cb) {
		if (!pendingZero.isEmpty()) {
			for (VkBuf buf : pendingZero) {
				buf.fill(cb, 0);
			}
			pendingZero.clear();
			barrier(cb);
		}
		if (pendingTransitions.isEmpty()) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageMemoryBarrier.Buffer barriers = VkImageMemoryBarrier.calloc(pendingTransitions.size(), stack);
			for (int i = 0; i < pendingTransitions.size(); i++) {
				VkTex tex = pendingTransitions.get(i);
				VkImageMemoryBarrier b = barriers.get(i).sType$Default();
				b.oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
				b.newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
				b.srcAccessMask(0);
				b.dstAccessMask(ACCESS_READ_WRITE);
				b.srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
				b.dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
				b.image(tex.image);
				b.subresourceRange().aspectMask(tex.aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			}
			VK10.vkCmdPipelineBarrier(cb, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, STAGE_ALL, 0, null, null, barriers);
		}
		pendingTransitions.clear();
	}

	public static void destroyLater(AutoCloseable resource) {
		deferred.add(new Deferred(frame + FRAME_RING + 1, resource));
	}

	public static void endFrame() {
		frame++;
		while (!deferred.isEmpty() && deferred.peek().frame <= frame) {
			closeQuietly(deferred.poll().resource);
		}
	}

	public static void waitIdle() {
		if (device != null) {
			VK10.vkDeviceWaitIdle(device.vkDevice());
		}
	}

	public static void flushDeferredNow() {
		waitIdle();
		while (!deferred.isEmpty()) {
			closeQuietly(deferred.poll().resource);
		}
	}

	private static void closeQuietly(AutoCloseable c) {
		try {
			c.close();
		} catch (Exception ignored) {
		}
	}
}
