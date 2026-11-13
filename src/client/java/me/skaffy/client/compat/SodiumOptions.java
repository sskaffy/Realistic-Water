package me.skaffy.client.compat;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import me.skaffy.RealisticWater;
import me.skaffy.client.water.PerformanceMode;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.client.water.WaterSettingsInfo;
import me.skaffy.client.water.WaterSettingsScreen;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ModOptionsBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.StatefulOptionBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class SodiumOptions implements ConfigEntryPoint {
	private static final StorageEventHandler SAVE = WaterSettings::save;
	private static final Identifier MODE_ID = Identifier.fromNamespaceAndPath(RealisticWater.MOD_ID, "performance_mode");

	@Override
	public void registerConfigLate(ConfigBuilder builder) {
		ModOptionsBuilder mod = builder.registerOwnModOptions();
		Set<String> ids = new HashSet<>();
		OptionPageBuilder first = builder.createOptionPage().setName(Component.translatable("realistic-water.settings.title"));
		first.addOption(builder.createExternalButtonOption(id("open_water_gui", ids))
			.setName(Component.literal("All settings (/water gui)"))
			.setTooltip(Component.literal("Opens the full Realistic Water settings menu, the same one /water gui opens."))
			.setScreenConsumer(screen -> Minecraft.getInstance().gui.setScreen(new WaterSettingsScreen(screen, ""))));
		mod.addPage(first);
		for (String category : WaterSettingsInfo.categories()) {
			OptionGroupBuilder group = builder.createOptionGroup().setName(Component.literal(category));
			int added = 0;
			for (String name : WaterSettingsInfo.names(category)) {
				WaterSettingsInfo.Info info = WaterSettingsInfo.get(name);
				OptionBuilder option = info == null ? null : option(builder, info, ids);
				if (option != null) {
					group.addOption(option);
					added++;
				}
			}
			if (added > 0) {
				mod.addPage(builder.createOptionPage().setName(Component.literal(category)).addOptionGroup(group));
			}
		}
	}

	private static @Nullable OptionBuilder option(ConfigBuilder builder, WaterSettingsInfo.Info info, Set<String> ids) {
		String name = info.name();
		StatefulOptionBuilder<?> option = switch (info.kind()) {
			case SWITCH -> builder.createBooleanOption(id(name, ids))
				.setDefaultValue(Boolean.parseBoolean(WaterSettings.defaultValue(name)))
				.setBinding(on -> set(name, on.toString()), () -> Boolean.parseBoolean(get(name)));
			case CHOICE -> {
				java.util.List<String> labels = WaterSettingsInfo.choices(name);
				int max = Math.max(0, labels.size() - 1);
				yield builder.createIntegerOption(id(name, ids))
					.setRange(0, max, 1)
					.setValueFormatter(v -> Component.literal(labels.isEmpty() ? String.valueOf(v) : labels.get(clamp(v, 0, max))))
					.setDefaultValue(clamp(parseInt(WaterSettings.defaultValue(name), 0), 0, max))
					.setBinding(v -> set(name, String.valueOf(v)), () -> clamp(parseInt(get(name), 0), 0, max));
			}
			case INTEGER -> {
				int min = (int) info.min();
				int max = Math.max((int) info.max(), min);
				yield builder.createIntegerOption(id(name, ids))
					.setRange(min, max, 1)
					.setValueFormatter(v -> Component.literal(String.valueOf(v)))
					.setDefaultValue(clamp(parseInt(WaterSettings.defaultValue(name), min), min, max))
					.setBinding(v -> set(name, String.valueOf(v)), () -> clamp(parseInt(get(name), min), min, max));
			}
			case NUMBER -> {
				double step = info.step() > 0.0 ? info.step() : (info.max() - info.min()) / 1000.0;
				int steps = step > 0.0 ? Math.max(1, (int) Math.round((info.max() - info.min()) / step)) : 1;
				yield builder.createIntegerOption(id(name, ids))
					.setRange(0, steps, 1)
					.setValueFormatter(i -> Component.literal(WaterSettingsScreen.format(info, info.min() + i * step)))
					.setDefaultValue(index(WaterSettings.defaultValue(name), info, step, steps))
					.setBinding(i -> set(name, WaterSettingsScreen.format(info, info.min() + i * step)), () -> index(get(name), info, step, steps));
			}
			case TEXT -> null;
		};
		if (option == null) {
			return null;
		}
		option.setName(Component.literal(info.label()))
			.setTooltip(WaterSettingsScreen.tooltip(info))
			.setStorageHandler(SAVE);
		OptionImpact impact = switch (info.cost()) {
			case NONE -> null;
			case LOW -> OptionImpact.LOW;
			case MEDIUM -> OptionImpact.MEDIUM;
			case HIGH, EXTREME -> OptionImpact.HIGH;
		};
		if (impact != null) {
			option.setImpact(impact);
		}
		if (PerformanceMode.controlledSettings().contains(name)) {
			option.setEnabledProvider(state -> !PerformanceMode.controls(state.readIntOption(MODE_ID), name), MODE_ID);
		}
		return option;
	}

	private static Identifier id(String name, Set<String> ids) {
		String path = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
		while (!ids.add(path)) {
			path += "_";
		}
		return Identifier.fromNamespaceAndPath(RealisticWater.MOD_ID, path);
	}

	private static int index(String value, WaterSettingsInfo.Info info, double step, int steps) {
		try {
			return clamp((int) Math.round((Double.parseDouble(value) - info.min()) / step), 0, steps);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static String get(String name) {
		try {
			return WaterSettings.get(name);
		} catch (ReflectiveOperationException e) {
			return "";
		}
	}

	private static void set(String name, String value) {
		try {
			WaterSettings.set(name, value);
		} catch (ReflectiveOperationException | NumberFormatException e) {
			RealisticWater.LOGGER.warn("Couldn't set {} to {}", name, value, e);
		}
	}
}
