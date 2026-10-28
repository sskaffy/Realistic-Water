package me.skaffy.client.water;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.RealisticWater;

public final class WaterSettingsInfo {
	public enum Cost {
		NONE("None", "No effect on performance."),
		LOW("Light", "Barely affects performance."),
		MEDIUM("Medium", "Noticeable on big bodies of fluid."),
		HIGH("Heavy", "Can cost a lot of frame time."),
		EXTREME("Extreme", "The biggest performance lever in the mod.");

		public final String label;
		public final String meaning;

		Cost(String label, String meaning) {
			this.label = label;
			this.meaning = meaning;
		}
	}

	public enum Kind {
		NUMBER,
		INTEGER,
		SWITCH,
		TEXT
	}

	public record Info(String name, String category, String label, Kind kind, Cost cost, String costNote, String description, double min, double max,
		double step) {
	}

	private static final Map<String, Info> BY_NAME = new LinkedHashMap<>();
	private static final Map<String, List<String>> BY_CATEGORY = new LinkedHashMap<>();
	private static String category = "";

	private WaterSettingsInfo() {
	}

	public static List<String> categories() {
		return List.copyOf(BY_CATEGORY.keySet());
	}

	public static List<String> names(String category) {
		return BY_CATEGORY.getOrDefault(category, List.of());
	}

	public static Info get(String name) {
		return BY_NAME.get(name);
	}

	public static List<String> search(String query) {
		String q = query.toLowerCase().trim();
		List<String> out = new ArrayList<>();
		for (Info info : BY_NAME.values()) {
			if (info.name.toLowerCase().contains(q) || info.label.toLowerCase().contains(q) || info.description.toLowerCase().contains(q)) {
				out.add(info.name);
			}
		}
		return out;
	}

	private static void category(String name) {
		category = name;
		BY_CATEGORY.putIfAbsent(name, new ArrayList<>());
	}

	private static void add(Info info) {
		BY_NAME.put(info.name, info);
		BY_CATEGORY.get(info.category).add(info.name);
	}

	private static void num(String name, String label, Cost cost, String costNote, double min, double max, double step, String description) {
		add(new Info(name, category, label, Kind.NUMBER, cost, costNote, description, min, max, step));
	}

	private static void integer(String name, String label, Cost cost, String costNote, int min, int max, String description) {
		add(new Info(name, category, label, Kind.INTEGER, cost, costNote, description, min, max, 1));
	}

	private static void toggle(String name, String label, Cost cost, String costNote, String description) {
		add(new Info(name, category, label, Kind.SWITCH, cost, costNote, description, 0, 1, 1));
	}

	private static void text(String name, String label, Cost cost, String costNote, String description) {
		add(new Info(name, category, label, Kind.TEXT, cost, costNote, description, 0, 0, 0));
	}

	private static final String LOOK = "Only changes a number in the shader.";
	private static final String ONE_PASS = "Changes how much one compute pass does, not how many there are.";

