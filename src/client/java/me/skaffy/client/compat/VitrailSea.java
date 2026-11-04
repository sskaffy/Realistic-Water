package me.skaffy.client.compat;

import java.util.ArrayList;
import java.util.List;
import me.skaffy.client.water.OceanMode;
import me.skaffy.client.water.WaterWorld;
import me.skaffy.ocean.OceanWaves;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class VitrailSea {
	public static final int GRID = 96;
	public static final int WAVES = OceanWaves.WAVES;

	public static final String CAM = "rwSeaCam";
	public static final String GRID_INFO = "rwSeaGrid";
	public static final String WAVE = "rwSeaWave";
	public static final String PHASE = "rwSeaPhase";
	public static final String COLUMNS = "rwSeaCols";
	public static final String FAR_INFO = "rwSeaFarInfo";
	public static final String FAR = "rwSeaFar";

	public static final int FAR_CELL = 8;
	public static final int FAR_GRID = 128;
	static final int FAR_INTS = FAR_GRID * FAR_GRID / 8;

	public static final List<String[]> DECLARATIONS = List.of(
		new String[]{CAM, "vec4 " + CAM},
		new String[]{GRID_INFO, "ivec4 " + GRID_INFO},
		new String[]{WAVE, "vec4 " + WAVE + "[" + WAVES + "]"},
		new String[]{PHASE, "vec4 " + PHASE + "[" + (WAVES / 4) + "]"},
		new String[]{COLUMNS, "ivec4 " + COLUMNS + "[" + (GRID * GRID / 4) + "]"},
		new String[]{FAR_INFO, "ivec4 " + FAR_INFO},
		new String[]{FAR, "ivec4 " + FAR + "[" + (FAR_INTS / 4) + "]"});

	public static final ThreadLocal<Boolean> BLOCK_HAS_UNIFORMS = ThreadLocal.withInitial(() -> false);

	static final float[] cam = new float[4];
	static final int[] grid = new int[3];
	static final float[] waves = new float[WAVES * 4];
	static final float[] phases = new float[WAVES];
	static final int[] columns = new int[GRID * GRID];
	static final int[] farInfo = new int[4];
	private static final int[] camBlock = new int[3];
	static final int[] far = new int[FAR_INTS];
	private static int farCellX = Integer.MIN_VALUE;
	private static int farCellZ;
	private static int farBuilt = Integer.MIN_VALUE;
	private static int farX0;
	private static int farZ0;
	private static int farSeaTop;
	private static int builtFrame = Integer.MIN_VALUE;
	private static volatile long lastRead;

	private VitrailSea() {
	}

	public static boolean hooked() {
		long read = lastRead;
		return read != 0L && System.nanoTime() - read < 2_000_000_000L;
	}

	static void ensure(int frame) {
		lastRead = System.nanoTime();
		if (frame == builtFrame) {
			return;
		}
		builtFrame = frame;
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || !OceanMode.packDrawsSea()) {
			cam[3] = 0.0F;
			return;
		}
		LevelRenderState state = mc.gameRenderer.gameRenderState().levelRenderState;
		Vec3 c = state.cameraRenderState.pos;
		int bx = Mth.floor(c.x);
		int by = Mth.floor(c.y);
		int bz = Mth.floor(c.z);
		camBlock[0] = bx;
		camBlock[1] = by;
		camBlock[2] = bz;
		cam[0] = (float) (c.x - bx);
		cam[1] = (float) (c.y - by);
		cam[2] = (float) (c.z - bz);
		cam[3] = 1.0F;
		int half = GRID / 2;
		grid[0] = -half;
		grid[1] = GRID;
		grid[2] = -half;
		WaterWorld world = WaterWorld.get();
		world.seaColumns(bx - half, bz - half, GRID, by, columns);
		int cellX = Math.floorDiv(bx, FAR_CELL);
		int cellZ = Math.floorDiv(bz, FAR_CELL);
		if (cellX != farCellX || cellZ != farCellZ || frame - farBuilt > 120 || frame < farBuilt) {
			farCellX = cellX;
			farCellZ = cellZ;
			farBuilt = frame;
			farX0 = (cellX - FAR_GRID / 2) * FAR_CELL;
			farZ0 = (cellZ - FAR_GRID / 2) * FAR_CELL;
			farSeaTop = level.getSeaLevel() - 1;
			world.seaFarCells(farX0, farZ0, FAR_GRID, FAR_CELL, farSeaTop, far);
		}
		farInfo[0] = farX0 - bx;
		farInfo[1] = farZ0 - bz;
		farInfo[2] = farSeaTop - by;
		farInfo[3] = FAR_CELL;
		OceanWaves.Waves w = world.calmWaves(level, state.worldPartialTicks);
		float[] p = w.phases(bx, bz);
		for (int i = 0; i < WAVES; i++) {
			waves[i * 4] = (float) w.kx()[i];
			waves[i * 4 + 1] = (float) w.kz()[i];
			waves[i * 4 + 2] = (float) w.amp()[i];
			waves[i * 4 + 3] = (float) w.omega()[i];
			phases[i] = p[i];
		}
	}

	public static boolean live() {
		return hooked() && cam[3] > 0.5F;
	}

	public static float camOffset(int axis) {
		return cam[axis];
	}

	private static int column(int cx, int cz) {
		int gx = cx - grid[0];
		int gz = cz - grid[2];
		if (gx < 0 || gz < 0 || gx >= GRID || gz >= GRID) {
			return 0;
		}
		return columns[gx + gz * GRID];
	}

	private static float farCell(int kx, int kz) {
		if (kx < 0 || kz < 0 || kx >= FAR_GRID || kz >= FAR_GRID) {
			return 0.0F;
		}
		int i = kx + kz * FAR_GRID;
		return ((far[i >> 3] >> ((i & 7) * 4)) & 15) * (OceanWaves.AMP_MAX / 15.0F);
	}

	public static void corner(int cx, int cz, int y, float[] out) {
		float dist = (float) Math.sqrt((double) cx * cx + (double) cz * cz);
		float half = GRID * 0.5F;
		float near = 1.0F - smoothstep(half - 12.0F, half - 1.0F, dist);
		float amp = 0.0F;
		float dev = 0.0F;
		if (near > 0.0F) {
			float a = 1.5F;
			int hits = 0;
			for (int k = 0; k < 4; k++) {
				int w = column(cx + (k & 1) - 1, cz + (k >> 1) - 1);
				boolean on = w != 0 && (w & 511) - 256 == y;
				a = Math.min(a, on ? ((w >> 9) & 31) * (1.5F / 31.0F) : 0.0F);
				if (on) {
					dev += (((w >> 14) & 1023) - 512) / 64.0F;
					hits++;
				}
			}
			amp = a * near;
			dev = hits > 0 ? dev / hits * near : 0.0F;
		}
		if (near < 1.0F && y == farInfo[2]) {
			float u = (float) (cx - farInfo[0]) / FAR_CELL - 0.5F;
			float v = (float) (cz - farInfo[1]) / FAR_CELL - 0.5F;
			int kx = (int) Math.floor(u);
			int kz = (int) Math.floor(v);
			float fu = u - kx;
			float fv = v - kz;
			float f = (farCell(kx, kz) * (1 - fu) + farCell(kx + 1, kz) * fu) * (1 - fv) + (farCell(kx, kz + 1) * (1 - fu) + farCell(kx + 1, kz + 1) * fu) * fv;
			float farHalf = FAR_GRID / 2.0F * FAR_CELL;
			amp += (1.0F - near) * f * (1.0F - smoothstep(farHalf - 24.0F, farHalf - 8.0F, dist));
		}
		float h = 0.0F;
		if (amp > 0.0F) {
			for (int i = 0; i < OceanWaves.GEOMETRIC; i++) {
				float kx = waves[i * 4];
				float kz = waves[i * 4 + 1];
				float lod = 1.0F - smoothstep(10.0F, 20.0F, dist * (float) Math.sqrt(kx * kx + kz * kz) * 0.159155F);
				h += lod * waves[i * 4 + 2] * (float) Math.sin(phases[i] + kx * cx + kz * cz);
			}
		}
		out[0] = amp;
		out[1] = amp * h + dev;
		out[2] = dist;
	}

	public static float wave(int i, int field) {
		return field < 4 ? waves[i * 4 + field] : phases[i];
	}

	private static float smoothstep(float a, float b, float x) {
		float t = Math.clamp((x - a) / (b - a), 0.0F, 1.0F);
		return t * t * (3.0F - 2.0F * t);
	}

	public static int[] camBlock() {
		return camBlock;
	}

	public static List<String> vertexLines(List<String> prologue, List<String> carried, String blockId, String entry) {
		if (!BLOCK_HAS_UNIFORMS.get() || !carried.contains(blockId)) {
			return prologue;
		}
		int function = -1;
		for (int i = 0; i < prologue.size(); i++) {
			if (prologue.get(i).startsWith("void " + entry + "()")) {
				function = i;
			}
		}
		int end = prologue.size() - 1;
		if (function < 0 || end <= function || !prologue.get(end).equals("}")) {
			return prologue;
		}
		List<String> out = new ArrayList<>(prologue.size() + 48);
		out.addAll(prologue.subList(0, function));
		out.add("int rwSeaColumn(ivec2 rwC) {");
		out.add("\tivec2 rwG = rwC - " + GRID_INFO + ".xz;");
		out.add("\tif (any(lessThan(rwG, ivec2(0))) || any(greaterThanEqual(rwG, ivec2(" + GRID_INFO + ".y)))) {");
		out.add("\t\treturn 0;");
		out.add("\t}");
		out.add("\tint rwI = rwG.x + rwG.y * " + GRID_INFO + ".y;");
		out.add("\treturn " + COLUMNS + "[rwI >> 2][rwI & 3];");
		out.add("}");
		out.add("float rwSeaCorner(ivec2 rwC, int rwY, out float rwAmp) {");
		out.add("\trwAmp = 1.5;");
		out.add("\tfloat rwDev = 0.0;");
		out.add("\tint rwHits = 0;");
		out.add("\tfor (int rwK = 0; rwK < 4; rwK++) {");
		out.add("\t\tint rwW = rwSeaColumn(rwC + ivec2((rwK & 1) - 1, (rwK >> 1) - 1));");
		out.add("\t\tbool rwOn = rwW != 0 && (rwW & 511) - 256 == rwY;");
		out.add("\t\trwAmp = min(rwAmp, rwOn ? float((rwW >> 9) & 31) * (1.5 / 31.0) : 0.0);");
		out.add("\t\tif (rwOn) {");
		out.add("\t\t\trwDev += float(((rwW >> 14) & 1023) - 512) * (1.0 / 64.0);");
		out.add("\t\t\trwHits++;");
		out.add("\t\t}");
		out.add("\t}");
		out.add("\treturn rwHits > 0 ? rwDev / float(rwHits) : 0.0;");
		out.add("}");
		out.add("float rwSeaFarCell(ivec2 rwK) {");
		out.add("\tif (any(lessThan(rwK, ivec2(0))) || any(greaterThanEqual(rwK, ivec2(" + FAR_GRID + ")))) {");
		out.add("\t\treturn 0.0;");
		out.add("\t}");
		out.add("\tint rwI = rwK.x + rwK.y * " + FAR_GRID + ";");
		out.add("\tint rwW = " + FAR + "[rwI >> 5][(rwI >> 3) & 3];");
		out.add("\treturn float((rwW >> ((rwI & 7) * 4)) & 15) * (" + OceanWaves.AMP_MAX + " / 15.0);");
		out.add("}");
		out.add("float rwSeaFarAmp(ivec2 rwC) {");
		out.add("\tvec2 rwU = vec2(rwC - " + FAR_INFO + ".xy) / float(" + FAR_INFO + ".w) - 0.5;");
		out.add("\tivec2 rwK = ivec2(floor(rwU));");
		out.add("\tvec2 rwF = rwU - vec2(rwK);");
		out.add("\treturn mix(mix(rwSeaFarCell(rwK), rwSeaFarCell(rwK + ivec2(1, 0)), rwF.x),");
		out.add("\t\tmix(rwSeaFarCell(rwK + ivec2(0, 1)), rwSeaFarCell(rwK + ivec2(1, 1)), rwF.x), rwF.y);");
		out.add("}");
		out.addAll(prologue.subList(function, end));
		out.add("\tif (" + CAM + ".w > 0.5 && " + GRID_INFO + ".w > 1 && (" + blockId + " & 1u) != 0u"
			+ " && (" + blockId + " >> 1u) == (uint(" + GRID_INFO + ".w) >> 1u) && fract(ofLocal.y) > 0.01) {");
		out.add("\t\tvec3 rwP = of_Vertex.xyz + " + CAM + ".xyz;");
		out.add("\t\tivec2 rwC = ivec2(floor(rwP.xz + 0.5));");
		out.add("\t\tint rwY = int(floor(rwP.y));");
		out.add("\t\tfloat rwDist = length(vec2(rwC));");
		out.add("\t\tfloat rwHalf = float(" + GRID_INFO + ".y) * 0.5;");
		out.add("\t\tfloat rwNear = 1.0 - smoothstep(rwHalf - 12.0, rwHalf - 1.0, rwDist);");
		out.add("\t\tfloat rwAmp = 0.0;");
		out.add("\t\tfloat rwDev = 0.0;");
		out.add("\t\tvec2 rwDevSlope = vec2(0.0);");
		out.add("\t\tif (rwNear > 0.0) {");
		out.add("\t\t\tfloat rwOther;");
		out.add("\t\t\trwDev = rwSeaCorner(rwC, rwY, rwAmp) * rwNear;");
		out.add("\t\t\trwDevSlope = rwNear * 0.5 * vec2(rwSeaCorner(rwC + ivec2(1, 0), rwY, rwOther) - rwSeaCorner(rwC - ivec2(1, 0), rwY, rwOther),");
		out.add("\t\t\t\trwSeaCorner(rwC + ivec2(0, 1), rwY, rwOther) - rwSeaCorner(rwC - ivec2(0, 1), rwY, rwOther));");
		out.add("\t\t\trwAmp *= rwNear;");
		out.add("\t\t}");
		out.add("\t\tif (rwNear < 1.0 && rwY == " + FAR_INFO + ".z) {");
		out.add("\t\t\tfloat rwFarHalf = float(" + FAR_GRID / 2 + " * " + FAR_INFO + ".w);");
		out.add("\t\t\trwAmp += (1.0 - rwNear) * rwSeaFarAmp(rwC) * (1.0 - smoothstep(rwFarHalf - 24.0, rwFarHalf - 8.0, rwDist));");
		out.add("\t\t}");
		out.add("\t\tif (rwAmp > 0.0 || rwDev != 0.0) {");
		out.add("\t\t\tfloat rwH = 0.0;");
		out.add("\t\t\tvec2 rwS = vec2(0.0);");
		out.add("\t\t\tfor (int rwI = 0; rwI < " + WAVES + "; rwI++) {");
		out.add("\t\t\t\tvec4 rwWave = " + WAVE + "[rwI];");
		out.add("\t\t\t\tfloat rwPhase = " + PHASE + "[rwI >> 2][rwI & 3] + dot(rwWave.xy, rwP.xz);");
		out.add("\t\t\t\tfloat rwLod = 1.0 - smoothstep(10.0, 20.0, rwDist * length(rwWave.xy) * 0.159155);");
		out.add("\t\t\t\tif (rwI < " + OceanWaves.GEOMETRIC + ") {");
		out.add("\t\t\t\t\trwH += rwLod * rwWave.z * sin(rwPhase);");
		out.add("\t\t\t\t}");
		out.add("\t\t\t\trwS += rwWave.xy * (rwLod * rwWave.z * cos(rwPhase));");
		out.add("\t\t\t}");
		out.add("\t\t\tof_Vertex.y += rwAmp * rwH + rwDev;");
		out.add("\t\t\tif (of_Normal.y > 0.9) {");
		out.add("\t\t\t\tof_Normal = normalize(vec3(-(rwAmp * rwS.x + rwDevSlope.x), 1.0, -(rwAmp * rwS.y + rwDevSlope.y)));");
		out.add("\t\t\t}");
		out.add("\t\t}");
		out.add("\t}");
		out.add(prologue.get(end));
		return List.copyOf(out);
	}
}
