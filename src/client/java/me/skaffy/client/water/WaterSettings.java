package me.skaffy.client.water;

import me.skaffy.ModSettings;
import me.skaffy.RealisticWater;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

public final class WaterSettings {
	public static int resolution = 6;
	public static int boxMargin = 6;

	public static float cfl = 2.0F;
	public static float maxDt = 1.0F / 40.0F;
	public static int maxStepsPerFrame = 4;
	public static int renderMaxStepsPerFrame = 64;

	public static boolean adaptiveQuality = true;
	public static float targetFrameMs = 20.0F;
	public static float adaptiveMinQuality = 0.4F;
	public static float simDistance = 96.0F;
	public static float farMeshDistance = 40.0F;
	public static int farMeshInterval = 3;

	public static int cgIterations = 5;
	public static int mgCoarseSweeps = 12;
	public static int solverBottomCells = 8192;
	public static int solverSmallCells = 24000;
	public static int extrapolationLayers = 4;
	public static float flipRatio = 0.99F;
	public static float volumeCorrection = 2.0F;
	public static float maxSpeed = 40.0F;
	public static float gravity = 9.81F;

	public static boolean whitewater = true;
	public static float wwTrappedAirRate = 30.0F;
	public static float wwWaveCrestRate = 45.0F;
	public static float wwEnergyMin = 1.0F;
	public static float wwEnergyMax = 6.0F;
	public static float wwTrappedAirMin = 2.0F;
	public static float wwTrappedAirMax = 14.0F;
	public static float wwWaveCrestMin = 0.4F;
	public static float wwWaveCrestMax = 2.5F;
	public static float wwFoamLifeMin = 1.5F;
	public static float wwFoamLifeMax = 4.0F;
	public static float wwBubbleBuoyancy = 2.5F;
	public static float wwBubbleDrag = 0.6F;
	public static float wwSprayDrag = 0.4F;
	public static float wwSprayThreshold = 3.0F;
	public static float wwBubbleThreshold = 6.0F;
	public static int wwMaxPerParticle = 4;
	public static int wwCellLimit = 12;

	public static float filmFriction = 40.0F;
	public static float filmDepth = 0.08F;
	public static boolean sheetFill = false;
	public static boolean wallFill = true;
	public static float wallFillReach = 1.0F;
	public static float sheetSmoothing = 2.0F;
	public static float strandedTime = 1.5F;
	public static int strandedNeighbors = 12;

	public static int surfaceSubdivision = 2;
	public static float surfaceKernel = 3.0F;
	public static float surfaceRadius = 0.9F;
	public static float surfaceIso = 0.5F;
	public static float dropletThreshold = 0.35F;
	public static float nearCull = 0.05F;
	public static boolean surfaceGather = true;
	public static boolean sortParticles = true;
	public static boolean surfaceInteriorSkip = true;
	public static int surfaceInteriorCount = 4;

	public static float fluidRestSpeed = 0.15F;
	public static float fluidRestTime = 1.5F;
	public static int fluidRestWhitewater = 32;

	public static float particleRadius = 0.4F;
	public static float thicknessScale = 1.0F;
	public static float refraction = 0.15F;
	public static float absorbR = 0.45F;
	public static float absorbG = 0.12F;
	public static float absorbB = 0.07F;
	public static float scatterR = 0.03F;
	public static float scatterG = 0.08F;
	public static float scatterB = 0.11F;
	public static float scatterDensity = 0.5F;
	public static float underwaterFog = 0.1F;
	public static boolean underwaterMirror = false;
	public static float surfaceSmoothing = 0.6F;
	public static float detailStrength = 0.4F;
	public static float detailScale = 6.0F;
	public static float detailSpeed = 1.4F;
	public static float roughness = 0.06F;
	public static float specular = 6.0F;
	public static float wetDarkening = 0.3F;
	public static float filmShading = 0.25F;
	public static float wwRadius = 0.012F;
	public static float wwMaxPixels = 2.5F;
	public static float foamCoverage = 1.2F;
	public static float foamSize = 3.0F;
	public static float foamBubbleSize = 0.05F;
	public static float bubbleAlpha = 0.25F;
	public static float sprayAlpha = 0.35F;

	public static float sourceFill = 0.9F;
	public static boolean bucketMakesSource = false;

	public static boolean flowSounds = true;
	public static float flowSoundVolume = 1.0F;

