package me.skaffy.liquid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.FriendlyByteBuf;

public record CustomLiquid(int slot, String name, float[] values) {
	public static final int MAX_SLOTS = 64;
	public static final int MAX_NAME = 32;

	public static final Codec<CustomLiquid> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("slot").forGetter(CustomLiquid::slot),
		Codec.STRING.fieldOf("name").forGetter(CustomLiquid::name),
		Codec.unboundedMap(Codec.STRING, Codec.FLOAT).fieldOf("values").forGetter(CustomLiquid::valueMap)
	).apply(i, CustomLiquid::fromMap));

	public CustomLiquid {
		slot = Math.min(Math.max(slot, 0), MAX_SLOTS - 1);
		name = cleanName(name);
		float[] v = new float[LiquidParam.ALL.length];
		for (LiquidParam p : LiquidParam.ALL) {
			v[p.ordinal()] = p.clean(p.ordinal() < values.length ? values[p.ordinal()] : p.fallback);
		}
		values = v;
	}

	public static CustomLiquid of(int slot, String name, LiquidType type) {
		return new CustomLiquid(slot, name, type.defaults());
	}

	public static CustomLiquid missing(int slot) {
		float[] v = LiquidType.WATER.defaults();
		v[LiquidParam.RED.ordinal()] = 200.0F;
		v[LiquidParam.GREEN.ordinal()] = 60.0F;
		v[LiquidParam.BLUE.ordinal()] = 200.0F;
		v[LiquidParam.OPACITY.ordinal()] = 0.7F;
		v[LiquidParam.FOAM.ordinal()] = 0.0F;
		return new CustomLiquid(slot, "Unknown liquid", v);
	}

	private static String cleanName(String name) {
		String n = name == null ? "" : name.strip();
		StringBuilder out = new StringBuilder();
		for (int k = 0; k < n.length() && out.length() < MAX_NAME; k++) {
			char c = n.charAt(k);
			if (c >= ' ' && c != 127 && c != '§') {
				out.append(c);
			}
		}
		return out.isEmpty() ? "Liquid" : out.toString();
	}

	private Map<String, Float> valueMap() {
		Map<String, Float> out = new LinkedHashMap<>();
		for (LiquidParam p : LiquidParam.ALL) {
			out.put(p.id, this.values[p.ordinal()]);
		}
		return out;
	}

	private static CustomLiquid fromMap(int slot, String name, Map<String, Float> saved) {
		Float type = saved.get(LiquidParam.TYPE.id);
		float[] v = LiquidType.of(type != null ? type : 0.0F).defaults();
		for (LiquidParam p : LiquidParam.ALL) {
			Float f = saved.get(p.id);
			if (f != null) {
				v[p.ordinal()] = f;
			}
		}
		return new CustomLiquid(slot, name, v);
	}

	public static void write(FriendlyByteBuf buf, CustomLiquid liquid) {
		buf.writeVarInt(liquid.slot);
		buf.writeUtf(liquid.name, MAX_NAME);
		buf.writeVarInt(liquid.values.length);
		for (float v : liquid.values) {
			buf.writeFloat(v);
		}
	}

	public static CustomLiquid read(FriendlyByteBuf buf) {
		int slot = buf.readVarInt();
		String name = buf.readUtf(MAX_NAME);
		int n = Math.min(buf.readVarInt(), 256);
		float[] v = new float[n];
		for (int k = 0; k < n; k++) {
			v[k] = buf.readFloat();
		}
		return new CustomLiquid(slot, name, v);
	}

	public float get(LiquidParam p) {
		return this.values[p.ordinal()];
	}

	public boolean on(LiquidParam p) {
		return this.values[p.ordinal()] >= 0.5F;
	}

	public int choice(LiquidParam p) {
		return Math.round(this.values[p.ordinal()]);
	}

	public CustomLiquid with(LiquidParam p, float value) {
		float[] v = this.values.clone();
		v[p.ordinal()] = value;
		return new CustomLiquid(this.slot, this.name, v);
	}

	public CustomLiquid withName(String name) {
		return new CustomLiquid(this.slot, name, this.values);
	}

	public CustomLiquid withSlot(int slot) {
		return new CustomLiquid(slot, this.name, this.values);
	}

	public static final LiquidParam[] COLOUR_PARAMS = {
		LiquidParam.RED, LiquidParam.GREEN, LiquidParam.BLUE, LiquidParam.COLOR_MODE, LiquidParam.RED2, LiquidParam.GREEN2, LiquidParam.BLUE2,
		LiquidParam.PATTERN, LiquidParam.STRIPE_SIZE, LiquidParam.COLOR_SPEED
	};

	public static final int COLOURS_ONE = 0;
	public static final int COLOURS_GRADIENT = 1;
	public static final int COLOURS_RAINBOW = 2;

	public int color2() {
		return Math.round(this.get(LiquidParam.RED2)) << 16 | Math.round(this.get(LiquidParam.GREEN2)) << 8 | Math.round(this.get(LiquidParam.BLUE2));
	}

	public CustomLiquid withType(LiquidType type) {
		float[] v = type.defaults();
		for (LiquidParam p : COLOUR_PARAMS) {
			v[p.ordinal()] = this.values[p.ordinal()];
		}
		return new CustomLiquid(this.slot, this.name, v);
	}

	public LiquidType type() {
		return LiquidType.of(this.get(LiquidParam.TYPE));
	}

	public int color() {
		return Math.round(this.get(LiquidParam.RED)) << 16 | Math.round(this.get(LiquidParam.GREEN)) << 8 | Math.round(this.get(LiquidParam.BLUE));
	}

	public boolean viscous() {
		return this.get(LiquidParam.VISCOSITY) > 0.0F || this.get(LiquidParam.STICKINESS) > 0.0F || this.get(LiquidParam.HOLD) > 0.0F;
	}

	public boolean foams() {
		return this.on(LiquidParam.FOAM) && !this.viscous() && this.choice(LiquidParam.LOOK) != LOOK_GEL;
	}

	public int resolution() {
		return Math.max(1, Math.round(this.get(LiquidParam.DETAIL)));
	}

	public static final int LOOK_CLEAR = 0;
	public static final int LOOK_GEL = 1;
	public static final int LOOK_MOLTEN = 2;

	@Override
	public boolean equals(Object o) {
		return o instanceof CustomLiquid other && other.slot == this.slot && other.name.equals(this.name) && Arrays.equals(other.values, this.values);
	}

	@Override
	public int hashCode() {
		return 31 * (31 * this.slot + this.name.hashCode()) + Arrays.hashCode(this.values);
	}

	@Override
	public String toString() {
		return "CustomLiquid[" + this.slot + " " + this.name + "]";
	}
}
