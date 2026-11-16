package me.skaffy.liquid;

public enum LiquidParam {
	TYPE("type", "Start from", Group.PHYSICS, 0, LiquidType.NAMES,
		"The family this liquid starts from. Picking one fills in every physics and look setting below with that family's "
			+ "values; change any of them afterwards. It also decides nothing by itself: two types with the same sliders behave the same."),
	GRAVITY("gravity", "Gravity", Group.PHYSICS, 1.0F, 0.05F, 3.0F, 0.05F,
		"How hard it falls compared with realistic water. Low values drift like something in low gravity; high values slam down."),
	TOP_SPEED("top_speed", "Max flow speed", Group.PHYSICS, 40.0F, 0.5F, 40.0F, 0.5F,
		"The fastest it may ever move, in blocks per second. Water is 40, lava 5, slime 3. Low values make it creep."),
	SPLASH("splash", "Splashiness", Group.PHYSICS, 0.9F, 0.0F, 1.0F, 0.01F,
		"How long it keeps sloshing. 1 keeps every wave and splash going like water; 0 calms down at once like a heavy oil."),
	VISCOSITY("viscosity", "Thickness", Group.PHYSICS, 0.0F, 0.0F, 10.0F, 0.05F,
		"How thick it is. 0 is water, about 1.5 is slime, 6 and up is tar. Anything above 0 costs an extra solver pass each step."),
	STICKINESS("stickiness", "Wall stickiness", Group.PHYSICS, 0.0F, 0.0F, 40.0F, 0.5F,
		"How strongly it slows down against blocks it touches, so it clings to walls and ceilings instead of running off. Slime is 18."),
	CLING("cling", "Clings to things", Group.PHYSICS, 0.0F, 0.0F, 1.0F, 0.01F,
		"How much it is dragged along by players, mobs and moving blocks that touch it. 0 slides off, 1 moves with them."),
	HOLD("hold", "Keeps its shape", Group.PHYSICS, 0.0F, 0.0F, 3.0F, 0.05F,
		"Below this speed (blocks per second) a resting blob stops flowing and keeps its shape until something disturbs it. "
			+ "0 always levels out like a liquid; slime is 0.6."),
	FRICTION("friction", "Ground friction", Group.PHYSICS, 30.0F, 0.0F, 400.0F, 1.0F,
		"How much thin puddles are slowed by the ground under them. Low values spread into a wide film; high values stay in a thick pool."),
	PUDDLE_LIFE("puddle_life", "Stray drops last", Group.PHYSICS, 3.0F, 0.5F, 60.0F, 0.5F,
		"How many seconds a lone drop or thin splash lasts before it dries up. Long lives leave more lingering droplets to simulate."),
	DETAIL("detail", "Detail (costly)", Group.PHYSICS, 6.0F, 2.0F, 12.0F, 1.0F,
		"Simulation cells per block for this liquid. Higher looks finer and costs much more (8 times the work for double the "
			+ "detail). Thick liquids look fine at 4."),

	LOOK("look", "Looks like", Group.LOOK, 0, new String[]{"Clear liquid", "Gel", "Molten"},
		"How it is drawn. Clear liquid is see-through with reflections like water. Gel is a soft, glossy jelly. "
			+ "Molten glows by itself like lava."),
	RED("red", "Red", Group.LOOK, 60.0F, 0.0F, 255.0F, 1.0F, "Red part of the liquid's colour."),
	GREEN("green", "Green", Group.LOOK, 140.0F, 0.0F, 255.0F, 1.0F, "Green part of the liquid's colour."),
	BLUE("blue", "Blue", Group.LOOK, 230.0F, 0.0F, 255.0F, 1.0F, "Blue part of the liquid's colour."),
	COLOR_MODE("color_mode", "Colour style", Group.LOOK, 0, new String[]{"One colour", "Gradient", "Rainbow"},
		"One colour: the whole liquid is one colour. Gradient: it fades between two colours in stripes. Rainbow: stripes of "
			+ "every colour. Set Colour movement above 0 to make the stripes flow."),
	RED2("red2", "Red (2nd)", Group.LOOK, 240.0F, 0.0F, 255.0F, 1.0F, "Red part of the gradient's second colour."),
	GREEN2("green2", "Green (2nd)", Group.LOOK, 120.0F, 0.0F, 255.0F, 1.0F, "Green part of the gradient's second colour."),
	BLUE2("blue2", "Blue (2nd)", Group.LOOK, 200.0F, 0.0F, 255.0F, 1.0F, "Blue part of the gradient's second colour."),
	PATTERN("pattern", "Stripe direction", Group.LOOK, 0, new String[]{"Up and down", "Sideways", "Diagonal"},
		"Which way the colour stripes run. Up and down changes colour with height; Sideways across the ground; Diagonal both."),
	STRIPE_SIZE("stripe_size", "Stripe size", Group.LOOK, 8.0F, 1.0F, 64.0F, 0.5F,
		"How many blocks it takes to go through all the colours once. Small values make thin stripes."),
	COLOR_SPEED("color_speed", "Colour movement", Group.LOOK, 0.0F, 0.0F, 10.0F, 0.1F,
		"How fast the colours flow along the stripes, in blocks per second. 0 keeps them still."),
	OPACITY("opacity", "Colour strength", Group.LOOK, 0.35F, 0.0F, 1.0F, 0.01F,
		"How much you can see through it. 0 is as clear as glass, 1 is solid colour you cannot see through."),
	GLOW("glow", "Glow", Group.LOOK, 0.0F, 0.0F, 2.0F, 0.01F,
		"How much it shines by itself in the dark. This is only how it is drawn: it does not light up blocks around it."),
	SHINE("shine", "Shine", Group.LOOK, 1.0F, 0.0F, 3.0F, 0.05F, "How bright the sun's glint on the surface is."),
	ROUGHNESS("roughness", "Surface roughness", Group.LOOK, 0.06F, 0.01F, 1.0F, 0.01F,
		"How blurred the glint is. Low is a sharp mirror-like sparkle, high is a wide soft sheen."),
	RIPPLES("ripples", "Ripples", Group.LOOK, 0.5F, 0.0F, 1.0F, 0.01F, "How strong the fine ripples drawn on the surface are."),
	REFRACTION("refraction", "Refraction", Group.LOOK, 0.05F, 0.0F, 0.2F, 0.005F, "How much things seen through it are bent."),
	SMOOTHING("smoothing", "Surface smoothing", Group.LOOK, 0.5F, 0.0F, 1.0F, 0.01F,
		"How rounded the surface is. Low shows every drop, high melts it into one smooth skin."),
	FOAM("foam", "Foam and spray", Group.LOOK, true,
		"White foam, bubbles and spray where it churns. Only thin liquids make foam: it is off whenever Thickness, Wall "
			+ "stickiness or Holds its shape is above 0."),
	TEXTURE("texture", "Crust texture", Group.LOOK, true, "Molten look only: cooler dark crust and bright veins drifting on the surface."),