	public static int sandResolution = 12;
	public static float sandFrictionAngle = 15.0F;
	public static float sandCohesion = 0.015F;
	public static float sandStiffness = 62.5F;
	public static float sandWallFriction = 0.5F;
	public static float sandCfl = 0.35F;
	public static int sandMaxStepsPerFrame = 16;
	public static float sandImpactDamping = 0.5F;
	public static float sandImpactRate = 2.0F;
	public static float sandImpactCompression = 0.15F;
	public static float sandRestSpeed = 0.05F;
	public static float sandRestTime = 0.15F;
	public static float sandWakeAccel = 0.5F;
	public static float sandSurfaceIso = 0.5F;
	public static float sandSurfaceSmoothing = 0.6F;
	public static float sandGrainSize = 0.01F;
	public static int sandGrainsPerParticle = 32;
	public static float sandGrainSpread = 0.5F;
	public static float sandGrainMinPixels = 0.7F;
	public static float sandBrightness = 1.1F;
	public static boolean sandSounds = true;

	public static int leafResolution = 8;
	public static int leavesPerBlock = 200;
	public static int leafCellCapacity = 1;
	public static float leafSize = 0.3125F;
	public static float leafFallSpeed = 1.2F;
	public static float leafSinkSpeed = 0.25F;
	public static float leafFlutter = 0.8F;
	public static float leafAirDrag = 1.2F;
	public static float leafTumbleRate = 5.0F;
	public static float leafGroundFriction = 10.0F;
	public static int leafRepose = 2;
	public static float leafShuffleSpeed = 1.5F;
	public static float leafRestSpeed = 0.06F;
	public static float leafRestTime = 0.5F;
	public static float leafWakeSpeed = 0.3F;
	public static float leafKick = 1.4F;
	public static float leafBurnTime = 0.6F;
	public static float leafWindStrength = 2.5F;
	public static float leafWindLift = 0.5F;
	public static float leafMaxSpeed = 12.0F;
	public static int leafMaxStepsPerFrame = 8;
	public static float leafBrightness = 1.0F;
	public static boolean leafSounds = true;

	public static float waveMakerRate = 4.0F;
	public static float waveMakerSpeed = 1.0F;

	public static int lavaResolution = 6;
	public static float lavaFlipRatio = 0.9993F;
	public static float lavaMaxSpeed = 5.0F;
	public static float lavaFilmFriction = 140.0F;
	public static float lavaStrandedTime = 5.0F;
	public static int lavaStrandedNeighbors = 16;
	public static float lavaFilmDepth = 0.05F;
	public static float lavaRefraction = 0.04F;
	public static float lavaAbsorbR = 2.8F;
	public static float lavaAbsorbG = 5.5F;
	public static float lavaAbsorbB = 8.0F;
	public static float lavaScatterR = 1.2F;
	public static float lavaScatterG = 0.35F;
	public static float lavaScatterB = 0.05F;
	public static float lavaScatterDensity = 2.5F;
	public static float lavaUnderwaterFog = 0.35F;
	public static float lavaSurfaceSmoothing = 0.75F;
	public static float lavaDetailStrength = 0.25F;
	public static float lavaRoughness = 0.35F;
	public static float lavaSpecular = 2.5F;
	public static boolean lavaFlowSounds = true;
	public static boolean lavaIgniteFlammables = true;
	public static boolean lavaWaterMixing = true;
	public static float lavaMixLavaMin = 0.06F;
	public static float lavaMixWaterMin = 0.03F;

	public static int slimeResolution = 4;
	public static float slimeFlipRatio = 0.9F;
	public static float slimeMaxSpeed = 3.0F;
	public static float slimeVolumeCorrection = 6.0F;
	public static float slimeViscosity = 1.5F;
	public static int slimeViscosityIterations = 4;
	public static float slimeAdhesion = 18.0F;
	public static float slimeEntityStick = 0.85F;
	public static float slimeRestSpeed = 0.6F;
	public static float slimeRestTime = 0.1F;
	public static float slimeWakeSpeed = 1.5F;
	public static float slimeFilmFriction = 220.0F;
	public static float slimeFilmDepth = 0.15F;
	public static float slimeStrandedTime = 8.0F;
	public static int slimeStrandedNeighbors = 4;
	public static float slimeOpacity = 0.85F;
	public static float slimeBrightness = 1.0F;
	public static float slimeSurfaceSmoothing = 0.85F;
	public static float slimeInsideOpacity = 0.92F;
	public static float slimeFogDistance = 0.5F;
	public static boolean slimeSounds = true;

	public static boolean tntReactions = true;
	public static float tntBlastSpeed = 14.0F;
	public static float tntBlastReach = 2.0F;
	public static float tntUpwardBias = 0.35F;
	public static float tntScatter = 0.25F;

	public static boolean vitrailInsidePack = true;

	public static String ffmpeg = "";
	public static boolean renderFreezeWorld = true;
	public static boolean renderSteadyPreview = true;
	public static float renderCameraSmoothing = 0.12F;

