package me.skaffy.client.liquid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import me.skaffy.liquid.CustomLiquid;
import me.skaffy.liquid.CustomLiquids;
import me.skaffy.liquid.LiquidParam;
import me.skaffy.liquid.LiquidType;
import me.skaffy.net.LiquidEditPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.Permissions;
import org.jspecify.annotations.Nullable;

public class LiquidEditorScreen extends OptionsSubScreen {
	private static final int NEW = -1;
	private static boolean showAll;
	private static boolean openPending;

	private static final class State {
		final Supplier<@Nullable Screen> back;
		CustomLiquid draft;
		int editing;
		boolean dirty;
		@Nullable Set<Integer> slotsBeforeSave;
		@Nullable String savingName;
		double scroll;

		State(Supplier<@Nullable Screen> back) {
			this.back = back;
		}
	}

	private final State state;

	public LiquidEditorScreen(Supplier<@Nullable Screen> back) {
		this(newState(back));
	}

	private LiquidEditorScreen(State state) {
		super(null, Minecraft.getInstance().options, Component.literal("Custom Liquids"));
		this.state = state;
	}

	private static State newState(Supplier<@Nullable Screen> back) {
		State s = new State(back);
		List<CustomLiquid> known = CustomLiquids.clientList();
		if (known.isEmpty()) {
			startNew(s, LiquidType.WATER);
		} else {
			s.draft = known.getFirst();
			s.editing = s.draft.slot();
		}
		return s;
	}

	private static void startNew(State s, LiquidType type) {
		s.draft = CustomLiquid.of(0, "My " + type.label.toLowerCase(Locale.ROOT), type);
		s.editing = NEW;
		s.dirty = true;
	}

	public static void openNextTick() {
		openPending = true;
	}

	public static void tick(Minecraft mc) {
		if (openPending) {
			openPending = false;
			mc.gui.setScreen(new LiquidEditorScreen(() -> null));
		}
	}

