package me.skaffy.client.vk;

import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

public final class Sampler implements AutoCloseable {
	public final long handle;

	public Sampler(boolean linear) {
		int filter = linear ? VK10.VK_FILTER_LINEAR : VK10.VK_FILTER_NEAREST;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
				.magFilter(filter)
				.minFilter(filter)
				.mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
				.addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
				.maxLod(0.0F);
			LongBuffer p = stack.mallocLong(1);
			Vk.check(VK10.vkCreateSampler(Vk.vk(), info, null, p), "vkCreateSampler");
			this.handle = p.get(0);
		}
	}

	@Override
	public void close() {
		VK10.vkDestroySampler(Vk.vk(), this.handle, null);
	}
}
