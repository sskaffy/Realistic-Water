package me.skaffy.client.water;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.skaffy.RealisticWater;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import org.jspecify.annotations.Nullable;

public class WaterSettingsScreen extends OptionsSubScreen {
	private static String lastCategory = "";
	private static boolean openPending;

	private final String category;

	public WaterSettingsScreen(@Nullable Screen lastScreen, String category) {
		super(lastScreen, Minecraft.getInstance().options, Component.translatable("realistic-water.settings.title"));
		List<String> categories = WaterSettingsInfo.categories();
		this.category = categories.contains(category) ? category : categories.getFirst();
		lastCategory = this.category;
	}

	public static void openNextTick() {
		openPending = true;
	}

	public static void tick(Minecraft mc) {
		if (openPending) {
			openPending = false;
			mc.gui.setScreen(new WaterSettingsScreen(mc.gui.screen(), lastCategory));
		}
	}

	@Override
	protected void addOptions() {
		CycleButton<String> categoryButton = CycleButton.builder(Component::literal, this.category)
			.withValues(WaterSettingsInfo.categories())
			.create(Component.translatable("realistic-water.settings.category"), (button, value) -> this.minecraft.gui.setScreen(new WaterSettingsScreen(this.lastScreen, value)));
		categoryButton.setTooltip(Tooltip.create(Component.translatable("realistic-water.settings.hint")));
		this.list.addBig(categoryButton);
		List<AbstractWidget> widgets = new ArrayList<>();
		for (String name : WaterSettingsInfo.names(this.category)) {
			widgets.add(this.widget(WaterSettingsInfo.get(name)));
		}
		this.list.addSmall(widgets);
	}

	@Override
	public void removed() {
		super.removed();
		WaterSettings.save();
	}

