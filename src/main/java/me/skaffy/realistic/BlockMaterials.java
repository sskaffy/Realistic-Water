package me.skaffy.realistic;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.skaffy.block.WaveMakerBlock;
import me.skaffy.physics.RigidBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

public final class BlockMaterials {
	public static final double WEIGHT = 0.5;

	public static volatile double gravity = 9.81;

	private static volatile Map<Block, Double> gravityOverrides = Map.of();

	public static void setGravityOverrides(String text) {
		Map<Block, Double> parsed = new java.util.HashMap<>();
		for (String entry : text.split("[,;\n]")) {
			String[] halves = entry.trim().split("=");
			if (halves.length != 2) {
				continue;
			}
			Identifier id = Identifier.tryParse(halves[0].trim());
			if (id == null) {
				continue;
			}
			Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
			if (block == null) {
				continue;
			}
			try {
				parsed.put(block, Double.parseDouble(halves[1].trim()));
			} catch (NumberFormatException ignored) {
			}
		}
		gravityOverrides = Map.copyOf(parsed);
	}

	public static boolean hasGravityOverrides() {
		return !gravityOverrides.isEmpty();
	}

	public static double gravityFor(BlockState state) {
		Double own = gravityOverrides.get(state.getBlock());
		return own != null ? own : gravity;
	}

	public static double gravityOf(RigidBody body) {
		if (gravityOverrides.isEmpty() || body.mass <= 0.0) {
			return gravity;
		}
		double weighted = 0.0;
		double total = 0.0;
		for (RigidBody.Cell cell : body.cells) {
			double w = cell.density * cell.volume;
			total += w;
			weighted += w * (cell.payload instanceof BlockState state ? gravityFor(state) : gravity);
		}
		return total > 0.0 ? weighted / total : gravity;
	}

	private static final Set<SoundType> METAL = Set.of(SoundType.METAL, SoundType.ANVIL, SoundType.NETHERITE_BLOCK, SoundType.ANCIENT_DEBRIS,
		SoundType.LODESTONE, SoundType.CHAIN, SoundType.COPPER, SoundType.COPPER_BULB, SoundType.COPPER_GRATE, SoundType.HEAVY_CORE, SoundType.IRON,
		SoundType.LANTERN);
	private static final Set<SoundType> LOOSE = Set.of(SoundType.SAND, SoundType.GRAVEL, SoundType.GRASS, SoundType.ROOTED_DIRT, SoundType.MUD,
		SoundType.SOUL_SAND, SoundType.SOUL_SOIL, SoundType.SUSPICIOUS_SAND, SoundType.SUSPICIOUS_GRAVEL, SoundType.MOSS);

	private static final Set<Block> FEATHER = Set.of(Blocks.OAK_PLANKS, Blocks.OAK_STAIRS, Blocks.OAK_LOG, Blocks.COBBLESTONE, Blocks.COBBLESTONE_SLAB,
		Blocks.COBBLESTONE_STAIRS, Blocks.COBBLESTONE_WALL, Blocks.GLASS, Blocks.TORCH, Blocks.WALL_TORCH);

	private BlockMaterials() {
	}

	public static double density(BlockState state) {
		SoundType sound = state.getSoundType();
		if (FEATHER.contains(state.getBlock())) {
			return 50.0;
		}
		if (state.is(BlockTags.WOOL) || state.getBlock() instanceof WaveMakerBlock || state.is(Blocks.REDSTONE_BLOCK)) {
			return 250.0;
		}
		if (state.is(BlockTags.LEAVES)) {
			return 300.0;
		}
		if (state.is(BlockTags.LOGS)) {
			return 700.0;
		}
		if (state.is(BlockTags.PLANKS)) {
			return 600.0;
		}
		if (state.is(BlockTags.ICE)) {
			return 917.0;
		}
		if (state.is(BlockTags.SNOW) || sound == SoundType.SNOW || sound == SoundType.POWDER_SNOW) {
			return 400.0;
		}
		if (sound == SoundType.SPONGE || sound == SoundType.WET_SPONGE || sound == SoundType.SLIME_BLOCK || sound == SoundType.HONEY_BLOCK) {
			return sound == SoundType.SPONGE ? 150.0 : 1100.0;
		}
		if (METAL.contains(sound)) {
			return 7800.0;
		}
		if (state.ignitedByLava()) {
			return 550.0;
		}
		if (sound == SoundType.GLASS) {
			return 2500.0;
		}
		if (LOOSE.contains(sound)) {
			return 1600.0;
		}
		return 2500.0;
	}

	public static boolean flammable(BlockState state) {
		return state.ignitedByLava() || state.is(BlockTags.WOOL) || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS);
	}

	public static double grip(float vanillaFriction) {
		return Math.clamp((1.0 - vanillaFriction) / 0.4, 0.05, 1.5);
	}

	public static RigidBody.Cell cell(int x, int y, int z, BlockState state) {
		return new RigidBody.Cell(x, y, z, density(state) * WEIGHT, flammable(state), grip(state.getBlock().getFriction()), state, solidBoxes(state));
	}

	private static final double[][] FULL = {{0, 0, 0, 1, 1, 1}};
	private static final double[][] NONE = new double[0][];
	private static final Map<BlockState, double[][]> SOLID = new ConcurrentHashMap<>();
	private static final Map<BlockState, double[][]> COLLISION = new ConcurrentHashMap<>();

	public static double @Nullable [][] solidBoxes(BlockState state) {
		double[][] boxes = SOLID.computeIfAbsent(state, s -> {
			if (s.getBlock() instanceof RedstoneWireBlock) {
				s = s.getBlock().defaultBlockState();
			}
			double[][] b = boxes(s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
			if (b.length == 0) {
				b = boxes(s.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
			}
			return b.length == 0 ? FULL : b;
		});
		return boxes == FULL ? null : boxes;
	}

	public static double[][] collisionBoxes(BlockState state) {
		return COLLISION.computeIfAbsent(state, s -> boxes(s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
	}

	private static double[][] boxes(VoxelShape shape) {
		if (shape.isEmpty()) {
			return NONE;
		}
		List<AABB> list = shape.toAabbs();
		double[][] out = new double[list.size()][];
		for (int i = 0; i < list.size(); i++) {
			AABB b = list.get(i);
			out[i] = new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ};
		}
		return out;
	}
}
