package me.skaffy.client.vk;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;

public final class GameVertexBuffer extends VulkanGpuBuffer {
	private final VkBuf buffer;
	private boolean closed;

	public GameVertexBuffer(VkBuf buffer) {
		super(buffer.handle, GpuBuffer.USAGE_VERTEX, buffer.size);
		this.buffer = buffer;
	}

	public VkBuf buffer() {
		return this.buffer;
	}

	@Override
	public void destroy() {
		this.closed = true;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		this.closed = true;
	}

	@Override
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		throw new UnsupportedOperationException("Realistic Water's GPU-written vertex buffer can't be mapped");
	}
}
