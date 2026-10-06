package me.skaffy.client.vk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

public final class Shaders {
	private static final String ROOT = "/assets/realistic-water/shaders/";
	public static final int VERTEX = Shaderc.shaderc_vertex_shader;
	public static final int FRAGMENT = Shaderc.shaderc_fragment_shader;
	public static final int COMPUTE = Shaderc.shaderc_compute_shader;

	private Shaders() {
	}

	public static String load(String name) {
		try (InputStream in = Shaders.class.getResourceAsStream(ROOT + name)) {
			if (in == null) {
				throw new IllegalStateException("Missing shader " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read shader " + name, e);
		}
	}

	private static String preprocess(String source, Set<String> seen) {
		StringBuilder out = new StringBuilder();
		for (String line : source.split("\n", -1)) {
			String trimmed = line.trim();
			if (trimmed.startsWith("#include")) {
				int a = trimmed.indexOf('"');
				int b = trimmed.lastIndexOf('"');
				String file = trimmed.substring(a + 1, b);
				if (seen.add(file)) {
					out.append(preprocess(load(file), seen)).append('\n');
				}
			} else {
				out.append(line).append('\n');
			}
		}
		return out.toString();
	}

	public static long module(String name, int kind, Map<String, String> defines) {
		String source = load(name);
		int nl = source.indexOf('\n');
		StringBuilder head = new StringBuilder(source.substring(0, nl + 1));
		defines.forEach((k, v) -> head.append("#define ").append(k).append(' ').append(v).append('\n'));
		String full = preprocess(head + source.substring(nl + 1), new HashSet<>());

		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		ByteBuffer src = MemoryUtil.memUTF8(full, false);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_performance);
			long result = Shaderc.shaderc_compile_into_spv(compiler, src, kind, stack.UTF8(name), stack.UTF8("main"), options);
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
					throw new IllegalStateException("Shader " + name + " failed to compile:\n" + Shaderc.shaderc_result_get_error_message(result));
				}
				ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
				VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spv);
				LongBuffer pModule = stack.mallocLong(1);
				Vk.check(VK10.vkCreateShaderModule(Vk.vk(), info, null, pModule), "vkCreateShaderModule " + name);
				return pModule.get(0);
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			MemoryUtil.memFree(src);
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}
}