	private static boolean canEdit() {
		Minecraft mc = Minecraft.getInstance();
		return mc.hasSingleplayerServer() || mc.player != null && mc.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	private void reopen() {
		if (this.list != null) {
			this.state.scroll = this.list.scrollAmount();
		}
		this.minecraft.gui.setScreen(new LiquidEditorScreen(this.state));
	}

	private void unlessUnsaved(String doing, Runnable then) {
		if (!this.state.dirty) {
			then.run();
			return;
		}
		State s = this.state;
		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				s.dirty = false;
				then.run();
			} else {
				this.minecraft.gui.setScreen(new LiquidEditorScreen(s));
			}
		}, Component.literal("Discard your changes?"),
			Component.literal("\"" + s.draft.name() + "\" has changes that aren't saved. " + doing + " anyway?"),
			Component.literal("Discard"), Component.literal("Keep editing")));
	}

	public void definitionsChanged() {
		State s = this.state;
		if (s.slotsBeforeSave != null) {
			for (CustomLiquid liquid : CustomLiquids.clientList()) {
				if (!s.slotsBeforeSave.contains(liquid.slot())) {
					s.draft = liquid;
					s.editing = liquid.slot();
					s.dirty = false;
					break;
				}
			}
			s.slotsBeforeSave = null;
		} else if (s.editing != NEW) {
			CustomLiquid saved = CustomLiquids.client(s.editing);
			if (saved == null) {
				List<CustomLiquid> known = CustomLiquids.clientList();
				if (known.isEmpty()) {
					startNew(s, LiquidType.WATER);
				} else {
					s.draft = known.getFirst();
					s.editing = s.draft.slot();
					s.dirty = false;
				}
			} else if (!s.dirty || saved.equals(s.draft)) {
				s.draft = saved;
				s.dirty = false;
			}
		}
		if (s.savingName != null) {
			SystemToast.addOrUpdate(this.minecraft.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.literal("Liquid saved"), Component.literal(s.savingName));
			s.savingName = null;
		}
		this.reopen();
	}

	@Override
	protected void addOptions() {
		State s = this.state;
		this.list.addHeader(Component.literal("Your liquids").withStyle(ChatFormatting.YELLOW));
		List<AbstractWidget> saved = new ArrayList<>();
		for (CustomLiquid liquid : CustomLiquids.clientList()) {
			int slot = liquid.slot();
			Button pick = Button.builder(this.pickerLabel(slot), button -> this.pick(slot)).width(150).build();
			pick.setTooltip(Tooltip.create(Component.literal(liquid.name()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
				.append(Component.literal("\n" + liquid.type().label).withStyle(ChatFormatting.GRAY))
				.append(Component.literal(slot == s.editing ? "\nYou are editing this one." : "\nClick to edit it.").withStyle(ChatFormatting.DARK_GRAY))));
			saved.add(pick);
		}
		Button fresh = Button.builder(this.pickerLabel(NEW), button -> this.pick(NEW)).width(150).build();
		fresh.setTooltip(Tooltip.create(Component.literal(s.editing == NEW ? "You are making a new liquid now. Click Create to save it."
			: "Start a new liquid from scratch.")));
		saved.add(fresh);
		this.list.addSmall(saved);
		this.list.addHeader(Component.literal(s.editing == NEW ? "New liquid" : "Edit \"" + s.draft.name() + "\"").withStyle(ChatFormatting.YELLOW));

		EditBox name = new EditBox(this.font, 310, 20, Component.literal("Name"));
		name.setMaxLength(CustomLiquid.MAX_NAME);
		name.setValue(s.draft.name());
		name.setTextColor(0xFF000000 | brighten(s.draft.color()));
		name.setHint(Component.literal("Name your liquid").withStyle(ChatFormatting.DARK_GRAY));
		name.setResponder(text -> {
			if (!text.equals(s.draft.name())) {
				s.draft = s.draft.withName(text);
				s.dirty = true;
			}
		});
		name.setTooltip(Tooltip.create(Component.literal("The liquid's name, shown on its bucket.")));
		this.list.addBig(name);

		CycleButton<LiquidType> type = CycleButton.<LiquidType>builder(t -> Component.literal(t.label), s.draft.type())
			.withValues(LiquidType.ALL)
			.withTooltip(t -> Tooltip.create(Component.literal(t.blurb + "\n\n").append(Component.literal(
				"Click to try another kind of liquid. This sets how it flows, looks and feels in one go; the colour and name stay yours.")
				.withStyle(ChatFormatting.GRAY))))
			.create(Component.literal(LiquidParam.TYPE.label), (button, picked) -> {
				s.draft = s.draft.withType(picked);
				s.dirty = true;
				this.reopen();
			});
		this.list.addBig(type);

		Button more = Button.builder(Component.literal(showAll ? "Show fewer settings" : "Show all settings..."), button -> {
			showAll = !showAll;
			s.scroll = 0.0;
			this.minecraft.gui.setScreen(new LiquidEditorScreen(s));
		}).width(310).build();
		more.setTooltip(Tooltip.create(Component.literal(showAll
			? "Back to the short list."
			: "Every setting: gravity, splashiness, stickiness, ripples, reflections and more. You don't need these: \"Start from\" already picks good values.")));
		this.list.addBig(more);

		if (!showAll) {
			this.list.addSmall(this.colourWidgets());
			List<AbstractWidget> simple = new ArrayList<>();
			for (LiquidParam p : SIMPLE) {
				simple.add(this.widget(p));
			}
			this.list.addSmall(simple);
		} else {
			for (LiquidParam.Group group : LiquidParam.Group.values()) {
				this.list.addHeader(Component.literal(group.title).withStyle(ChatFormatting.YELLOW));
				List<AbstractWidget> widgets = new ArrayList<>();
				if (group == LiquidParam.Group.LOOK) {
					widgets.addAll(this.colourWidgets());
				}
				for (LiquidParam p : LiquidParam.ALL) {
					if (p.group == group && p != LiquidParam.TYPE && !COLOUR_CONTROLS.contains(p) && this.relevant(p)) {
						widgets.add(this.widget(p));
					}
				}
				this.list.addSmall(widgets);
			}
		}
		this.list.setScrollAmount(s.scroll);
	}

	private void pick(int slot) {
		State s = this.state;
		if (slot == s.editing) {
			this.reopen();
			return;
		}
		this.unlessUnsaved(slot == NEW ? "Start a new liquid" : "Switch liquids", () -> {
			if (slot == NEW) {
				startNew(s, s.draft.type());
			} else {
				CustomLiquid liquid = CustomLiquids.client(slot);
				if (liquid != null) {
					s.draft = liquid;
					s.editing = slot;
					s.dirty = false;
				}
			}
			s.scroll = 0.0;
			this.minecraft.gui.setScreen(new LiquidEditorScreen(s));
		});
	}

	private Component pickerLabel(int slot) {
		boolean current = slot == this.state.editing;
		if (slot == NEW) {
			return Component.literal(current ? "> + New liquid <" : "+ New liquid").withStyle(ChatFormatting.GREEN);
		}
		CustomLiquid liquid = CustomLiquids.client(slot);
		String label = liquid != null ? liquid.name() : "?";
		if (current && this.state.dirty) {
			label += " *";
		}
		MutableComponent text = Component.literal(current ? "> " + label + " <" : label).withColor(brighten(liquid != null ? liquid.color() : 0xFFFFFF));
		return current ? text.withStyle(ChatFormatting.BOLD) : text;
	}

	private static final LiquidParam[] SIMPLE = {
		LiquidParam.OPACITY, LiquidParam.VISCOSITY, LiquidParam.LOOK, LiquidParam.GLOW, LiquidParam.DAMAGE, LiquidParam.BURNS, LiquidParam.DRAG
	};

	private record Swatch(String name, int rgb) {
	}

	private static final Swatch[] SWATCHES = {
		new Swatch("Blue", 0x3C8CE6), new Swatch("Light blue", 0x7FC8F8), new Swatch("Cyan", 0x22C5C5), new Swatch("Green", 0x4FBF45),
		new Swatch("Lime", 0x9BE33C), new Swatch("Yellow", 0xF2D23A), new Swatch("Gold", 0xD9A11F), new Swatch("Orange", 0xFF7A1A),
		new Swatch("Red", 0xD83030), new Swatch("Pink", 0xF27BB5), new Swatch("Magenta", 0xC84FD8), new Swatch("Purple", 0x8A4FE0),
		new Swatch("Brown", 0x7A4A28), new Swatch("White", 0xF2F2F2), new Swatch("Silver", 0xC4CAD2), new Swatch("Gray", 0x6F6F6F),
		new Swatch("Black", 0x1E1A17)
	};

	private static final List<LiquidParam> COLOUR_CONTROLS = List.of(LiquidParam.COLOR_MODE, LiquidParam.PATTERN, LiquidParam.STRIPE_SIZE,
		LiquidParam.COLOR_SPEED);

	private boolean relevant(LiquidParam p) {
		int style = this.state.draft.choice(LiquidParam.COLOR_MODE);
		if (p == LiquidParam.RED2 || p == LiquidParam.GREEN2 || p == LiquidParam.BLUE2) {
			return style == CustomLiquid.COLOURS_GRADIENT;
		}
		if (p == LiquidParam.PATTERN || p == LiquidParam.STRIPE_SIZE || p == LiquidParam.COLOR_SPEED) {
			return style != CustomLiquid.COLOURS_ONE;
		}
		if (p == LiquidParam.RED || p == LiquidParam.GREEN || p == LiquidParam.BLUE) {
			return style != CustomLiquid.COLOURS_RAINBOW;
		}
		return true;
	}

	private List<AbstractWidget> colourWidgets() {
		int style = this.state.draft.choice(LiquidParam.COLOR_MODE);
		List<AbstractWidget> out = new ArrayList<>();
		if (style != CustomLiquid.COLOURS_RAINBOW) {
			String label = style == CustomLiquid.COLOURS_GRADIENT ? "1st colour" : "Colour";
			out.add(this.colourPicker(LiquidParam.RED, LiquidParam.GREEN, LiquidParam.BLUE, label));
			out.add(this.hexBox(LiquidParam.RED, LiquidParam.GREEN, LiquidParam.BLUE, label));
		}
		out.add(this.widget(LiquidParam.COLOR_MODE));
		if (style != CustomLiquid.COLOURS_ONE) {
			out.add(this.widget(LiquidParam.COLOR_SPEED));
		}
		if (style == CustomLiquid.COLOURS_GRADIENT) {
			out.add(this.colourPicker(LiquidParam.RED2, LiquidParam.GREEN2, LiquidParam.BLUE2, "2nd colour"));
			out.add(this.hexBox(LiquidParam.RED2, LiquidParam.GREEN2, LiquidParam.BLUE2, "2nd colour"));
		}
		if (style != CustomLiquid.COLOURS_ONE) {
			out.add(this.widget(LiquidParam.PATTERN));
			out.add(this.widget(LiquidParam.STRIPE_SIZE));
		}
		return out;
	}

	private AbstractWidget hexBox(LiquidParam red, LiquidParam green, LiquidParam blue, String label) {
		State s = this.state;
		int current = Math.round(s.draft.get(red)) << 16 | Math.round(s.draft.get(green)) << 8 | Math.round(s.draft.get(blue));
		EditBox box = new EditBox(this.font, 150, 20, Component.literal(label + " hex"));
		box.setMaxLength(7);
		box.setValue(String.format(Locale.ROOT, "#%06X", current));
		box.setTextColor(0xFF000000 | brighten(current));
		box.setHint(Component.literal("#RRGGBB").withStyle(ChatFormatting.DARK_GRAY));
		box.setResponder(text -> {
			int rgb = parseHex(text);
			if (rgb < 0) {
				box.setTextColor(0xFFFF5555);
				return;
			}
			box.setTextColor(0xFF000000 | brighten(rgb));
			int now = Math.round(s.draft.get(red)) << 16 | Math.round(s.draft.get(green)) << 8 | Math.round(s.draft.get(blue));
			if (rgb != now) {
				s.draft = s.draft.with(red, (rgb >> 16) & 0xFF).with(green, (rgb >> 8) & 0xFF).with(blue, rgb & 0xFF);
				s.dirty = true;
			}
		});
		box.setTooltip(Tooltip.create(Component.literal(label + " as a hex code").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
			.append(Component.literal("\nType any colour, like #FF8800 (orange) or #8A2BE2 (violet). The short form #F80 works too. "
				+ "It turns red while the code isn't complete.").withStyle(ChatFormatting.GRAY))));
		return box;
	}

	static int parseHex(String text) {
		String t = text.strip();
		if (t.startsWith("#")) {
			t = t.substring(1);
		}
		if (t.length() == 3) {
			t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
		}
		if (t.length() != 6) {
			return -1;
		}
		try {
			return Integer.parseInt(t, 16);
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private AbstractWidget colourPicker(LiquidParam red, LiquidParam green, LiquidParam blue, String label) {
		State s = this.state;
		int current = Math.round(s.draft.get(red)) << 16 | Math.round(s.draft.get(green)) << 8 | Math.round(s.draft.get(blue));
		List<Integer> options = new ArrayList<>();
		int selected = -1;
		for (int k = 0; k < SWATCHES.length; k++) {
			options.add(k);
			if (SWATCHES[k].rgb == current) {
				selected = k;
			}
		}
		if (selected < 0) {
			options.addFirst(-1);
		}
		CycleButton<Integer> picker = CycleButton.<Integer>builder(k -> k < 0
				? Component.literal("Custom").withColor(brighten(current))
				: Component.literal(SWATCHES[k].name).withColor(brighten(SWATCHES[k].rgb)), selected)
			.withValues(options)
			.create(Component.literal(label), (button, k) -> {
				if (k >= 0) {
					int rgb = SWATCHES[k].rgb;
					s.draft = s.draft.with(red, (rgb >> 16) & 0xFF).with(green, (rgb >> 8) & 0xFF).with(blue, rgb & 0xFF);
					s.dirty = true;
				}
				this.reopen();
			});
		picker.setTooltip(Tooltip.create(Component.literal("Click for the next colour, shift-click for the previous one. "
			+ "For any other colour, type its hex code (like #FF8800) in the box beside this.")));
		return picker;
	}

	private static int brighten(int rgb) {
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		if (max >= 110) {
			return rgb;
		}
		if (max == 0) {
			return 0x6E6E6E;
		}
		float k = 110.0F / max;
		return Math.round(r * k) << 16 | Math.round(g * k) << 8 | Math.round(b * k);
	}

	private AbstractWidget widget(LiquidParam p) {
		State s = this.state;
		AbstractWidget widget = switch (p.kind) {
			case TOGGLE -> CycleButton.onOffBuilder(s.draft.on(p)).create(Component.literal(p.label), (button, on) -> {
				s.draft = s.draft.with(p, on ? 1.0F : 0.0F);
				s.dirty = true;
			});
			case CHOICE -> {
				List<Integer> options = new ArrayList<>();
				for (int k = 0; k < p.choices.length; k++) {
					options.add(k);
				}
				yield CycleButton.<Integer>builder(k -> Component.literal(p.choices[k]), Math.min(Math.max(s.draft.choice(p), 0), p.choices.length - 1))
					.withValues(options)
					.create(Component.literal(p.label), (button, k) -> {
						s.draft = s.draft.with(p, k);
						s.dirty = true;
						if (p == LiquidParam.COLOR_MODE) {
							this.reopen();
						}
					});
			}
			case SLIDER -> new ParamSlider(s, p);
		};
		widget.setTooltip(Tooltip.create(tooltip(s, p)));
		return widget;
	}

	private static Component tooltip(State s, LiquidParam p) {
		MutableComponent out = Component.literal(p.label).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
		out.append(Component.literal("\n" + p.tip).withStyle(ChatFormatting.GRAY));
		float typeValue = s.draft.type().defaults()[p.ordinal()];
		out.append(Component.literal("\n\"" + s.draft.type().label + "\" uses " + p.format(typeValue)).withStyle(ChatFormatting.DARK_GRAY));
		return out;
	}

	@Override
	protected void addFooter() {
		State s = this.state;
		boolean allowed = canEdit();
		boolean saved = s.editing != NEW;
		LinearLayout buttons = this.layout.addToFooter(LinearLayout.horizontal().spacing(4));
		Button save = Button.builder(Component.literal(saved ? "Save" : "Create"), button -> this.save()).width(60).build();
		Button bucket = Button.builder(Component.literal("Bucket"), button -> this.giveBucket(false)).width(60).build();
		Button infinite = Button.builder(Component.literal("Infinite"), button -> this.giveBucket(true)).width(60).build();
		Button delete = Button.builder(Component.literal("Delete"), button -> this.confirmDelete()).width(60).build();
		bucket.active = allowed && saved;
		infinite.active = allowed && saved;
		delete.active = allowed && saved;
		save.active = allowed;
		if (!allowed) {
			Tooltip locked = Tooltip.create(Component.literal("Only the world's owner or an operator can change liquids.").withStyle(ChatFormatting.YELLOW));
			save.setTooltip(locked);
			bucket.setTooltip(locked);
			infinite.setTooltip(locked);
			delete.setTooltip(locked);
		} else {
			save.setTooltip(Tooltip.create(Component.literal(saved
				? "Applies your changes for everyone. Liquid already poured changes too."
				: "Adds this liquid to the world. Then click Bucket or Infinite to get some.")));
			bucket.setTooltip(Tooltip.create(Component.literal(saved
				? "Saves your changes and gives you a bucket of this liquid. It pours one block."
				: "Click Create first.")));
			infinite.setTooltip(Tooltip.create(Component.literal(saved
				? "Saves your changes and gives you an infinite bucket. It places a spring that keeps pouring this liquid forever; "
					+ "scoop the spring up with an empty bucket to get the infinite bucket back, or remove it with a Special Sponge."
				: "Click Create first.")));
			delete.setTooltip(Tooltip.create(Component.literal(saved
				? "Removes this liquid from the world. Any already poured becomes a magenta \"Unknown liquid\" you can scoop up."
				: "This liquid isn't created yet.")));
		}
		buttons.addChild(save);
		buttons.addChild(bucket);
		buttons.addChild(infinite);
		buttons.addChild(delete);
		buttons.addChild(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(60).build());
	}

	private void giveBucket(boolean infinite) {
		State s = this.state;
		if (s.dirty) {
			this.save();
		}
		send(infinite ? LiquidEditPayload.INFINITE_BUCKET : LiquidEditPayload.BUCKET, s.draft.withSlot(s.editing), false);
	}

	private void save() {
		State s = this.state;
		s.savingName = s.draft.name();
		if (s.editing == NEW) {
			s.slotsBeforeSave = new HashSet<>();
			for (CustomLiquid liquid : CustomLiquids.clientList()) {
				s.slotsBeforeSave.add(liquid.slot());
			}
			send(LiquidEditPayload.SAVE, s.draft, true);
		} else {
			send(LiquidEditPayload.SAVE, s.draft.withSlot(s.editing), false);
		}
	}

	private void confirmDelete() {
		State s = this.state;
		CustomLiquid target = s.draft.withSlot(s.editing);
		this.minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				s.dirty = false;
				send(LiquidEditPayload.DELETE, target, false);
			}
			this.minecraft.gui.setScreen(new LiquidEditorScreen(s));
		}, Component.literal("Delete \"" + target.name() + "\"?"),
			Component.literal("Any of it already poured becomes an unknown liquid. This can't be undone."),
			Component.literal("Delete"), CommonComponents.GUI_CANCEL));
	}

	private static void send(int action, CustomLiquid liquid, boolean newSlot) {
		if (ClientPlayNetworking.canSend(LiquidEditPayload.TYPE)) {
			ClientPlayNetworking.send(new LiquidEditPayload(action, liquid, newSlot));
		}
	}

	@Override
	public void removed() {
	}

	@Override
	public void onClose() {
		this.unlessUnsaved("Close the editor", () -> this.minecraft.gui.setScreen(this.state.back.get()));
	}

	private static final class ParamSlider extends AbstractSliderButton {
		private final State state;
		private final LiquidParam param;

		ParamSlider(State state, LiquidParam param) {
			super(0, 0, 150, 20, Component.empty(), position(param, state.draft.get(param)));
			this.state = state;
			this.param = param;
			this.updateMessage();
		}

		private static double position(LiquidParam p, float value) {
			float span = p.max - p.min;
			return span <= 0.0F ? 0.0 : Math.clamp((value - p.min) / span, 0.0, 1.0);
		}

		private float picked() {
			return this.param.clean(this.param.min + (float) this.value * (this.param.max - this.param.min));
		}

		@Override
		protected void updateMessage() {
			this.setMessage(Component.literal(this.param.label + ": " + this.param.format(this.picked())));
		}

		@Override
		protected void applyValue() {
			float v = this.picked();
			if (v != this.state.draft.get(this.param)) {
				this.state.draft = this.state.draft.with(this.param, v);
				this.state.dirty = true;
			}
		}
	}
}
