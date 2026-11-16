package me.skaffy.liquid;

import java.util.EnumMap;
import java.util.Map;

public enum LiquidType {
	WATER("Thin liquid", "Runs, splashes and foams like water.", 0x3C8CE6),
	OIL("Oil", "Thin but lazy: spreads wide, settles quickly, no foam.", 0xC9A227),
	SYRUP("Syrup", "Thick and slow, pours in ropes, always levels out in the end.", 0xD98A1F),
	SLIME("Slime", "Thick, sticky, and keeps its shape once it rests.", 0x6FCB5A),
	MOLTEN("Molten", "Heavy, slow and glowing, like lava. Burns.", 0xFF6A14),
	METAL("Liquid metal", "Very heavy, very shiny, beads up and never foams.", 0xC4CAD2),
	FLOATY("Floaty", "Weak gravity: big slow blobs and lazy splashes.", 0xB06CF0),
	TAR("Tar", "Almost solid. Crawls, sticks to everything, traps whatever walks in.", 0x1E1A17);

	public static final LiquidType[] ALL = values();
	static final String[] NAMES = names();

	public final String label;
	public final String blurb;
	public final int color;

	LiquidType(String label, String blurb, int color) {
		this.label = label;
		this.blurb = blurb;
		this.color = color;
	}

	private static String[] names() {
		String[] out = new String[values().length];
		for (LiquidType t : values()) {
			out[t.ordinal()] = t.label;
		}
		return out;
	}

	public static LiquidType of(float value) {
		return ALL[Math.min(Math.max(Math.round(value), 0), ALL.length - 1)];
	}

	public float[] defaults() {
		Map<LiquidParam, Float> overrides = Presets.OVERRIDES.get(this);
		float[] out = new float[LiquidParam.ALL.length];
		for (LiquidParam p : LiquidParam.ALL) {
			Float o = overrides.get(p);
			out[p.ordinal()] = p.clean(o != null ? o : p.fallback);
		}
		out[LiquidParam.TYPE.ordinal()] = this.ordinal();
		out[LiquidParam.RED.ordinal()] = (this.color >> 16) & 0xFF;
		out[LiquidParam.GREEN.ordinal()] = (this.color >> 8) & 0xFF;
		out[LiquidParam.BLUE.ordinal()] = this.color & 0xFF;
		return out;
	}

	private static final class Presets {
		static final Map<LiquidType, Map<LiquidParam, Float>> OVERRIDES = new EnumMap<>(LiquidType.class);

		private static final class Build {
			private final Map<LiquidParam, Float> map = new EnumMap<>(LiquidParam.class);

			Build set(LiquidParam p, double v) {
				this.map.put(p, (float) v);
				return this;
			}

			Build flag(LiquidParam p, boolean on) {
				return this.set(p, on ? 1.0 : 0.0);
			}
		}

		private static Build of(LiquidType type) {
			Build b = new Build();
			OVERRIDES.put(type, b.map);
			return b;
		}

