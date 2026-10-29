package me.skaffy.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import me.skaffy.RealisticWater;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterWorld;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class ShaderCompat {
	public static final boolean VITRAIL = FabricLoader.getInstance().isModLoaded("vitrail");
	public static final boolean SULKAN = FabricLoader.getInstance().isModLoaded("sulkan");
	public static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");

	private static final String VITRAIL_CHAIN = "dev.vitrail.render.PackChain";
	private static final String VITRAIL_GAME_RENDER = "dev.vitrail.render.GameRender";
	private static final String VITRAIL_LEVEL_PASS = "dev.vitrail.render.LevelPass";
	private static final String VITRAIL_GEOMETRY_HOLD = "dev.vitrail.render.GeometryHold";
	private static final String SULKAN_SCENE_PASS = "com.sulkan.shaders.graph.SceneRenderPass";
	private static final String VITRAIL_SHADOW_GEOMETRY = "dev.vitrail.render.ShadowGeometry";
	private static @Nullable Field shadowStorage;
	private static @Nullable Field shadowGathered;
	private static boolean shadowLooked;
	private static @Nullable Field activeChain;
	private static Field chainProgram;
	private static Field chainValues;
	private static Field chainTargets;
	private static Method programPackName;
	private static Field valuesState;
	private static Field frameView;
	private static Field mapModelView;
	private static Field mapProjection;
	private static Field targetsShadow;
	private static Method shadowDepth;
	private static Method shadowResolution;
	private static boolean packShadowLooked;

	private static @Nullable MethodHandle drawingPack;
	private static @Nullable Field featuresColour;
	private static @Nullable Field featuresDepth;
	private static @Nullable MethodHandle vitrailSuspend;
	private static @Nullable MethodHandle geometryFlush;
	private static @Nullable Field levelPassCurrent;
	private static @Nullable Field levelPassUnder;
	private static @Nullable Method levelPassSuspend;
	private static @Nullable MethodHandle sulkanOutside;
	private static boolean vitrailLooked;
	private static boolean sulkanLooked;
	private static boolean openPassSaid;
	private static long skippedFrames;
	private static boolean layerMissingSaid;
	private static boolean drawn;

	private ShaderCompat() {
	}

	public static void logEnvironment() {
		RealisticWater.LOGGER.info("Realistic Water render compat: Sodium {}, Sulkan {}, Vitrail {}", SODIUM ? "present" : "absent",
			SULKAN ? "present" : "absent", VITRAIL ? "present" : "absent");
		if (SULKAN && VITRAIL) {
			RealisticWater.LOGGER.warn("Sulkan and Vitrail are both installed. They both wrap the level's render pass and together leave one open "
				+ "(the game then crashes in render3dHud). Use only one of them.");
		}
	}

	public static void beginLevel() {
		drawn = false;
		WaterWorld.get().beginLevel();
	}

	public static boolean wantsBeforeTranslucent() {
		return !drawn && !SULKAN && !vitrailPackRunning();
	}

	public static void beforeTranslucent() {
		if (wantsBeforeTranslucent()) {
			draw(FrameTarget.main());
		}
	}

	public static void inLevel() {
		if (!vitrailPackRunning()) {
			if (!drawn) {
				draw(FrameTarget.main());
			}
			rain(FrameTarget.main());
		}
	}

	private static void rain(@Nullable FrameTarget target) {
		if (target != null && WaterSettings.realisticRain) {
			outsidePasses(() -> WaterWorld.get().renderRain(target));
		}
	}

	public static void vitrailFeaturesClosing() {
		if (!WaterSettings.vitrailInsidePack || drawn || !vitrailPackRunning()) {
			return;
		}
		GpuTextureView colour = read(featuresColour);
		if (colour == null) {
			if (!layerMissingSaid && featuresColour == null) {
				layerMissingSaid = true;
				RealisticWater.LOGGER.warn("Vitrail's feature layer wasn't found; drawing fluids over the finished pack image instead");
			}
			return;
		}
		GpuTextureView depth = read(featuresDepth);
		if (depth == null) {
			depth = Minecraft.getInstance().gameRenderer.mainRenderTarget().getDepthTextureView();
		}
		draw(FrameTarget.of(colour, depth, true));
	}

	public static void afterLevel() {
		if (!drawn) {
			draw(FrameTarget.main());
		}
		if (vitrailPackRunning()) {
			rain(FrameTarget.main());
		}
		if (WaterWorld.get().hasLate()) {
			FrameTarget main = FrameTarget.main();
			if (main != null) {
				outsidePasses(() -> WaterWorld.get().renderLate(main));
			} else {
				WaterWorld.get().beginLevel();
			}
		}
	}

	private static void draw(@Nullable FrameTarget target) {
		if (target == null) {
			return;
		}
		outsidePasses(() -> {
			drawn = true;
			WaterWorld.get().renderFrame(target);
		});
	}

	private static void outsidePasses(Runnable action) {
		if (!passOpen()) {
			action.run();
			return;
		}
		Runnable outside = () -> {
			if (passOpen()) {
				suspendVitrailPass();
			}
			if (passOpen()) {
				sayOpenPass();
				return;
			}
			action.run();
		};
		MethodHandle sulkan = sulkanOutside();
		if (sulkan == null) {
			outside.run();
			return;
		}
		try {
			sulkan.invokeExact(outside);
		} catch (Throwable e) {
			sulkanOutside = null;
			RealisticWater.LOGGER.warn("Sulkan's SceneRenderPass.outside failed; drawing without it", e);
			outside.run();
		}
	}

	private static boolean passOpen() {
		return RenderSystem.getDevice().createCommandEncoder() instanceof FrontendCommandEncoder encoder && encoder.isInRenderPass();
	}

	private static void suspendVitrailPass() {
		if (!VITRAIL) {
			return;
		}
		lookUpVitrail();
		if (geometryFlush != null) {
			try {
				geometryFlush.invokeExact((Supplier<String>) () -> "Realistic Water fluids");
			} catch (Throwable e) {
				geometryFlush = null;
				RealisticWater.LOGGER.warn("Vitrail's GeometryHold.flush failed", e);
			}
		}
		if (!passOpen() || vitrailSuspend == null) {
			return;
		}
		try {
			vitrailSuspend.invokeExact();
		} catch (Throwable e) {
			vitrailSuspend = null;
			RealisticWater.LOGGER.warn("Vitrail's LevelPass.suspendCurrent failed", e);
		}
		if (!passOpen() || levelPassCurrent == null || levelPassUnder == null || levelPassSuspend == null) {
			return;
		}
		try {
			for (Object pass = levelPassCurrent.get(null); pass != null; pass = levelPassUnder.get(pass)) {
				levelPassSuspend.invoke(pass);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			levelPassSuspend = null;
			RealisticWater.LOGGER.warn("Couldn't step Vitrail's nested level passes aside", e);
		}
	}

	private static void sayOpenPass() {
		skippedFrames++;
		if (skippedFrames % 600 == 0) {
			RealisticWater.LOGGER.warn("Realistic fluids have skipped {} frames for an open render pass", skippedFrames);
		}
		if (openPassSaid) {
			return;
		}
		openPassSaid = true;
		String why = SULKAN && VITRAIL ? "Sulkan and Vitrail are both installed and leave a render pass open; use only one of them."
			: "another mod left a render pass open.";
		RealisticWater.LOGGER.warn("Realistic fluids skipped a frame: {}", why);
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("Realistic Water can't draw: " + why));
		}
	}

	private static @Nullable MethodHandle sulkanOutside() {
		if (!SULKAN) {
			return null;
		}
		if (!sulkanLooked) {
			sulkanLooked = true;
			try {
				sulkanOutside = MethodHandles.publicLookup().findStatic(Class.forName(SULKAN_SCENE_PASS), "outside",
					MethodType.methodType(void.class, Runnable.class));
			} catch (ReflectiveOperationException | LinkageError e) {
				RealisticWater.LOGGER.warn("Sulkan's SceneRenderPass.outside wasn't found", e);
			}
		}
		return sulkanOutside;
	}

	public static void vitrailShadowGathered() {
		if (!WaterWorld.get().hasShadowCasters()) {
			return;
		}
		lookUpShadowGeometry();
		if (shadowGathered == null) {
			return;
		}
		try {
			if (shadowGathered.getInt(null) == 0) {
				shadowGathered.setInt(null, 1);
			}
		} catch (IllegalAccessException | RuntimeException e) {
			shadowGathered = null;
			RealisticWater.LOGGER.warn("Couldn't keep Vitrail's shadow walk open for realistic shadow casters", e);
		}
	}

	public static void vitrailShadowSubmit(Vec3 camera) {
		lookUpShadowGeometry();
		if (shadowStorage == null) {
			return;
		}
		try {
			if (shadowStorage.get(null) instanceof SubmitNodeCollector collector) {
				WaterWorld.get().submitShadowCasters(collector, camera);
			}
		} catch (IllegalAccessException | RuntimeException e) {
			shadowStorage = null;
			RealisticWater.LOGGER.warn("Couldn't add realistic shadow casters to Vitrail's shadow pass", e);
		}
	}

	private static void lookUpShadowGeometry() {
		if (shadowLooked) {
			return;
		}
		shadowLooked = true;
		try {
			Class<?> geometry = Class.forName(VITRAIL_SHADOW_GEOMETRY);
			shadowStorage = geometry.getDeclaredField("storage");
			shadowStorage.setAccessible(true);
			shadowGathered = geometry.getDeclaredField("gathered");
			shadowGathered.setAccessible(true);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			shadowStorage = null;
			shadowGathered = null;
			RealisticWater.LOGGER.info("Vitrail's shadow geometry isn't reachable; sand, leaves and slime won't cast pack shadows ({})", e.toString());
		}
	}

	public record PackShadow(long view, Matrix4f modelView, Matrix4f projection, int resolution) {
	}

	private static final String[] BSL_WARP_PACKS = {"bsl", "complementary"};

	public static @Nullable PackShadow packShadowMap() {
		if (!VITRAIL || !vitrailPackRunning()) {
			return null;
		}
		lookUpPackShadow();
		if (activeChain == null) {
			return null;
		}
		try {
			Object chain = activeChain.get(null);
			if (chain == null) {
				return null;
			}
			Object program = chainProgram.get(chain);
			String name = program == null ? null : (String) programPackName.invoke(program);
			if (name == null || !knownWarp(name)) {
				return null;
			}
			Object view = frameView.get(valuesState.get(chainValues.get(chain)));
			Object shadow = targetsShadow.get(chainTargets.get(chain));
			if (view == null || shadow == null || !(shadowDepth.invoke(shadow) instanceof VulkanGpuTextureView depth)) {
				return null;
			}
			Matrix4f modelView = new Matrix4f((Matrix4fc) mapModelView.get(view));
			Matrix4f projection = new Matrix4f((Matrix4fc) mapProjection.get(view));
			return new PackShadow(depth.vkImageView(), modelView, projection, (int) shadowResolution.invoke(shadow));
		} catch (ReflectiveOperationException | RuntimeException e) {
			activeChain = null;
			RealisticWater.LOGGER.warn("Couldn't read Vitrail's shadow map; realistic materials fall back to their own shadow trace", e);
			return null;
		}
	}

	private static boolean knownWarp(String packName) {
		String lower = packName.toLowerCase(java.util.Locale.ROOT);
		for (String known : BSL_WARP_PACKS) {
			if (lower.contains(known)) {
				return true;
			}
		}
		return false;
	}

	private static void lookUpPackShadow() {
		if (packShadowLooked) {
			return;
		}
		packShadowLooked = true;
		try {
			Class<?> chain = Class.forName(VITRAIL_CHAIN);
			activeChain = field(chain, "active");
			chainProgram = field(chain, "chain");
			chainValues = field(chain, "values");
			chainTargets = field(chain, "targets");
			programPackName = Class.forName("dev.vitrail.glsl.PackProgram$Chain").getDeclaredMethod("packName");
			valuesState = field(Class.forName("dev.vitrail.render.PackValues"), "state");
			frameView = field(Class.forName("dev.vitrail.render.FrameState"), "view");
			Class<?> matrices = Class.forName("dev.vitrail.render.ViewMatrices");
			mapModelView = field(matrices, "mapShadowModelView");
			mapProjection = field(matrices, "mapShadowProjection");
			targetsShadow = field(Class.forName("dev.vitrail.render.ColorTargets"), "shadowMap");
			Class<?> shadowTargets = Class.forName("dev.vitrail.render.ShadowTargets");
			shadowDepth = shadowTargets.getDeclaredMethod("depthWithoutTranslucents");
			shadowDepth.setAccessible(true);
			shadowResolution = shadowTargets.getDeclaredMethod("resolution");
			shadowResolution.setAccessible(true);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			activeChain = null;
			RealisticWater.LOGGER.info("Vitrail's shadow map isn't reachable; realistic materials use their own shadow trace ({})", e.toString());
		}
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	public static boolean packRunning() {
		return SULKAN || vitrailPackRunning();
	}

	private static boolean vitrailPackRunning() {
		if (!VITRAIL) {
			return false;
		}
		lookUpVitrail();
		if (drawingPack == null) {
			return true;
		}
		try {
			return (boolean) drawingPack.invokeExact();
		} catch (Throwable e) {
			drawingPack = null;
			RealisticWater.LOGGER.warn("Couldn't ask Vitrail whether a pack is running; assuming one is", e);
			return true;
		}
	}

	private static void lookUpVitrail() {
		if (vitrailLooked) {
			return;
		}
		vitrailLooked = true;
		try {
			Class<?> chain = Class.forName(VITRAIL_CHAIN);
			drawingPack = MethodHandles.publicLookup().findStatic(chain, "drawingPack", MethodType.methodType(boolean.class));
		} catch (ReflectiveOperationException | LinkageError e) {
			RealisticWater.LOGGER.warn("Vitrail's PackChain.drawingPack() wasn't found; fluids will always draw after the level", e);
		}
		try {
			try {
				geometryFlush = MethodHandles.publicLookup().findStatic(Class.forName(VITRAIL_GEOMETRY_HOLD), "flush",
					MethodType.methodType(void.class, Supplier.class));
			} catch (ReflectiveOperationException | LinkageError e) {
				RealisticWater.LOGGER.info("Vitrail's GeometryHold.flush wasn't found ({})", e.toString());
			}
			Class<?> levelPass = Class.forName(VITRAIL_LEVEL_PASS);
			vitrailSuspend = MethodHandles.publicLookup().findStatic(levelPass, "suspendCurrent", MethodType.methodType(void.class));
			try {
				levelPassCurrent = levelPass.getDeclaredField("current");
				levelPassCurrent.setAccessible(true);
				levelPassUnder = levelPass.getDeclaredField("under");
				levelPassUnder.setAccessible(true);
				levelPassSuspend = levelPass.getDeclaredMethod("suspend");
				levelPassSuspend.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException e) {
				levelPassSuspend = null;
				RealisticWater.LOGGER.info("Vitrail's nested level passes aren't reachable ({})", e.toString());
			}
		} catch (ReflectiveOperationException | LinkageError e) {
			RealisticWater.LOGGER.warn("Vitrail's LevelPass.suspendCurrent() wasn't found", e);
		}
		try {
			Class<?> gameRender = Class.forName(VITRAIL_GAME_RENDER);
			featuresColour = gameRender.getDeclaredField("featuresColour");
			featuresColour.setAccessible(true);
			featuresDepth = gameRender.getDeclaredField("featuresDepth");
			featuresDepth.setAccessible(true);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			featuresColour = null;
			featuresDepth = null;
			RealisticWater.LOGGER.info("Vitrail's feature layer isn't reachable; 'Inside Vitrail packs' will draw on top instead ({})", e.toString());
		}
	}

	private static @Nullable GpuTextureView read(@Nullable Field field) {
		if (field == null) {
			return null;
		}
		try {
			return field.get(null) instanceof GpuTextureView view ? view : null;
		} catch (IllegalAccessException e) {
			return null;
		}
	}
}
