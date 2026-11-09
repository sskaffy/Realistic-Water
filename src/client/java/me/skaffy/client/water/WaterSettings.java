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
	public static float windowLookahead = 0.6F;

	public static float cfl = 2.0F;
	public static float maxDt = 1.0F / 40.0F;
	public static int maxStepsPerFrame = 4;
	public static int renderMaxStepsPerFrame = 64;
	public static int cgIterations = 5;
	public static int mgCoarseSweeps = 12;
	public static int solverBottomCells = 8192;
	public static int solverSmallCells = 24000;
	public static int extrapolationLayers = 4;
	public static float flipRatio = 0.99F;
	public static float volumeCorrection = 2.0F;
	public static float maxSpeed = 40.0F;
	public static float gravity = 9.81F;
	public static float blockGravity = 9.81F;
	public static String blockGravityPerBlock = "";

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
	public static boolean vanillaWaterExchange = true;
	public static boolean grabBodies = true;
	public static float grabStrength = 8000.0F;
	public static boolean opGrabbing = false;
	public static boolean grabCarrying = false;
	public static boolean realisticGrass = true;
	public static int grassStyle = 0;
	public static boolean grassTrails = true;
	public static float grassTrailTime = 45.0F;
	public static boolean grassCartoon = false;
	public static boolean grassPackDraw = true;
	public static int grassRadius = 32;
	public static int grassBlades = 200;
	public static float grassHeight = 1.0F;
	public static float grassWidth = 1.0F;
	public static float grassWind = 1.0F;
	public static float grassStiffness = 1.0F;
	public static int grassPush = 0;
	public static int grassShadows = 1;
	public static float grassShadowStrength = 0.55F;
	public static int grassShadowResolution = 2048;
	public static float grassPullStrength = 0.35F;
	public static float grassLieTime = 180.0F;
	public static float grassRegrowTime = 60.0F;
	public static float grassGrabRadius = 0.2F;
	public static boolean realisticTreeLeaves = true;
	public static int treeLeafRadius = 24;
	public static int treeLeavesPerBlock = 400;
	public static float treeLeafSize = 1.25F;
	public static boolean treeVanillaTextures = false;
	public static int treeStickRule = 0;
	public static int treeSideSticks = 3;
	public static boolean treePackDraw = true;
	public static float treeLeafDetailRange = 12.0F;
	public static float treeLeafFarDensity = 0.25F;
	public static float treeShadowLeaves = 0.35F;
	public static float treeWind = 1.0F;
	public static float treeRainHits = 1.0F;
	public static boolean treeLeafSinking = true;
	public static float treeGrabRadius = 0.3F;
	public static float treeLeafPullDistance = 0.5F;
	public static float treeFallenLeafLife = 300.0F;
	public static boolean realisticKelp = true;
	public static int kelpRadius = 48;
	public static float kelpSway = 0.6F;
	public static float kelpBuoyancy = 5.0F;
	public static float kelpWaterDrag = 3.0F;
	public static float kelpStiffness = 0.08F;
	public static boolean kelpSelfCollision = true;
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
	public static boolean slimeOldTexture = false;
	public static float slimeGelAbsorbR = 1.1F;
	public static float slimeGelAbsorbG = 0.25F;
	public static float slimeGelAbsorbB = 1.4F;
	public static float slimeGelScatterR = 0.30F;
	public static float slimeGelScatterG = 0.62F;
	public static float slimeGelScatterB = 0.18F;
	public static float slimeGelDensity = 1.6F;
	public static float slimeGelHaze = 1.2F;
	public static float slimeGelRefraction = 0.05F;
	public static float slimeGelGlow = 0.8F;
	public static float slimeGelBubbles = 0.6F;
	public static float slimeGelGloss = 1.0F;
	public static boolean wobblyBlocks = true;
	public static float wobblySoftness = 1.0F;
	public static int wobblyRange = 64;
	public static boolean realisticIce = true;
	public static float realisticIceCloudiness = 0.25F;
	public static float realisticIceBubbles = 0.45F;
	public static float realisticIceCracks = 0.35F;
	public static float realisticIceRefraction = 0.6F;
	public static float honeyGelAbsorbR = 0.12F;
	public static float honeyGelAbsorbG = 0.65F;
	public static float honeyGelAbsorbB = 2.0F;
	public static float honeyGelScatterR = 0.95F;
	public static float honeyGelScatterG = 0.52F;
	public static float honeyGelScatterB = 0.12F;
	public static float honeyGelDensity = 1.8F;
	public static float honeyGelHaze = 1.6F;
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
	public static float packWaterVisibility = 0.5F;
	public static float packWaterTint = 0.5F;
	public static boolean packShadows = true;
	public static float packShadowStrength = 0.55F;
	public static boolean packShadowPerLeaf = true;
	public static int packShadowDetail = 4;
	public static boolean packDrawMaterials = true;

	public static float packWaterOpacity = 0.25F;
	public static boolean waterSeaLook = false;
	public static boolean debugWaterLayers = false;

	public static boolean realisticRain = true;
	public static int rainRadius = 32;
	public static float rainDensity = 9.0F;
	public static float rainFallSpeed = 9.0F;
	public static float rainStreakTime = 0.03F;
	public static float rainOpacity = 0.3F;
	public static float rainWind = 1.5F;
	public static float rainSplashDroplets = 3.0F;
	public static float rainKick = 0.6F;
	public static float rainLavaSteam = 0.05F;
	public static float rainFeed = 0.02F;

	public static boolean realisticOcean = false;
	public static boolean oceanPackDrawsSea = true;
	public static int oceanResolution = 3;
	public static int oceanSimRadius = 3;
	public static int oceanSimDepth = 3;
	public static boolean oceanSimUnderwater = false;
	public static int oceanSimHeadroom = 2;
	public static float oceanSettleTime = 1.0F;
	public static float oceanSnapDeviation = 0.8F;
	public static float oceanWaveHeight = 1.0F;
	public static int oceanServerRadius = 32;
	public static float oceanEdgeBand = 1.5F;
	public static float oceanEdgePull = 6.0F;
	public static float oceanEdgeRefill = 2.0F;
	public static float oceanEdgeDrain = 6.0F;
	public static float oceanCalming = 0.4F;
	public static float oceanThicknessScale = 1.0F;
	public static float oceanSurfaceOpacity = 0.45F;
	public static float oceanRefraction = 0.15F;
	public static float oceanAbsorbR = 0.14F;
	public static float oceanAbsorbG = 0.05F;
	public static float oceanAbsorbB = 0.035F;
	public static float oceanScatterR = 0.16F;
	public static float oceanScatterG = 0.38F;
	public static float oceanScatterB = 0.75F;
	public static float oceanScatterDensity = 0.12F;
	public static float oceanUnderwaterFog = 0.15F;
	public static float oceanDetailStrength = 0.4F;
	public static float oceanRoughness = 0.06F;
	public static float oceanSpecular = 6.0F;
	public static float oceanFlipRatio = 0.99F;
	public static boolean oceanWhitewater = true;
	public static float oceanWhitecaps = 1.0F;
	public static float oceanWhitecapStart = 0.6F;
	public static int oceanWhitecapRadius = 64;
	public static float packFoamSize = 0.12F;

	public static boolean waterTemperature = true;
	public static float waterHeatTimeScale = 1.0F;
	public static float waterMixing = 5.0e-4F;
	public static float airHeatTransfer = 15.0F;
	public static float iceHeatTransfer = 300.0F;
	public static float magmaWaterTemperature = 90.0F;
	public static float magmaHeatReach = 3.0F;
	public static float magmaHeatTime = 2.0F;
	public static float lavaBoilTime = 0.15F;
	public static float fireWaterTemperature = 120.0F;
	public static float fireHeatTime = 12.0F;
	public static float furnaceWaterTemperature = 80.0F;
	public static float furnaceHeatTime = 120.0F;
	public static float boilChurn = 6.0F;
	public static float boilSteam = 1.0F;
	public static float steamWispTemperature = 60.0F;
	public static float boilingDamage = 1.0F;
	public static boolean waterFreezing = true;
	public static float slushDrag = 6.0F;
	public static float iceCloudiness = 0.35F;
	public static float iceBubbles = 0.5F;
	public static float iceCracks = 0.3F;
	public static float slushGrainSize = 1.0F;
	public static boolean slushCrystals = true;
	public static float slushCrystalSize = 0.03F;
	public static float slushCrystalOpacity = 0.45F;
	public static float slushSurface = 0.0F;
	public static boolean boilingSounds = true;
	public static float boilingSoundVolume = 0.4F;
	public static float boilBubbleSize = 0.12F;
	public static float boilBubbleRise = 1.0F;
	public static float boilBubbleRate = 10.0F;
	public static float boilClingTemperature = 70.0F;

	public static int steamResolution = 4;
	public static float steamParticleSize = 0.08F;
	public static float steamExpansion = 6.0F;
	public static float steamLifetime = 8.0F;
	public static float steamBuoyancy = 1.2F;
	public static float steamDrag = 1.0F;
	public static float steamSwirl = 0.6F;
	public static float steamBurst = 1.2F;
	public static float steamEntityPush = 0.6F;
	public static float steamOpacity = 0.35F;
	public static float steamDarkShade = 0.45F;
	public static float steamLightShade = 0.95F;

	public static boolean fire = true;
	public static boolean fireVanillaBlocks = false;
	public static int fireMaxSources = 256;
	public static float fireRange = 64.0F;
	public static boolean fireParticles = true;
	public static float fireParticleDensity = 1.0F;
	public static float fireParticleRange = 32.0F;
	public static int fireParticleBudget = 200;
	public static boolean fireSteam = true;
	public static boolean fireEmbers = true;
	public static float fireWind = 1.0F;

	public static String ffmpeg = "";
	public static boolean renderFreezeWorld = true;
	public static boolean renderSteadyPreview = true;
	public static float renderCameraSmoothing = 0.12F;

	private static final Map<String, String> DEFAULTS = snapshot();

	private WaterSettings() {
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
		if ("lavaWaterMixing".equals(name) && t == boolean.class) {
			ModSettings.lavaWaterMixing = (boolean) f.get(null);
		}
		return String.valueOf(f.get(null));
	}
}
