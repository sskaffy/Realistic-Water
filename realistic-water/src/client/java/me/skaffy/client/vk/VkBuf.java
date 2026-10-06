package me.skaffy.client.vk;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

public final class VkBuf implements AutoCloseable {
	public static final int STORAGE = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
	public static final int UNIFORM = VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
	public static final int INDIRECT = VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
	public static final int SRC = VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
	public static final int DST = VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

	public final long handle;
	public final long allocation;
	public final long size;
	private final @Nullable ByteBuffer mapped;
	private boolean closed;

	private VkBuf(long size, int usage, int memoryUsage, int flags) {
		this.size = size;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
				.size(size)
				.usage(usage)
				.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
			VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(memoryUsage).flags(flags);
			LongBuffer pBuffer = stack.mallocLong(1);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
			Vk.check(Vma.vmaCreateBuffer(Vk.vma(), info, alloc, pBuffer, pAlloc, allocInfo), "vmaCreateBuffer(" + size + ")");
			this.handle = pBuffer.get(0);
			this.allocation = pAlloc.get(0);
			long ptr = allocInfo.pMappedData();
			this.mapped = ptr != 0L ? MemoryUtil.memByteBuffer(ptr, (int) size) : null;
		}
	}

	public static VkBuf device(long size, int usage) {
		return new VkBuf(align(size), usage | STORAGE | SRC | DST, Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE, 0);
	}

	public static VkBuf upload(long size, int usage) {
		return new VkBuf(align(size), usage | SRC, Vma.VMA_MEMORY_USAGE_AUTO,
			Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
	}

	public static VkBuf readback(long size) {
		return new VkBuf(align(size), DST, Vma.VMA_MEMORY_USAGE_AUTO,
			Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT | Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
	}

	private static long align(long size) {
		return Math.max(16L, (size + 15L) & ~15L);
	}

	public ByteBuffer mapped() {
		if (this.mapped == null) {
			throw new IllegalStateException("Buffer is not host visible");
		}
		return this.mapped;
	}

	public void flush(long offset, long length) {
		Vma.vmaFlushAllocation(Vk.vma(), this.allocation, offset, length);
	}

	public void invalidate(long offset, long length) {
		Vma.vmaInvalidateAllocation(Vk.vma(), this.allocation, offset, length);
	}

	public void fill(VkCommandBuffer cb, long offset, long length, int value) {
		VK10.vkCmdFillBuffer(cb, this.handle, offset, length, value);
	}

	public void fill(VkCommandBuffer cb, int value) {
		VK10.vkCmdFillBuffer(cb, this.handle, 0L, VK10.VK_WHOLE_SIZE, value);
	}

	public void copyTo(VkCommandBuffer cb, VkBuf dst, long srcOffset, long dstOffset, long length) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(srcOffset).dstOffset(dstOffset).size(length);
			VK10.vkCmdCopyBuffer(cb, this.handle, dst.handle, region);
		}
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			Vma.vmaDestroyBuffer(Vk.vma(), this.handle, this.allocation);
		}
	}
}
