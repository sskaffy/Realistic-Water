package me.skaffy.client.vk;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

public abstract class Program implements AutoCloseable {
	public static final int PUSH_SIZE = 64;
	private static final int STAGES = VK10.VK_SHADER_STAGE_ALL;

	protected final String name;
	protected final int[] types;
	protected final int bindPoint;
	protected final long setLayout;
	protected final long layout;
	protected long pipeline;

	protected Program(String name, int bindPoint, int... types) {
		this.name = name;
		this.types = types;
		this.bindPoint = bindPoint;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
			for (int i = 0; i < types.length; i++) {
				bindings.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(STAGES);
			}
			VkDescriptorSetLayoutCreateInfo setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
				.flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
				.pBindings(bindings);
			LongBuffer p = stack.mallocLong(1);
			Vk.check(VK10.vkCreateDescriptorSetLayout(Vk.vk(), setInfo, null, p), "descriptor set layout " + name);
			this.setLayout = p.get(0);

			VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack).stageFlags(STAGES).offset(0).size(PUSH_SIZE);
			VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
				.pSetLayouts(stack.longs(this.setLayout))
				.pPushConstantRanges(range);
			Vk.check(VK10.vkCreatePipelineLayout(Vk.vk(), layoutInfo, null, p), "pipeline layout " + name);
			this.layout = p.get(0);
		}
	}

	public void bind(VkCommandBuffer cb, Desc... descs) {
		if (descs.length != this.types.length) {
			throw new IllegalArgumentException(this.name + " expects " + this.types.length + " bindings, got " + descs.length);
		}
		VK10.vkCmdBindPipeline(cb, this.bindPoint, this.pipeline);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(descs.length, stack);
			for (int i = 0; i < descs.length; i++) {
				Desc d = descs[i];
				if (d.type() != this.types[i]) {
					throw new IllegalArgumentException(this.name + " binding " + i + " has wrong descriptor type");
				}
				VkWriteDescriptorSet w = writes.get(i).sType$Default().dstBinding(i).descriptorCount(1).descriptorType(d.type());
				if (d.type() == Desc.UBO || d.type() == Desc.SSBO) {
					w.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(d.buffer()).offset(d.offset()).range(d.range()));
				} else {
					w.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(d.view()).sampler(d.sampler()).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL));
				}
			}
			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, this.bindPoint, this.layout, 0, writes);
		}
	}

	public void push(VkCommandBuffer cb, int... values) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = stack.calloc(PUSH_SIZE);
			for (int i = 0; i < values.length; i++) {
				data.putInt(i * 4, values[i]);
			}
			VK10.vkCmdPushConstants(cb, this.layout, STAGES, 0, data);
		}
	}

	public static int f(float v) {
		return Float.floatToRawIntBits(v);
	}

	@Override
	public void close() {
		VK10.vkDestroyPipeline(Vk.vk(), this.pipeline, null);
		VK10.vkDestroyPipelineLayout(Vk.vk(), this.layout, null);
		VK10.vkDestroyDescriptorSetLayout(Vk.vk(), this.setLayout, null);
	}
}
