package me.skaffy.client.vk;

import org.lwjgl.vulkan.VK10;

public record Desc(int type, long buffer, long offset, long range, long view, long sampler) {
	public static final int UBO = VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
	public static final int SSBO = VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
	public static final int TEX = VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
	public static final int IMG = VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;

	public static Desc ubo(VkBuf buf, long offset, long range) {
		return new Desc(UBO, buf.handle, offset, range, 0L, 0L);
	}

	public static Desc ssbo(VkBuf buf) {
		return new Desc(SSBO, buf.handle, 0L, VK10.VK_WHOLE_SIZE, 0L, 0L);
	}

	public static Desc ssbo(VkBuf buf, long offset, long range) {
		return new Desc(SSBO, buf.handle, offset, range, 0L, 0L);
	}

	public static Desc tex(long view, long sampler) {
		return new Desc(TEX, 0L, 0L, 0L, view, sampler);
	}

	public static Desc tex(VkTex tex, long sampler) {
		return tex(tex.view, sampler);
	}

	public static Desc img(VkTex tex) {
		return new Desc(IMG, 0L, 0L, 0L, tex.view, 0L);
	}
}
