package me.skaffy.client.vk;

import java.nio.LongBuffer;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;

public final class ComputeProgram extends Program {
	public ComputeProgram(String shader, Map<String, String> defines, int... types) {
		super(shader, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, types);
		long module = Shaders.module(shader, Shaders.COMPUTE, defines);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(this.layout);
			info.stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
			LongBuffer p = stack.mallocLong(1);
			Vk.check(VK10.vkCreateComputePipelines(Vk.vk(), 0L, info, null, p), "compute pipeline " + shader);
			this.pipeline = p.get(0);
		} finally {
			VK10.vkDestroyShaderModule(Vk.vk(), module, null);
		}
	}

	public ComputeProgram(String shader, int... types) {
		this(shader, Map.of(), types);
	}

	public static final int MAX_GROUPS_X = 65535;

	public static int groups(long items, int groupSize) {
		return (int) Math.max(1L, (items + groupSize - 1) / groupSize);
	}

	public static int launched(int groups) {
		if (groups <= MAX_GROUPS_X) {
			return groups;
		}
		return MAX_GROUPS_X * ((groups + MAX_GROUPS_X - 1) / MAX_GROUPS_X);
	}

	public void dispatch(VkCommandBuffer cb, int x, int y, int z) {
		if (x > MAX_GROUPS_X && y == 1 && z == 1) {
			VK10.vkCmdDispatch(cb, MAX_GROUPS_X, (x + MAX_GROUPS_X - 1) / MAX_GROUPS_X, 1);
		} else {
			VK10.vkCmdDispatch(cb, x, y, z);
		}
	}

	public void dispatchIndirect(VkCommandBuffer cb, VkBuf args, long offset) {
		VK10.vkCmdDispatchIndirect(cb, args.handle, offset);
	}
}