	static {
		category("Water simulation");
		integer("resolution", "Resolution", Cost.EXTREME, "Cells per block in each direction: 6 to 12 is 8x the cells and particles, so roughly 8x the GPU time and memory.", 1, 16,
			"How many simulation cells fit along one block for water. Higher gives finer splashes, thinner sheets and smaller drops. Changing it rebuilds every water body.");
		integer("boxMargin", "Box margin", Cost.MEDIUM, "Every extra cell of margin adds a shell of cells that are solved each step.", 0, 24,
			"Empty cells kept around the water so it has room to move into before the simulated area grows.");
		num("cfl", "CFL number", Cost.HIGH, "Lower means more substeps per frame for fast water.", 0.25, 6, 0.05,
			"How many cells water may travel in one substep. Lower is more stable and accurate for fast flows but runs more substeps.");
		num("maxDt", "Max substep time", Cost.HIGH, "Lower forces more substeps every frame, even for calm water.", 0.002, 0.1, 0.001,
			"The longest one simulation substep may be, in seconds. Smaller is smoother and more stable.");
		integer("maxStepsPerFrame", "Max substeps per frame", Cost.HIGH, "Each substep is a full simulation step, about 15-40 GPU dispatches.", 1, 32,
			"Upper limit on substeps per frame while playing. When the limit is hit the water runs in slow motion instead of going unstable.");
		num("flipRatio", "FLIP ratio", Cost.NONE, LOOK, 0.8, 1, 0.001,
			"How much particles keep their own motion (FLIP) versus taking the smoothed grid velocity (PIC). Higher is splashier and livelier, lower is calmer and thicker.");
		num("volumeCorrection", "Volume correction", Cost.NONE, ONE_PASS, 0, 6, 0.1,
			"How hard the solver pushes apart particles that have bunched up, so water keeps its volume instead of slowly shrinking.");
		num("maxSpeed", "Max speed", Cost.LOW, "Faster water needs more substeps to stay stable.", 1, 100, 1,
			"Speed limit for water in blocks per second.");
		num("gravity", "Gravity", Cost.LOW, "Stronger gravity means faster water and more substeps.", 0, 30, 0.1,
			"Downward acceleration for water, lava and slime, in blocks per second squared. 9.81 is real-world gravity.");
		num("filmFriction", "Film friction", Cost.NONE, LOOK, 0, 300, 1,
			"How quickly a thin film of water on the ground stops sliding. Higher makes puddles settle faster, lower lets them spread thin.");
		num("filmDepth", "Film depth", Cost.NONE, LOOK, 0, 1, 0.01,
			"How shallow (in blocks) water has to be before film friction kicks in.");
		num("strandedTime", "Stranded drop lifetime", Cost.LOW, "Longer means more stray droplets stay alive and simulated.", 0, 20, 0.1,
			"Seconds a lone drop with few neighbours survives before it dries up.");
		integer("strandedNeighbors", "Stranded neighbour count", Cost.LOW, "Fewer means more lone drops are kept alive.", 0, 64,
			"A drop with fewer particles than this around it counts as stranded and starts drying up.");
		num("fluidRestSpeed", "Rest speed", Cost.MEDIUM, "Higher lets still water go to sleep sooner, which saves its whole simulation cost.", 0, 2, 0.01,
			"Below this speed (blocks per second) a body of water, lava or slime counts as calm.");
		num("fluidRestTime", "Rest time", Cost.MEDIUM, "Shorter puts calm bodies to sleep sooner. 0 never sleeps, so everything is simulated every frame.", 0, 10, 0.1,
			"Seconds a body must stay calm before it stops simulating until something disturbs it.");
		integer("fluidRestWhitewater", "Rest foam limit", Cost.LOW, "Lets bodies with leftover foam sleep sooner.", 0, 2000,
			"A body only goes to sleep when it has at most this many foam and spray particles left.");
		num("sourceFill", "Spring fill", Cost.LOW, "Fuller springs add particles faster.", 0, 1, 0.01,
			"How full a spring block (infinite water or lava) keeps itself, from empty to a full block.");
		toggle("bucketMakesSource", "Bucket makes spring", Cost.LOW, "Springs keep emitting forever, which keeps their body of fluid simulated.",
			"Off: one bucket places one finite block of water or lava. On: the bucket places an infinite spring that keeps flowing. "
				+ "Applies to buckets used after changing it; existing springs stay as they are.");

		category("Performance");
		toggle("adaptiveQuality", "Adaptive quality", Cost.NONE, "Trades some accuracy for frame rate only while frames run slow.",
			"Watches the frame time and, while it is over the target, uses fewer substeps and solver iterations. Restores them when there is headroom.");
		num("targetFrameMs", "Target frame time", Cost.NONE, "Lower reacts earlier; 16.7 is 60 FPS, 20 is 50 FPS.", 5, 66, 0.5,
			"Frame time in milliseconds that adaptive quality tries to stay under.");
		num("adaptiveMinQuality", "Lowest quality", Cost.NONE, "Lower lets adaptive quality cut deeper.", 0.1, 1, 0.05,
			"Smallest fraction of substeps and solver iterations adaptive quality may fall back to.");
		num("simDistance", "Simulation distance", Cost.HIGH, "Bodies of fluid farther away than this are not simulated or drawn. 0 uses the render distance.", 0, 512, 8,
			"Blocks from the camera beyond which fluid bodies stop being simulated.");
		num("farMeshDistance", "Far mesh distance", Cost.MEDIUM, "Beyond this the water surface is rebuilt less often. 0 turns this off.", 0, 512, 8,
			"Blocks from the camera beyond which a body's surface mesh is rebuilt only every few frames.");
		integer("farMeshInterval", "Far mesh interval", Cost.MEDIUM, "Higher rebuilds distant surfaces less often.", 1, 8,
			"A distant body rebuilds its surface once every this many frames.");

		category("Pressure solver");
		integer("cgIterations", "Solver iterations", Cost.HIGH, "Each iteration is a few more GPU dispatches every substep.", 1, 30,
			"Rounds of the pressure solver per substep. More keeps water incompressible (no squishing or sinking) at a higher cost.");
		integer("mgCoarseSweeps", "Coarse sweeps", Cost.MEDIUM, ONE_PASS, 1, 64,
			"Smoothing sweeps on the coarsest multigrid level. More helps big, deep pools settle without bouncing.");
		integer("solverBottomCells", "Bottom level size", Cost.MEDIUM, "Picks where the solver switches to one big single-group pass; the best value depends on the GPU.", 0, 65536,
			"Multigrid levels at or below this many cells are solved in one workgroup. 0 turns this off.");
		integer("solverSmallCells", "Small body size", Cost.MEDIUM, "Small bodies solved in one dispatch avoid dozens of tiny dispatches.", 0, 200000,
			"Bodies with at most this many active cells use the single-dispatch solver.");
		integer("extrapolationLayers", "Velocity extrapolation", Cost.LOW, "One extra pass per layer per substep.", 0, 12,
			"How many cells the water's velocity is carried out into the air, so flying drops keep moving correctly.");

		category("Whitewater and foam");
		toggle("whitewater", "Whitewater", Cost.HIGH, "Spawns and simulates up to millions of extra foam, spray and bubble particles.",
			"Foam on the surface, spray in the air and bubbles under water, spawned where water is fast, turbulent or breaking.");
		num("wwTrappedAirRate", "Trapped air rate", Cost.MEDIUM, "More rate means more whitewater particles.", 0, 200, 1,
			"How much whitewater spawns where water churns and traps air (waterfalls, impacts).");
		num("wwWaveCrestRate", "Wave crest rate", Cost.MEDIUM, "More rate means more whitewater particles.", 0, 200, 1,
			"How much whitewater spawns on sharp wave crests.");
		num("wwEnergyMin", "Energy minimum", Cost.MEDIUM, "Lower means calmer water spawns whitewater too.", 0, 20, 0.1,
			"Water slower than this spawns no whitewater at all.");
		num("wwEnergyMax", "Energy maximum", Cost.LOW, "Lower reaches full spawning sooner.", 0, 40, 0.1,
			"Water this energetic spawns whitewater at the full rate.");
		num("wwTrappedAirMin", "Trapped air minimum", Cost.LOW, "Lower spawns more.", 0, 40, 0.1,
			"How churned-up water has to be before it traps air.");
		num("wwTrappedAirMax", "Trapped air maximum", Cost.LOW, "Lower spawns more.", 0, 60, 0.1,
			"Churn at which trapped-air spawning is at full strength.");
		num("wwWaveCrestMin", "Wave crest minimum", Cost.LOW, "Lower spawns more.", 0, 10, 0.05,
			"How sharp a crest has to be before it makes foam.");
		num("wwWaveCrestMax", "Wave crest maximum", Cost.LOW, "Lower spawns more.", 0, 20, 0.05,
			"Crest sharpness at which foam spawning is at full strength.");
		num("wwFoamLifeMin", "Foam life minimum", Cost.MEDIUM, "Longer-lived foam means more particles alive at once.", 0, 20, 0.1,
			"Shortest time a foam particle lasts, in seconds.");
		num("wwFoamLifeMax", "Foam life maximum", Cost.MEDIUM, "Longer-lived foam means more particles alive at once.", 0, 30, 0.1,
			"Longest time a foam particle lasts, in seconds.");
		num("wwBubbleBuoyancy", "Bubble buoyancy", Cost.NONE, LOOK, 0, 10, 0.1,
			"How fast bubbles rise back up through the water.");
		num("wwBubbleDrag", "Bubble drag", Cost.NONE, LOOK, 0, 5, 0.05,
			"How strongly bubbles are carried along by the water around them.");
		num("wwSprayDrag", "Spray drag", Cost.NONE, LOOK, 0, 5, 0.05,
			"Air resistance on spray droplets.");
		num("wwSprayThreshold", "Spray threshold", Cost.NONE, LOOK, 0, 20, 0.5,
			"Whitewater with fewer water neighbours than this counts as spray (flying droplets).");
		num("wwBubbleThreshold", "Bubble threshold", Cost.NONE, LOOK, 0, 30, 0.5,
			"Whitewater with more water neighbours than this counts as an underwater bubble; in between is surface foam.");
		integer("wwMaxPerParticle", "Max spawn per particle", Cost.HIGH, "Caps how many whitewater particles one water particle can create per substep.", 0, 16,
			"Upper limit on whitewater spawned by a single water particle in one substep.");
		integer("wwCellLimit", "Max per cell", Cost.HIGH, "Raising it lets dense foam pile up to many more particles.", 0, 100,
			"No more whitewater spawns in a cell that already holds this many.");
		num("wwRadius", "Whitewater size", Cost.LOW, "Bigger sprites cover more pixels.", 0.001, 0.1, 0.001,
			"Size of one spray or bubble particle, in blocks.");
		num("wwMaxPixels", "Whitewater max pixels", Cost.LOW, "Bigger sprites up close cost more fill rate.", 0.5, 20, 0.1,
			"Largest a whitewater particle can appear on screen, in pixels, so close-up spray stays fine.");
		num("foamCoverage", "Foam coverage", Cost.NONE, LOOK, 0, 5, 0.05,
			"How opaque and white foam looks where it gathers.");
		num("foamSize", "Foam size", Cost.LOW, "Bigger foam splats cover more pixels.", 0.5, 10, 0.1,
			"Size multiplier for surface foam splats.");
		num("foamBubbleSize", "Foam bubble size", Cost.NONE, LOOK, 0.005, 0.5, 0.005,
			"Size of the bubble lace pattern drawn into foam, in blocks.");
		num("bubbleAlpha", "Bubble opacity", Cost.NONE, LOOK, 0, 1, 0.01,
			"How visible underwater bubbles are.");
		num("sprayAlpha", "Spray opacity", Cost.NONE, LOOK, 0, 1, 0.01,
			"How visible spray droplets are.");

		category("Surface mesh");
		integer("surfaceSubdivision", "Surface subdivision", Cost.EXTREME, "Mesh voxels grow with the cube of this: 2 to 3 is about 3.4x the surface work.", 1, 4,
			"How many times finer than the simulation the water surface is meshed. Higher is smoother and more detailed. Takes effect when a body's area is rebuilt (/water reload).");
		num("surfaceKernel", "Surface kernel", Cost.HIGH, "Each particle touches kernel^3 voxels when the surface is built.", 1, 6, 0.1,
			"How far (in fine voxels) each particle reaches when building the surface. Bigger is smoother and blobbier.");
		num("surfaceRadius", "Surface radius", Cost.NONE, ONE_PASS, 0.2, 3, 0.05,
			"Radius of the sphere around each particle that makes up the surface.");
		num("surfaceIso", "Surface threshold", Cost.NONE, ONE_PASS, 0.05, 1.5, 0.01,
			"Where the surface is drawn: lower makes water look fuller and thin sheets hold together, higher shrinks it.");
		num("dropletThreshold", "Droplet threshold", Cost.LOW, "More particles drawn as separate droplets.", 0, 2, 0.01,
			"Particles in places thinner than this are drawn as separate round droplets instead of being part of the mesh.");
		num("nearCull", "Near cull", Cost.NONE, LOOK, 0, 1, 0.01,
			"Surface closer to the camera than this (in blocks) is skipped, so it doesn't cut through the view.");
		toggle("surfaceGather", "Gather surface", Cost.MEDIUM, "On is much faster on most GPUs (fewer atomics); off uses the older splat path.",
			"Builds the surface field by having each voxel gather nearby particles instead of particles scattering into voxels.");
		toggle("sortParticles", "Sort particles", Cost.MEDIUM, "Costs a sort per frame but makes the gather and particle passes several times faster.",
			"Keeps particles in memory order by cell. Only used with Gather surface.");
		toggle("surfaceInteriorSkip", "Skip interior", Cost.MEDIUM, "Skips surface work deep inside water, which saves a lot on big pools.",
			"Doesn't build surface detail for the inside of deep water, where it can never be seen.");
		integer("surfaceInteriorCount", "Interior depth", Cost.LOW, "Higher skips less.", 1, 16,
			"How deep inside the water (in cells) the surface work starts being skipped.");
		toggle("wallFill", "Fill to walls", Cost.MEDIUM, "One extra pass over the surface field each time it is rebuilt.",
			"Makes the surface reach all the way to blocks it touches, like vanilla water, instead of stopping a little short of walls.");
		num("wallFillReach", "Wall fill reach", Cost.LOW, "A wider reach reads more voxels per pass.", 0, 3, 0.05,
			"How far from a wall (in cells) the surface is straightened out to meet it.");
		toggle("sheetFill", "Sheet fill", Cost.MEDIUM, "Adds a blur pass over the surface field.",
			"Smooths very thin sheets of water so they don't break into holes and noodles.");
		num("sheetSmoothing", "Sheet smoothing", Cost.NONE, ONE_PASS, 0, 6, 0.1,
			"How strongly Sheet fill smooths thin sheets.");
		num("particleRadius", "Particle radius", Cost.LOW, "Bigger droplets cover more pixels.", 0.05, 1.5, 0.01,
			"Size of one particle drawn as a droplet, in cells.");

		category("Water look");
		num("thicknessScale", "Thickness scale", Cost.NONE, LOOK, 0, 4, 0.05,
			"Multiplies how deep the water looks for colour and light absorption.");
		num("refraction", "Refraction", Cost.NONE, LOOK, 0, 1, 0.01,
			"How much the world behind water is bent.");
		num("absorbR", "Absorption red", Cost.NONE, LOOK, 0, 5, 0.01,
			"How quickly red light fades through water. Higher red absorption makes deep water bluer.");
		num("absorbG", "Absorption green", Cost.NONE, LOOK, 0, 5, 0.01,
			"How quickly green light fades through water.");
		num("absorbB", "Absorption blue", Cost.NONE, LOOK, 0, 5, 0.01,
			"How quickly blue light fades through water.");
		num("scatterR", "Scatter red", Cost.NONE, LOOK, 0, 1, 0.01,
			"Red part of the colour light picks up inside water.");
		num("scatterG", "Scatter green", Cost.NONE, LOOK, 0, 1, 0.01,
			"Green part of the colour light picks up inside water.");
		num("scatterB", "Scatter blue", Cost.NONE, LOOK, 0, 1, 0.01,
			"Blue part of the colour light picks up inside water.");
		num("scatterDensity", "Scatter density", Cost.NONE, LOOK, 0, 5, 0.01,
			"How murky water is: higher shows more of the scatter colour and less of what's behind it.");
		num("underwaterFog", "Underwater fog", Cost.NONE, LOOK, 0, 2, 0.01,
			"How thick the fog is when your head is under water.");
		toggle("underwaterMirror", "Underwater mirror", Cost.NONE, LOOK,
			"Looking up from under water, the surface past about 48 degrees acts as a mirror like real water (total internal reflection), "
				+ "showing dark deep water there. Off shows what is above the surface everywhere.");
		num("surfaceSmoothing", "Surface smoothing", Cost.LOW, "Wider smoothing reads more neighbours per pixel.", 0, 1, 0.01,
			"How much the surface normals are smoothed. Higher is glassier, lower shows more of the particle shapes.");
		num("detailStrength", "Ripple strength", Cost.NONE, LOOK, 0, 2, 0.01,
			"Strength of the small procedural ripples on the surface.");
		num("detailScale", "Ripple scale", Cost.NONE, LOOK, 0.5, 20, 0.1,
			"How tightly packed the small ripples are.");
		num("detailSpeed", "Ripple speed", Cost.NONE, LOOK, 0, 5, 0.05,
			"How fast the small ripples move.");
		num("roughness", "Roughness", Cost.NONE, LOOK, 0.01, 0.7, 0.01,
			"Lower gives sharp sun glints, higher spreads them into a soft sheen.");
		num("specular", "Sun glint", Cost.NONE, LOOK, 0, 20, 0.1,
			"Brightness of sun reflections.");
		num("wetDarkening", "Wet darkening", Cost.NONE, LOOK, 0, 1, 0.01,
			"How much the ground under thin water looks darker, like wet stone or grass.");
		num("filmShading", "Film shading", Cost.NONE, LOOK, 0, 1, 0.01,
			"Flattens the lighting of very thin water so puddles don't get a glassy rim.");

		category("Lava");
		integer("lavaResolution", "Resolution", Cost.EXTREME, "Same as water: doubling it is roughly 8x the cost.", 1, 16,
			"Simulation cells per block for lava. Changing it rebuilds every lava body.");
		num("lavaFlipRatio", "FLIP ratio", Cost.NONE, LOOK, 0.8, 1, 0.0001,
			"How much lava keeps its own motion versus the smoothed grid velocity.");
		num("lavaMaxSpeed", "Max speed", Cost.LOW, "Faster lava needs more substeps.", 0.5, 40, 0.1,
			"Speed limit for lava in blocks per second. Low keeps it slow and heavy.");
		num("lavaFilmFriction", "Film friction", Cost.NONE, LOOK, 0, 500, 1,
			"How quickly thin lava stops spreading.");
		num("lavaFilmDepth", "Film depth", Cost.NONE, LOOK, 0, 1, 0.01,
			"How shallow lava has to be before film friction kicks in.");
		num("lavaStrandedTime", "Stranded drop lifetime", Cost.LOW, "Longer keeps more stray blobs alive.", 0, 30, 0.1,
			"Seconds a lone blob of lava survives.");
		integer("lavaStrandedNeighbors", "Stranded neighbour count", Cost.LOW, "Fewer keeps more blobs alive.", 0, 64,
			"Lava with fewer particles than this around it counts as stranded.");
		num("lavaRefraction", "Refraction", Cost.NONE, LOOK, 0, 1, 0.01,
			"How much lava bends what's behind it.");
		num("lavaAbsorbR", "Absorption red", Cost.NONE, LOOK, 0, 20, 0.05,
			"How quickly red light fades through lava.");
		num("lavaAbsorbG", "Absorption green", Cost.NONE, LOOK, 0, 20, 0.05,
			"How quickly green light fades through lava.");
		num("lavaAbsorbB", "Absorption blue", Cost.NONE, LOOK, 0, 20, 0.05,
			"How quickly blue light fades through lava.");
		num("lavaScatterR", "Glow red", Cost.NONE, LOOK, 0, 3, 0.01,
			"Red part of lava's glow.");
		num("lavaScatterG", "Glow green", Cost.NONE, LOOK, 0, 3, 0.01,
			"Green part of lava's glow.");
		num("lavaScatterB", "Glow blue", Cost.NONE, LOOK, 0, 3, 0.01,
			"Blue part of lava's glow.");
		num("lavaScatterDensity", "Glow density", Cost.NONE, LOOK, 0, 10, 0.05,
			"How quickly lava reaches full glow as it gets deeper.");
		num("lavaUnderwaterFog", "Inside fog", Cost.NONE, LOOK, 0, 2, 0.01,
			"Fog when your head is inside lava.");
		num("lavaSurfaceSmoothing", "Surface smoothing", Cost.LOW, "Wider smoothing reads more neighbours per pixel.", 0, 1, 0.01,
			"How smooth the lava surface looks.");
		num("lavaDetailStrength", "Ripple strength", Cost.NONE, LOOK, 0, 2, 0.01,
			"Strength of small surface ripples on lava.");
		num("lavaRoughness", "Roughness", Cost.NONE, LOOK, 0.01, 0.7, 0.01,
			"How spread out sun reflections on lava are.");
		num("lavaSpecular", "Sun glint", Cost.NONE, LOOK, 0, 20, 0.1,
			"Brightness of sun reflections on lava.");
		toggle("lavaFlowSounds", "Lava sounds", Cost.LOW, "Plays a few sounds per second near moving lava.",
			"Bubbling and popping sounds from moving lava.");
		toggle("lavaIgniteFlammables", "Lava sets fires", Cost.LOW, "A random tick check on each lava block, like vanilla lava.",
			"Lets realistic lava set nearby flammable blocks on fire, like vanilla lava.");
		toggle("lavaWaterMixing", "Water + lava = stone", Cost.LOW, "Checks where lava and water touch a few times per second.",
			"Lava that touches water turns into cobblestone, and a lava spring into obsidian, like vanilla.");
		num("lavaMixLavaMin", "Mix: lava needed", Cost.NONE, "Doesn't change the cost.", 0.01, 1, 0.01,
			"How full of lava a block must be before touching water turns it to cobblestone. Lower reacts to thin lava.");
		num("lavaMixWaterMin", "Mix: water needed", Cost.NONE, "Doesn't change the cost.", 0.01, 1, 0.01,
			"How full of water a block must be to cool the lava next to it. Lower lets a few drops make stone.");

		category("Slime");
		integer("slimeResolution", "Resolution", Cost.EXTREME, "Same as water: doubling it is roughly 8x the cost.", 1, 16,
			"Simulation cells per block for slime. Changing it rebuilds every slime body.");
		num("slimeViscosity", "Thickness", Cost.LOW, "Doesn't change the cost; Thickness passes does.", 0, 10, 0.05,
			"How thick and gooey slime is (its viscosity). Higher flows slower and holds together; 0 makes it flow like water.");
		integer("slimeViscosityIterations", "Thickness passes", Cost.MEDIUM, "Each pass is one more GPU dispatch over every face, every substep.", 0, 32,
			"How many solver passes the thickness gets per substep. More makes high Thickness values actually feel that thick.");
		num("slimeAdhesion", "Stickiness", Cost.NONE, LOOK, 0, 100, 0.5,
			"How strongly slime sticks to blocks it touches. High values let it cling to walls and ceilings and only slowly drip off.");
		num("slimeEntityStick", "Sticks to mobs", Cost.NONE, LOOK, 0, 1, 0.01,
			"How much slime touching a player or mob gets dragged along with it. 1 sticks completely.");
		num("slimeRestSpeed", "Hold speed", Cost.NONE, LOOK, 0, 3, 0.05,
			"Slime slower than this (blocks per second) starts holding its shape. This is what keeps a placed block of slime standing.");
		num("slimeRestTime", "Hold delay", Cost.NONE, LOOK, 0, 2, 0.01,
			"Seconds slime has to be slow before it holds its shape.");
		num("slimeWakeSpeed", "Wobble limit", Cost.NONE, LOOK, 0, 10, 0.05,
			"How hard slime has to be pushed (blocks per second) before it lets go and flows again.");
		num("slimeFlipRatio", "FLIP ratio", Cost.NONE, LOOK, 0.5, 1, 0.001,
			"How much slime keeps its own motion. Lower is more damped and sluggish.");
		num("slimeVolumeCorrection", "Volume correction", Cost.NONE, ONE_PASS, 0, 40, 0.5,
			"How hard squashed slime pushes back out to its normal size, so it can't be squeezed into a small ball.");
		num("slimeMaxSpeed", "Max speed", Cost.LOW, "Faster slime needs more substeps.", 0.5, 40, 0.1,
			"Speed limit for slime in blocks per second. This is a big part of why it moves so slowly.");
		num("slimeFilmFriction", "Film friction", Cost.NONE, LOOK, 0, 500, 1,
			"How quickly thin slime stops spreading on the ground.");
		num("slimeFilmDepth", "Film depth", Cost.NONE, LOOK, 0, 1, 0.01,
			"How shallow slime has to be before film friction kicks in.");
		num("slimeStrandedTime", "Stranded blob lifetime", Cost.LOW, "Longer keeps more stray blobs alive.", 0, 60, 0.5,
			"Seconds a lone blob of slime survives.");
		integer("slimeStrandedNeighbors", "Stranded neighbour count", Cost.LOW, "Fewer keeps more blobs alive.", 0, 64,
			"Slime with fewer particles than this around it counts as stranded. Low so small blobs and strings survive.");
		num("slimeOpacity", "Opacity", Cost.NONE, LOOK, 0, 1, 0.01,
			"How solid slime looks where it is thin. 1 hides everything behind it; thick slime is always fully opaque.");
		num("slimeBrightness", "Brightness", Cost.NONE, LOOK, 0, 3, 0.01,
			"Overall brightness of the slime texture.");
		num("slimeInsideOpacity", "Inside view", Cost.NONE, LOOK, 0, 1, 0.01,
			"How much green covers the screen while your head is inside slime. 1 is completely blind.");
		num("slimeFogDistance", "Inside fog distance", Cost.NONE, LOOK, 0.05, 8, 0.05,
			"How far you can see (in blocks) while your head is inside slime.");
		num("slimeSurfaceSmoothing", "Surface smoothing", Cost.LOW, "Wider smoothing reads more neighbours per pixel.", 0, 1, 0.01,
			"How smooth and glossy the slime surface looks.");
		toggle("slimeSounds", "Slime sounds", Cost.LOW, "Plays a few sounds per second near moving slime.",
			"Squishing sounds from moving slime.");

		category("Sand");
		integer("sandResolution", "Resolution", Cost.EXTREME, "Doubling it is roughly 8x the particles and grid; sand also runs many substeps.", 1, 24,
			"Simulation cells per block for sand. Higher gives finer grains and more detailed piles. Changing it rebuilds every sand body.");
		num("sandFrictionAngle", "Friction angle", Cost.NONE, LOOK, 0, 60, 0.5,
			"How steep sand can pile before it slides, in degrees.");
		num("sandCohesion", "Cohesion", Cost.NONE, LOOK, 0, 0.5, 0.001,
			"How much sand sticks to itself, like damp sand. 0 is perfectly dry.");
		num("sandStiffness", "Stiffness", Cost.HIGH, "Stiffer sand needs more, shorter substeps to stay stable.", 1, 500, 0.5,
			"How stiff a grain packing is. Higher feels more solid but costs more substeps.");
		num("sandWallFriction", "Wall friction", Cost.NONE, LOOK, 0, 2, 0.01,
			"How much sand grips the blocks it slides along.");
		num("sandCfl", "CFL number", Cost.HIGH, "Lower means more substeps.", 0.05, 1, 0.01,
			"Stability margin for sand substeps. Lower is safer and slower.");
		integer("sandMaxStepsPerFrame", "Max substeps per frame", Cost.HIGH, "Each substep is a full sand step.", 1, 64,
			"Upper limit on sand substeps per frame. When hit, sand runs in slow motion.");
		num("sandImpactDamping", "Impact damping", Cost.NONE, LOOK, 0, 2, 0.01,
			"Soaks up energy when sand hits the ground hard, so it splats instead of bouncing.");
		num("sandImpactRate", "Impact rate", Cost.NONE, LOOK, 0, 20, 0.1,
			"How fast sand has to be squashed before it counts as an impact.");
		num("sandImpactCompression", "Impact compression", Cost.NONE, LOOK, 0, 0.9, 0.01,
			"How much an impact can pack sand down without springing back.");
		num("sandRestSpeed", "Rest speed", Cost.MEDIUM, "Higher lets sand freeze sooner, which skips its work.", 0, 1, 0.005,
			"Sand slower than this (blocks per second) starts to settle.");
		num("sandRestTime", "Rest time", Cost.MEDIUM, "Shorter freezes settled sand sooner.", 0, 2, 0.01,
			"Seconds sand has to be slow before it freezes in place.");
		num("sandWakeAccel", "Wake acceleration", Cost.LOW, "Lower wakes frozen sand more easily.", 0, 5, 0.05,
			"How hard frozen sand has to be pushed, as a fraction of gravity, before it moves again.");
		num("sandSurfaceIso", "Surface threshold", Cost.NONE, LOOK, 0.05, 1.5, 0.01,
			"Where the sand surface sits, used to decide which grains are on the outside.");
		num("sandSurfaceSmoothing", "Surface smoothing", Cost.NONE, LOOK, 0, 1, 0.01,
			"How smooth the sand's surface normals are, which shades the grains.");
		num("sandGrainSize", "Grain size", Cost.LOW, "Bigger grains cover more pixels.", 0.002, 0.05, 0.001,
			"Radius of one drawn sand grain, in blocks.");
		integer("sandGrainsPerParticle", "Grains per particle", Cost.HIGH, "Every surface particle draws this many grains: the main sand drawing cost.", 1, 128,
			"How many grains are drawn for each sand particle near the surface. More looks denser.");
		num("sandGrainSpread", "Grain spread", Cost.NONE, LOOK, 0, 2, 0.01,
			"How far grains scatter around their particle.");
		num("sandGrainMinPixels", "Grain minimum pixels", Cost.LOW, "Bigger far grains cover more pixels.", 0, 4, 0.05,
			"Smallest a grain can appear on screen, so far-away sand doesn't flicker.");
		num("sandBrightness", "Brightness", Cost.NONE, LOOK, 0, 3, 0.01,
			"Overall brightness of the sand.");
		toggle("sandSounds", "Sand sounds", Cost.LOW, "Plays a few sounds per second near moving sand.",
			"Sliding and impact sounds from moving sand.");

		category("Leaves");
		integer("leafResolution", "Resolution", Cost.MEDIUM, "Cells per block for stacking; more cells means a finer pile shape but a bigger grid.", 2, 16,
			"How finely leaf piles are stacked: each block is split into this many cells per side, and each cell holds a few leaves. Changing it rebuilds every pile.");
		integer("leavesPerBlock", "Leaves per block", Cost.MEDIUM, "Cost grows with the number of leaves: 400 is about twice the work of 200.", 8, 4000,
			"How many separate leaves one Realistic Leaves block is made of. Only affects blocks placed after the change.");
		integer("leafCellCapacity", "Leaves per cell", Cost.NONE, "Doesn't change the cost.", 1, 16,
			"How many leaves fit in one stacking cell before the rest are pushed aside. Higher makes thinner, denser piles.");
		num("leafSize", "Leaf size", Cost.LOW, "Bigger leaves cover more pixels.", 0.05, 1, 0.01,
			"Width of one leaf in blocks. 0.3125 matches Minecraft's pixel size for the 5-pixel leaf texture.");
		num("leafFallSpeed", "Fall speed", Cost.NONE, LOOK, 0.1, 10, 0.05,
			"Top speed a leaf falls through air, in blocks per second. Lower floats more.");
		num("leafSinkSpeed", "Sink speed in water", Cost.NONE, LOOK, 0.02, 5, 0.01,
			"Top speed a leaf sinks through water (realistic or vanilla), in blocks per second.");
		num("leafAirDrag", "Air glide", Cost.NONE, LOOK, 0.1, 20, 0.1,
			"How quickly air slows a leaf's sideways motion, per second. Lower lets kicked or blown leaves fly further.");
		num("leafFlutter", "Flutter", Cost.NONE, LOOK, 0, 3, 0.05,
			"How far falling leaves swing side to side.");
		num("leafTumbleRate", "Tumble rate", Cost.NONE, LOOK, 0, 20, 0.1,
			"How fast falling leaves rock and tumble.");
		num("leafGroundFriction", "Ground friction", Cost.NONE, LOOK, 0, 50, 0.5,
			"How quickly leaves stop sliding once they lie on something.");
		integer("leafRepose", "Pile steepness", Cost.NONE, "Doesn't change the cost.", 1, 8,
			"How many cells a leaf may stick out over an edge before it slides off. 1 gives low spread-out mounds, higher gives steeper heaps.");
		num("leafShuffleSpeed", "Settle speed", Cost.NONE, LOOK, 0.1, 10, 0.1,
			"How fast leaves are pushed out of overfull cells and slide down pile slopes, in blocks per second.");
		num("leafRestSpeed", "Rest speed", Cost.NONE, LOOK, 0, 1, 0.01,
			"Below this speed a supported leaf counts as still and soon falls asleep.");
		num("leafRestTime", "Rest time", Cost.NONE, LOOK, 0, 5, 0.05,
			"Seconds a leaf has to stay still before it sleeps. Sleeping piles cost almost nothing.");
		num("leafWakeSpeed", "Wake speed", Cost.NONE, LOOK, 0, 5, 0.05,
			"A sleeping leaf wakes when something pushes it faster than this.");
		num("leafKick", "Kick strength", Cost.NONE, LOOK, 0, 5, 0.05,
			"How hard players and mobs kick leaves up when walking through a pile.");
		num("leafBurnTime", "Burn time", Cost.NONE, LOOK, 0.05, 10, 0.05,
			"Seconds a leaf glows and chars in lava or fire before it is gone.");
		num("leafWindStrength", "Wind strength", Cost.NONE, LOOK, 0, 20, 0.1,
			"Wind speed during rain, in blocks per second. Thunderstorms blow twice as hard. There is no wind in clear weather.");
		num("leafWindLift", "Wind lift", Cost.LOW, "Lifted leaves stay awake, so stronger lift keeps more of the pile simulated.", 0, 5, 0.05,
			"How often gusts pick resting leaves back up during rain.");
		num("leafMaxSpeed", "Max speed", Cost.NONE, LOOK, 1, 50, 0.5,
			"Speed limit for leaves in blocks per second, so blasts don't fling them out of the world.");
		integer("leafMaxStepsPerFrame", "Max steps per frame", Cost.MEDIUM, "Each step is three small compute passes per pile.", 1, 32,
			"Upper limit on leaf substeps per frame.");
		num("leafBrightness", "Brightness", Cost.NONE, LOOK, 0.2, 3, 0.05,
			"Brightness of the leaves.");
		toggle("leafSounds", "Leaf sounds", Cost.LOW, "Plays a few sounds per second near moving leaves.",
			"Rustling sounds from leaves that are kicked up or blown around.");

		category("Water pump");
		num("waveMakerRate", "Pump rate", Cost.NONE, LOOK, 0.1, 30, 0.1,
			"How much of the water behind a fully powered water pump it moves to the front each second (4 = about four blocks of water per second). Weaker redstone signals pump less.");
		num("waveMakerSpeed", "Jet speed scale", Cost.NONE, LOOK, 0.1, 5, 0.05,
			"Multiplies how fast water leaves the front for every speed setting (slow 2, medium 4, fast 14 blocks per second).");

		category("TNT and explosions");
		toggle("tntReactions", "Explosions push fluids", Cost.LOW, "One small pass per explosion per body it reaches.",
			"Makes realistic water, lava, slime and sand get blasted away by TNT and other explosions.");
		num("tntBlastSpeed", "Blast strength", Cost.LOW, "Stronger blasts throw particles faster, which needs more substeps for a moment.", 0, 60, 0.5,
			"How fast fluid right next to a TNT-sized explosion is thrown, in blocks per second. Bigger explosions scale it up.");
		num("tntBlastReach", "Blast reach", Cost.NONE, LOOK, 0, 6, 0.05,
			"How far the push reaches, as a multiple of the explosion's radius. The push fades to nothing at this distance.");
		num("tntUpwardBias", "Upward bias", Cost.NONE, LOOK, 0, 2, 0.05,
			"Tilts the push upward so fluid sprays up into the air instead of only sideways.");
		num("tntScatter", "Scatter", Cost.NONE, LOOK, 0, 1, 0.01,
			"Randomness in the push direction, so the splash breaks up instead of moving as one shell.");

		category("Sounds");
		toggle("flowSounds", "Water sounds", Cost.LOW, "Plays a few sounds per second near moving water.",
			"Flowing and splashing sounds from moving water.");
		num("flowSoundVolume", "Fluid sound volume", Cost.NONE, "Only changes volume.", 0, 3, 0.05,
			"Volume of all realistic water, lava, slime and sand sounds.");

		category("Shader packs");
		toggle("vitrailInsidePack", "Inside Vitrail packs (experimental)", Cost.LOW,
			"Shades fluids twice per pixel while on; falls back to drawing on top if Vitrail changes.",
			"With a Vitrail shader pack running, draws fluids into the pack's image before its effects, so its fog, bloom, tone mapping "
				+ "and colour grading reach them. Fluids then can't refract what's behind them, and glass behind water is drawn over it. "
				+ "Off draws fluids over the finished pack image instead.");

		category("Clip recording");
		integer("renderMaxStepsPerFrame", "Max substeps per clip frame", Cost.HIGH, "Only while /water render records; higher makes recording slower but more accurate.", 1, 256,
			"Upper limit on substeps per frame while recording a clip with /water render.");
		text("ffmpeg", "ffmpeg path", Cost.NONE, "Only used when a clip finishes.",
			"Full path to ffmpeg for /water render. Leave empty to look in the usual places.");
		toggle("renderFreezeWorld", "Freeze world while recording", Cost.NONE, "Only while recording.",
			"Stops the world ticking while a clip records, so mobs and time don't run ahead of the slowed-down simulation.");
		toggle("renderSteadyPreview", "Steady preview", Cost.NONE, "Only while recording.",
			"Paces the on-screen preview while recording so it plays as smooth slow motion instead of stuttering.");
		num("renderCameraSmoothing", "Camera smoothing", Cost.NONE, "Only while recording.", 0, 1, 0.01,
			"Seconds of smoothing on mouse look while recording, so pans in the clip come out even.");

		category("Other");
		for (String name : WaterSettings.fields().keySet()) {
			if (!BY_NAME.containsKey(name)) {
				RealisticWater.LOGGER.warn("Setting {} has no description in the settings menu", name);
				add(new Info(name, category, name, Kind.TEXT, Cost.NONE, "", "No description yet.", 0, 0, 0));
			}
		}
		if (BY_CATEGORY.get("Other").isEmpty()) {
			BY_CATEGORY.remove("Other");
		}
	}
}
