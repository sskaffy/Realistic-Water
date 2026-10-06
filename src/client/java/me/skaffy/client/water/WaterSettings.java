package me.skaffy.client.water;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeMap;

public final class WaterSettings {
	public static int resolution = 4;
	public static int boxMargin = 6;

	public static float cfl = 2.0F;
	public static float maxDt = 1.0F / 40.0F;
	public static int maxStepsPerFrame = 4;
	public static int renderMaxStepsPerFrame = 64;
	public static int cgIterations = 5;
	public static int mgCoarseSweeps = 12;
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
	public static float wwFoamLifeMin = 4.0F;
	public static float wwFoamLifeMax = 12.0F;
	public static float wwBubbleBuoyancy = 2.5F;
	public static float wwBubbleDrag = 0.6F;
	public static float wwSprayDrag = 0.4F;
	public static float wwSprayThreshold = 3.0F;
	public static float wwBubbleThreshold = 6.0F;
	public static int wwMaxPerParticle = 4;
	public static int wwCellLimit = 12;

	public static int surfaceSubdivision = 2;
	public static float surfaceIso = 0.4F;
	public static float dropletThreshold = 0.12F;
	public static float nearCull = 0.05F;

	public static float particleRadius = 0.4F;
	public static float thicknessScale = 1.0F;
	public static float refraction = 0.15F;
	public static float absorbR = 2.2F;
	public static float absorbG = 1.5F;
	public static float absorbB = 1.1F;
	public static float scatterR = 0.055F;
	public static float scatterG = 0.085F;
	public static float scatterB = 0.1F;
	public static float scatterDensity = 0.9F;
	public static float underwaterFog = 0.1F;
	public static float surfaceSmoothing = 1.0F;
	public static float detailStrength = 0.5F;
	public static float detailScale = 2.6F;
	public static float detailSpeed = 1.4F;
	public static float roughness = 0.12F;
	public static float specular = 6.0F;
	public static float wwRadius = 0.012F;
	public static float wwMaxPixels = 2.5F;
	public static float foamCoverage = 1.2F;
	public static float bubbleAlpha = 0.25F;
	public static float sprayAlpha = 0.35F;

	public static float sourceFill = 0.9F;
	public static boolean bucketMakesSource = false;

	public static String ffmpeg = "";
	public static boolean renderFreezeWorld = true;

	private WaterSettings() {
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
		return String.valueOf(f.get(null));
	}
}
