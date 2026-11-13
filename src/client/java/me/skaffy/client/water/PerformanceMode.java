package me.skaffy.client.water;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.RealisticWater;

public final class PerformanceMode {
	public static final List<String> LABELS = List.of("Off", "High", "Medium", "Low");

	private static final Map<String, String[]> PRESETS = new LinkedHashMap<>();

	static {
		preset("resolution", "4", "3", "2");
		preset("maxStepsPerFrame", "4", "3", "2");
		preset("cgIterations", null, "4", "3");
		preset("extrapolationLayers", null, "3", "2");
		preset("fluidRestTime", "1.0", "0.7", "0.4");
		preset("fluidRestSpeed", null, "0.2", "0.25");
		preset("whitewater", null, null, "false");
		preset("wwMaxPerParticle", null, "2", "1");
		preset("wwCellLimit", null, "8", "4");
		preset("oceanWhitewater", null, null, "false");
		preset("reflectionSteps", "32", "24", "12");
		preset("lavaResolution", "4", "3", "2");
		preset("slimeResolution", "3", "3", "2");
		preset("slimeViscosityIterations", null, "3", "2");
		preset("sandResolution", "8", "6", "4");
		preset("sandMaxStepsPerFrame", "12", "10", "8");
		preset("sandGrainsPerParticle", "40", "48", "64");
		preset("snowRadius", "64", "48", "32");
		preset("snowTrackRadius", "24", "20", "16");
		preset("snowDetail", "8", "6", "4");
		preset("snowfallDensity", null, "0.15", "0.08");
		preset("leavesPerBlock", null, "150", "100");
		preset("leafMaxStepsPerFrame", null, "6", "4");
		preset("lightRefreshBlocks", null, "2048", "1024");
		preset("blockSyncInterval", null, "45", "60");
	}

	private static void preset(String name, String high, String medium, String low) {
		PRESETS.put(name, new String[]{high, medium, low});
	}

	private static final Map<String, String> SAVED = new LinkedHashMap<>();
	private static int applied;
	private static boolean applying;

	private PerformanceMode() {
	}

	public static int mode() {
		return Math.clamp(WaterSettings.performanceMode, 0, LABELS.size() - 1);
	}

	public static String label(int mode) {
		return LABELS.get(Math.clamp(mode, 0, LABELS.size() - 1));
	}

	public static String presetValue(int mode, String name) {
		String[] values = PRESETS.get(name);
		return values == null || mode <= 0 || mode > values.length ? null : values[mode - 1];
	}

	public static boolean controls(int mode, String name) {
		return presetValue(mode, name) != null;
	}

	public static boolean overrides(String name) {
		return SAVED.containsKey(name);
	}

	public static String userValue(String name) {
		return SAVED.get(name);
	}

	static boolean applying() {
		return applying;
	}

	static void storeUserValue(String name, String value) {
		SAVED.put(name, value);
	}

	static void apply() {
		int mode = mode();
		if (mode == applied && (mode == 0 || !SAVED.isEmpty())) {
			return;
		}
		applying = true;
		try {
			for (Map.Entry<String, String> e : SAVED.entrySet()) {
				setRaw(e.getKey(), e.getValue());
			}
			SAVED.clear();
			if (mode > 0) {
				for (String name : PRESETS.keySet()) {
					String value = presetValue(mode, name);
					if (value == null) {
						continue;
					}
					try {
						SAVED.put(name, WaterSettings.get(name));
					} catch (ReflectiveOperationException e) {
						RealisticWater.LOGGER.warn("Performance mode: no setting {}", name);
						continue;
					}
					setRaw(name, value);
				}
			}
			applied = mode;
		} finally {
			applying = false;
		}
	}

	private static void setRaw(String name, String value) {
		try {
			WaterSettings.set(name, value);
		} catch (ReflectiveOperationException | NumberFormatException e) {
			RealisticWater.LOGGER.warn("Performance mode couldn't set {} to {}", name, value, e);
		}
	}

	public static List<String> controlledSettings() {
		return List.copyOf(PRESETS.keySet());
	}
}