	DRAG("drag", "Slows you down", Group.EFFECTS, 0.0F, 0.0F, 1.0F, 0.01F,
		"How much players and mobs are slowed while inside it. 0 is like air, 0.6 is slime, 1 is close to stuck."),
	DAMAGE("damage", "Hurts you", Group.EFFECTS, 0.0F, 0.0F, 20.0F, 0.5F,
		"Damage per second to anything inside it, in half hearts. 0 is harmless."),
	BURNS("burns", "Sets on fire", Group.EFFECTS, false, "Sets players and mobs inside it on fire."),
	EXTINGUISHES("extinguishes", "Puts out fire", Group.EFFECTS, true, "Puts out players and mobs that are on fire.");

	public enum Group {
		PHYSICS("Physics"),
		LOOK("Look"),
		EFFECTS("Effects on players and mobs");

		public final String title;

		Group(String title) {
			this.title = title;
		}
	}

	public enum Kind {
		SLIDER,
		TOGGLE,
		CHOICE
	}

	public static final LiquidParam[] ALL = values();

	public final String id;
	public final String label;
	public final Group group;
	public final Kind kind;
	public final float fallback;
	public final float min;
	public final float max;
	public final float step;
	public final String[] choices;
	public final String tip;

	LiquidParam(String id, String label, Group group, float fallback, float min, float max, float step, String tip) {
		this(id, label, group, Kind.SLIDER, fallback, min, max, step, new String[0], tip);
	}

	LiquidParam(String id, String label, Group group, boolean fallback, String tip) {
		this(id, label, group, Kind.TOGGLE, fallback ? 1.0F : 0.0F, 0.0F, 1.0F, 1.0F, new String[0], tip);
	}

	LiquidParam(String id, String label, Group group, int fallback, String[] choices, String tip) {
		this(id, label, group, Kind.CHOICE, fallback, 0.0F, choices.length - 1, 1.0F, choices, tip);
	}

	LiquidParam(String id, String label, Group group, Kind kind, float fallback, float min, float max, float step, String[] choices, String tip) {
		this.id = id;
		this.label = label;
		this.group = group;
		this.kind = kind;
		this.fallback = fallback;
		this.min = min;
		this.max = max;
		this.step = step;
		this.choices = choices;
		this.tip = tip;
	}

	public float clean(float value) {
		if (!Float.isFinite(value)) {
			return this.fallback;
		}
		float v = Math.min(Math.max(value, this.min), this.max);
		if (this.step > 0.0F) {
			v = this.min + Math.round((v - this.min) / this.step) * this.step;
		}
		return Math.min(Math.max(v, this.min), this.max);
	}

	public boolean whole() {
		return this.kind != Kind.SLIDER || this.step >= 1.0F;
	}

	public String format(float value) {
		if (this.kind == Kind.TOGGLE) {
			return value >= 0.5F ? "On" : "Off";
		}
		if (this.kind == Kind.CHOICE) {
			int i = Math.min(Math.max(Math.round(value), 0), this.choices.length - 1);
			return this.choices[i];
		}
		if (this.step >= 1.0F) {
			return Integer.toString(Math.round(value));
		}
		return this.step >= 0.1F ? String.format(java.util.Locale.ROOT, "%.1f", value)
			: this.step >= 0.01F ? String.format(java.util.Locale.ROOT, "%.2f", value) : String.format(java.util.Locale.ROOT, "%.3f", value);
	}
}
