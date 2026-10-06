package me.skaffy.client.vk;

import java.nio.LongBuffer;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

public final class GraphicsProgram extends Program {
	public enum Blend { NONE, ADD }

	public record State(int colorFormat, int depthFormat, boolean depthTest, boolean depthWrite, int depthCompare, Blend blend) {
	}

	public GraphicsProgram(String vert, String frag, Map<String, String> defines, State state, int... types) {
		super(vert + "+" + frag, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, types);
		long vs = Shaders.module(vert, Shaders.VERTEX, defines);
		long fs = Shaders.module(frag, Shaders.FRAGMENT, defines);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vs).pName(stack.UTF8("main"));
			stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fs).pName(stack.UTF8("main"));

			VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
			VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
				.topology(VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
			VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
				.polygonMode(VK10.VK_POLYGON_MODE_FILL)
				.cullMode(VK10.VK_CULL_MODE_NONE)
				.frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE)
				.lineWidth(1.0F);
			VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
				.depthTestEnable(state.depthTest)
				.depthWriteEnable(state.depthWrite)
				.depthCompareOp(state.depthCompare);
			VkPipelineColorBlendAttachmentState.Buffer blend = VkPipelineColorBlendAttachmentState.calloc(1, stack).colorWriteMask(0xF);
			if (state.blend == Blend.ADD) {
				blend.blendEnable(true)
					.srcColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE).colorBlendOp(VK10.VK_BLEND_OP_ADD)
					.srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE).alphaBlendOp(VK10.VK_BLEND_OP_ADD);
			}
			VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blend);
			VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
			VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
				.rasterizationSamples(VK10.VK_SAMPLE_COUNT_1_BIT);
			VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
				.pDynamicStates(stack.ints(VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR));
			VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
				.colorAttachmentCount(1)
				.pColorAttachmentFormats(stack.ints(state.colorFormat))
				.depthAttachmentFormat(state.depthFormat);

			VkGraphicsPipelineCreateInfo.Buffer info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
				.pNext(rendering)
				.pStages(stages)
				.pVertexInputState(vertexInput)
				.pInputAssemblyState(assembly)
				.pRasterizationState(raster)
				.pDepthStencilState(depth)
				.pColorBlendState(colorBlend)
				.pViewportState(viewport)
				.pMultisampleState(multisample)
				.pDynamicState(dynamic)
				.layout(this.layout);
			LongBuffer p = stack.mallocLong(1);
			Vk.check(VK10.vkCreateGraphicsPipelines(Vk.vk(), 0L, info, null, p), "graphics pipeline " + this.name);
			this.pipeline = p.get(0);
		} finally {
			VK10.vkDestroyShaderModule(Vk.vk(), vs, null);
			VK10.vkDestroyShaderModule(Vk.vk(), fs, null);
		}
	}

	public static void begin(VkCommandBuffer cb, int width, int height, long colorView, float[] clearColor, long depthView) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkRenderingAttachmentInfo.Buffer color = VkRenderingAttachmentInfo.calloc(1, stack);
			color.get(0).sType$Default()
				.imageView(colorView)
				.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
				.storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE)
				.loadOp(clearColor != null ? VK10.VK_ATTACHMENT_LOAD_OP_CLEAR : VK10.VK_ATTACHMENT_LOAD_OP_LOAD);
			if (clearColor != null) {
				VkClearValue clear = VkClearValue.calloc(stack);
				clear.color().float32(0, clearColor[0]).float32(1, clearColor[1]).float32(2, clearColor[2]).float32(3, clearColor[3]);
				color.get(0).clearValue(clear);
			}
			VkRect2D area = VkRect2D.calloc(stack);
			area.extent().set(width, height);
			VkRenderingInfo info = VkRenderingInfo.calloc(stack).sType$Default()
				.renderArea(area)
				.layerCount(1)
				.pColorAttachments(color);
			if (depthView != 0L) {
				VkRenderingAttachmentInfo depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
					.imageView(depthView)
					.imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
					.loadOp(VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
					.storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
				info.pDepthAttachment(depth);
			}
			KHRDynamicRendering.vkCmdBeginRenderingKHR(cb, info);

			VkViewport.Buffer vp = VkViewport.calloc(1, stack).x(0).y(0).width(width).height(height).minDepth(0.0F).maxDepth(1.0F);
			VK10.vkCmdSetViewport(cb, 0, vp);
			VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
			scissor.get(0).extent().set(width, height);
			VK10.vkCmdSetScissor(cb, 0, scissor);
		}
	}

	public static void end(VkCommandBuffer cb) {
		KHRDynamicRendering.vkCmdEndRenderingKHR(cb);
	}

	public void draw(VkCommandBuffer cb, int vertices, int instances) {
		VK10.vkCmdDraw(cb, vertices, instances, 0, 0);
	}

	public void drawIndirect(VkCommandBuffer cb, VkBuf args, long offset) {
		VK10.vkCmdDrawIndirect(cb, args.handle, offset, 1, 16);
	}
}