	/** Runtime scale (adaptiveMinQuality..1) driven by the frame-time governor in WaterWorld. Not a setting, never saved. */
	static float quality = 1.0F;

	private static final Map<String, String> DEFAULTS = snapshot();

	private WaterSettings() {
	}

	static int effectiveMaxSteps() {
		return Math.max(1, Math.round(maxStepsPerFrame * (adaptiveQuality ? quality : 1.0F)));
	}

	static int effectiveCgIterations() {
		return Math.max(2, Math.min(cgIterations, Math.round(cgIterations * (adaptiveQuality ? quality : 1.0F))));
	}

	/** Slowly lowers quality while frames run over the target and restores it once there is headroom. */
	static void adapt(float smoothedFrameMs) {
		if (!adaptiveQuality || targetFrameMs <= 0.0F) {
			quality = 1.0F;
			return;
		}
		float floor = Math.clamp(adaptiveMinQuality, 0.1F, 1.0F);
		if (smoothedFrameMs > targetFrameMs * 1.15F) {
			quality = Math.max(floor, quality - 0.02F);
		} else if (smoothedFrameMs < targetFrameMs * 0.85F) {
			quality = Math.min(1.0F, quality + 0.005F);
		}
		quality = Math.max(floor, quality);
	}

	private static Map<String, String> snapshot() {
		Map<String, String> out = new TreeMap<>();
		for (Map.Entry<String, Field> e : fields().entrySet()) {
			try {
				out.put(e.getKey(), String.valueOf(e.getValue().get(null)));
			} catch (IllegalAccessException ignored) {
			}
		}
		return out;
	}

	public static String defaultValue(String name) {
		return DEFAULTS.getOrDefault(name, "");
	}

	public static String get(String name) throws ReflectiveOperationException {
		Field f = fields().get(name);
		if (f == null) {
			throw new NoSuchFieldException(name);
		}
		return String.valueOf(f.get(null));
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("realistic-water.properties");
	}

	public static void load() {
		Path file = file();
		if (!Files.exists(file)) {
			return;
		}
		Properties props = new Properties();
		try (Reader in = Files.newBufferedReader(file)) {
			props.load(in);
		} catch (IOException e) {
			RealisticWater.LOGGER.warn("Couldn't read {}", file, e);
			return;
		}
		for (String name : props.stringPropertyNames()) {
			try {
				String value = props.getProperty(name);
				set(name, value.isEmpty() ? "-" : value);
			} catch (ReflectiveOperationException | NumberFormatException e) {
				RealisticWater.LOGGER.warn("Skipping setting {} from {}", name, file);
			}
		}
	}

	public static void save() {
		Properties props = new Properties();
		for (String name : fields().keySet()) {
			try {
				String value = get(name);
				if (!value.equals(defaultValue(name))) {
					props.setProperty(name, value);
				}
			} catch (ReflectiveOperationException ignored) {
			}
		}
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			try (Writer out = Files.newBufferedWriter(file)) {
				props.store(out, "Realistic Water settings that differ from the defaults (edit in game with /water)");
			}
		} catch (IOException e) {
			RealisticWater.LOGGER.warn("Couldn't save {}", file, e);
		}
	}

	public static void reset(String name) throws ReflectiveOperationException {
		if (DEFAULTS.containsKey(name)) {
			set(name, DEFAULTS.get(name).isEmpty() ? "-" : DEFAULTS.get(name));
		}
	}

	public static Map<String, Field> fields() {
		Map<String, Field> out = new TreeMap<>();
		for (Field f : WaterSettings.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers())) {
				out.put(f.getName(), f);
			}
		}
		return out;
	}

	public static String set(String name, String value) throws ReflectiveOperationException {
		Field f = fields().get(name);
		if (f == null) {
			throw new NoSuchFieldException(name);
		}
		Class<?> t = f.getType();
		if (t == int.class) {
			f.setInt(null, Integer.parseInt(value));
		} else if (t == float.class) {
			f.setFloat(null, Float.parseFloat(value));
		} else if (t == boolean.class) {
			f.setBoolean(null, Boolean.parseBoolean(value));
		} else if (t == String.class) {
			f.set(null, value.equals("-") ? "" : value);
		}
		if ("lavaIgniteFlammables".equals(name) && t == boolean.class) {
			ModSettings.lavaIgniteFlammables = (boolean) f.get(null);
		}
		if ("bucketMakesSource".equals(name) && t == boolean.class) {
			ModSettings.bucketMakesSource = (boolean) f.get(null);
		}
		if ("lavaWaterMixing".equals(name) && t == boolean.class) {
			ModSettings.lavaWaterMixing = (boolean) f.get(null);
		}
		return String.valueOf(f.get(null));
	}
}