		static {
			of(WATER);
			of(OIL).set(LiquidParam.SPLASH, 0.35).set(LiquidParam.FRICTION, 12).set(LiquidParam.TOP_SPEED, 18).set(LiquidParam.PUDDLE_LIFE, 12)
				.set(LiquidParam.OPACITY, 0.55).set(LiquidParam.SHINE, 1.6).set(LiquidParam.ROUGHNESS, 0.04).set(LiquidParam.RIPPLES, 0.15)
				.set(LiquidParam.SMOOTHING, 0.7).flag(LiquidParam.FOAM, false).set(LiquidParam.DRAG, 0.1).flag(LiquidParam.EXTINGUISHES, false);
			of(SYRUP).set(LiquidParam.VISCOSITY, 1.2).set(LiquidParam.STICKINESS, 4).set(LiquidParam.CLING, 0.35).set(LiquidParam.TOP_SPEED, 6)
				.set(LiquidParam.SPLASH, 0.2).set(LiquidParam.FRICTION, 90).set(LiquidParam.PUDDLE_LIFE, 20).set(LiquidParam.DETAIL, 4)
				.set(LiquidParam.OPACITY, 0.5).set(LiquidParam.SHINE, 1.4).set(LiquidParam.ROUGHNESS, 0.05).set(LiquidParam.RIPPLES, 0.0)
				.set(LiquidParam.SMOOTHING, 0.8).flag(LiquidParam.FOAM, false).set(LiquidParam.DRAG, 0.45);
			of(SLIME).set(LiquidParam.LOOK, 1).set(LiquidParam.VISCOSITY, 1.5).set(LiquidParam.STICKINESS, 18).set(LiquidParam.CLING, 0.85)
				.set(LiquidParam.HOLD, 0.6).set(LiquidParam.TOP_SPEED, 3).set(LiquidParam.SPLASH, 0.1).set(LiquidParam.FRICTION, 220)
				.set(LiquidParam.PUDDLE_LIFE, 8).set(LiquidParam.DETAIL, 4).set(LiquidParam.OPACITY, 0.6).set(LiquidParam.SHINE, 1.0)
				.set(LiquidParam.RIPPLES, 0.0).set(LiquidParam.SMOOTHING, 0.85).flag(LiquidParam.FOAM, false).set(LiquidParam.DRAG, 0.6)
				.flag(LiquidParam.EXTINGUISHES, false);
			of(MOLTEN).set(LiquidParam.LOOK, 2).set(LiquidParam.VISCOSITY, 0.6).set(LiquidParam.TOP_SPEED, 5).set(LiquidParam.SPLASH, 0.45)
				.set(LiquidParam.FRICTION, 140).set(LiquidParam.PUDDLE_LIFE, 5).set(LiquidParam.DETAIL, 4).set(LiquidParam.OPACITY, 0.9)
				.set(LiquidParam.GLOW, 1.0).set(LiquidParam.SHINE, 2.5).set(LiquidParam.ROUGHNESS, 0.35).set(LiquidParam.RIPPLES, 0.25)
				.set(LiquidParam.SMOOTHING, 0.75).flag(LiquidParam.FOAM, false).set(LiquidParam.DRAG, 0.5).set(LiquidParam.DAMAGE, 8)
				.flag(LiquidParam.BURNS, true).flag(LiquidParam.EXTINGUISHES, false);
			of(METAL).set(LiquidParam.GRAVITY, 1.8).set(LiquidParam.SPLASH, 0.6).set(LiquidParam.FRICTION, 4).set(LiquidParam.TOP_SPEED, 30)
				.set(LiquidParam.PUDDLE_LIFE, 30).set(LiquidParam.OPACITY, 1.0).set(LiquidParam.SHINE, 3.0).set(LiquidParam.ROUGHNESS, 0.03)
				.set(LiquidParam.RIPPLES, 0.05).set(LiquidParam.REFRACTION, 0.0).set(LiquidParam.SMOOTHING, 0.8).flag(LiquidParam.FOAM, false)
				.set(LiquidParam.DRAG, 0.3).flag(LiquidParam.EXTINGUISHES, false);
			of(FLOATY).set(LiquidParam.GRAVITY, 0.25).set(LiquidParam.SPLASH, 0.95).set(LiquidParam.TOP_SPEED, 12).set(LiquidParam.FRICTION, 10)
				.set(LiquidParam.PUDDLE_LIFE, 15).set(LiquidParam.OPACITY, 0.3).set(LiquidParam.GLOW, 0.35).set(LiquidParam.SMOOTHING, 0.7);
			of(TAR).set(LiquidParam.LOOK, 1).set(LiquidParam.VISCOSITY, 6).set(LiquidParam.STICKINESS, 30).set(LiquidParam.CLING, 1.0)
				.set(LiquidParam.HOLD, 0.3).set(LiquidParam.TOP_SPEED, 1.5).set(LiquidParam.SPLASH, 0.0).set(LiquidParam.FRICTION, 400)
				.set(LiquidParam.PUDDLE_LIFE, 60).set(LiquidParam.DETAIL, 4).set(LiquidParam.OPACITY, 1.0).set(LiquidParam.SHINE, 0.6)
				.set(LiquidParam.ROUGHNESS, 0.2).set(LiquidParam.RIPPLES, 0.0).set(LiquidParam.SMOOTHING, 0.9).flag(LiquidParam.FOAM, false)
				.set(LiquidParam.DRAG, 0.95).flag(LiquidParam.EXTINGUISHES, false);
		}
	}
}