	@Override
	protected void addFooter() {
		LinearLayout buttons = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
		Button reset = Button.builder(Component.translatable("realistic-water.settings.resetCategory"), button -> {
			for (String name : WaterSettingsInfo.names(this.category)) {
				try {
					WaterSettings.reset(name);
				} catch (ReflectiveOperationException | NumberFormatException e) {
					RealisticWater.LOGGER.warn("Couldn't reset {}", name, e);
				}
			}
			this.minecraft.gui.setScreen(new WaterSettingsScreen(this.lastScreen, this.category));
		}).build();
		reset.setTooltip(Tooltip.create(Component.translatable("realistic-water.settings.resetCategory.tooltip")));
		buttons.addChild(reset);
		buttons.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).build());
	}

	private AbstractWidget widget(WaterSettingsInfo.Info info) {
		AbstractWidget widget = switch (info.kind()) {
			case SWITCH -> CycleButton.onOffBuilder(Boolean.parseBoolean(value(info.name())))
				.create(Component.literal(info.label()), (button, on) -> set(info.name(), on.toString()));
			case CHOICE -> {
				List<String> options = WaterSettingsInfo.choices(info.name());
				int current = Math.clamp(parseInt(value(info.name())), 0, Math.max(0, options.size() - 1));
				yield CycleButton.builder(Component::literal, options.get(current))
					.withValues(options)
					.create(Component.literal(info.label()), (button, picked) -> {
						set(info.name(), String.valueOf(options.indexOf(picked)));
						if ("performanceMode".equals(info.name())) {
							this.minecraft.gui.setScreen(new WaterSettingsScreen(this.lastScreen, this.category));
						}
					});
			}
			case TEXT -> {
				EditBox box = new EditBox(this.font, 150, 20, Component.literal(info.label()));
				box.setMaxLength(1024);
				box.setValue(value(info.name()));
				box.setHint(Component.literal(info.label()).withStyle(ChatFormatting.DARK_GRAY));
				box.setResponder(text -> set(info.name(), text.isEmpty() ? "-" : text));
				yield box;
			}
			default -> new SettingSlider(info);
		};
		widget.setTooltip(Tooltip.create(tooltip(info)));
		if (PerformanceMode.overrides(info.name())) {
			widget.active = false;
		}
		return widget;
	}

	private static int parseInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static String value(String name) {
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

	private static final Map<WaterSettingsInfo.Cost, Integer> COST_COLORS = Map.of(
		WaterSettingsInfo.Cost.NONE, 0x9AA7B8,
		WaterSettingsInfo.Cost.LOW, 0x7DFFA0,
		WaterSettingsInfo.Cost.MEDIUM, 0xFFE070,
		WaterSettingsInfo.Cost.HIGH, 0xFFA050,
		WaterSettingsInfo.Cost.EXTREME, 0xFF6060);

	public static Component tooltip(WaterSettingsInfo.Info info) {
		MutableComponent out = Component.empty();
		out.append(Component.literal(info.label()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
		out.append(Component.literal("\n/water set " + info.name()).withStyle(ChatFormatting.DARK_GRAY));
		out.append(Component.literal("\n" + info.description()).withStyle(ChatFormatting.GRAY));
		out.append(Component.literal("\n\nPerformance cost: " + info.cost().label)
			.withStyle(style -> style.withColor(TextColor.fromRgb(COST_COLORS.get(info.cost())))));
		String note = info.costNote().isEmpty() ? info.cost().meaning : info.costNote();
		out.append(Component.literal("\n" + note).withStyle(ChatFormatting.GRAY));
		String def = WaterSettings.defaultValue(info.name());
		if (info.kind() == WaterSettingsInfo.Kind.CHOICE) {
			List<String> options = WaterSettingsInfo.choices(info.name());
			int d = Math.clamp(parseInt(def), 0, Math.max(0, options.size() - 1));
			def = options.isEmpty() ? def : options.get(d);
		}
		out.append(Component.literal("\nDefault: " + (def.isEmpty() ? "(empty)" : def)).withStyle(ChatFormatting.DARK_GRAY));
		if (PerformanceMode.overrides(info.name())) {
			out.append(Component.literal("\n\nLocked by Performance mode (" + PerformanceMode.label(PerformanceMode.mode()) + "). Your value "
				+ PerformanceMode.userValue(info.name()) + " comes back when Performance mode is Off.").withStyle(ChatFormatting.YELLOW));
		}
		return out;
	}

	public static String format(WaterSettingsInfo.Info info, double value) {
		if (info.kind() == WaterSettingsInfo.Kind.INTEGER) {
			return String.valueOf(Math.round(value));
		}
		int decimals = info.step() > 0 ? Math.clamp((int) Math.ceil(-Math.log10(info.step()) - 1e-9), 0, 6) : 3;
		String out = String.format(Locale.ROOT, "%." + decimals + "f", value);
		if (out.contains(".")) {
			out = out.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return out;
	}

	private static final class SettingSlider extends AbstractSliderButton {
		private final WaterSettingsInfo.Info info;

		SettingSlider(WaterSettingsInfo.Info info) {
			super(0, 0, 150, 20, Component.empty(), position(info));
			this.info = info;
			this.updateMessage();
		}

		private static double position(WaterSettingsInfo.Info info) {
			double span = info.max() - info.min();
			if (span <= 0.0) {
				return 0.0;
			}
			try {
				return Math.clamp((Double.parseDouble(value(info.name())) - info.min()) / span, 0.0, 1.0);
			} catch (NumberFormatException e) {
				return 0.0;
			}
		}

		private double picked() {
			double v = this.info.min() + this.value * (this.info.max() - this.info.min());
			if (this.info.step() > 0.0) {
				v = this.info.min() + Math.round((v - this.info.min()) / this.info.step()) * this.info.step();
			}
			return Math.clamp(v, this.info.min(), this.info.max());
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.literal(this.info.label() + ": " + value(this.info.name())));
		}

		@Override
		protected void applyValue() {
			set(this.info.name(), format(this.info, this.picked()));
		}
	}
}
