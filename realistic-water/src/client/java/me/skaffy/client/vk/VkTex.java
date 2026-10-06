package me.skaffy.client.vk;

import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

public final class VkTex implements AutoCloseable {
	public static final int R32F = VK10.VK_FORMAT_R32_SFLOAT;
	public static final int R16F = VK10.VK_FORMAT_R16_SFLOAT;
	public static final int RGBA16F = VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
	public static final int RGBA8 = VK10.VK_FORMAT_R8G8B8A8_UNORM;
	public static final int D32 = VK10.VK_FORMAT_D32_SFLOAT;

	public static final int SAMPLED = VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
	public static final int STORAGE = VK10.VK_IMAGE_USAGE_STORAGE_BIT;
	public static final int COLOR = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
	public static final int DEPTH = VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
	public static final int COPY_DST = VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
	public static final int COPY_SRC = VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

	public final long image;
	public final long allocation;
	public final long view;
	public final int format;
	public final int width;
	public final int height;
	public final int aspect;
	private boolean closed;

	public VkTex(int format, int width, int height, int usage) {
		this.format = format;
		this.width = width;
		this.height = height;
		this.aspect = format == D32 ? VK10.VK_IMAGE_ASPECT_DEPTH_BIT : VK10.VK_IMAGE_ASPECT_COLOR_BIT;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default()
				.imageType(VK10.VK_IMAGE_TYPE_2D)
				.format(format)
				.mipLevels(1)
				.arrayLayers(1)
				.samples(VK10.VK_SAMPLE_COUNT_1_BIT)
				.tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
				.usage(usage)
				.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
				.initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
			info.extent().set(width, height, 1);
			VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
			LongBuffer pImage = stack.mallocLong(1);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			Vk.check(Vma.vmaCreateImage(Vk.vma(), info, alloc, pImage, pAlloc, null), "vmaCreateImage");
			this.image = pImage.get(0);
			this.allocation = pAlloc.get(0);

			VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
				.image(this.image)
				.viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
				.format(format);
			viewInfo.subresourceRange().aspectMask(this.aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			LongBuffer pView = stack.mallocLong(1);
			Vk.check(VK10.vkCreateImageView(Vk.vk(), viewInfo, null, pView), "vkCreateImageView");
			this.view = pView.get(0);
		}
		Vk.queueTransition(this);
	}

	public void clear(VkCommandBuffer cb, float r, float g, float b, float a) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkClearColorValue color = VkClearColorValue.calloc(stack);
			color.float32(0, r).float32(1, g).float32(2, b).float32(3, a);
			VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
				.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			VK10.vkCmdClearColorImage(cb, this.image, VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
		}
	}

	public static void copy(VkCommandBuffer cb, long srcImage, long dstImage, int aspect, int width, int height) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
			region.srcSubresource().aspectMask(aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.dstSubresource().aspectMask(aspect).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.extent().set(width, height, 1);
			VK10.vkCmdCopyImage(cb, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL, dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
		}
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			VK10.vkDestroyImageView(Vk.vk(), this.view, null);
			Vma.vmaDestroyImage(Vk.vma(), this.image, this.allocation);
		}
	}
}
