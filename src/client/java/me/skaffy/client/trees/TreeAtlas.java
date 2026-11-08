package me.skaffy.client.trees;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import me.skaffy.RealisticWater;
import me.skaffy.client.vk.VkBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import org.jspecify.annotations.Nullable;

final class TreeAtlas implements AutoCloseable {
	static final Identifier ID = RealisticWater.id("trees/atlas");
	static final Identifier SHADOW_ID = RealisticWater.id("trees/atlas_shadow");
	static final Identifier FALLEN_ID = RealisticWater.id("trees/atlas_fallen");
	static final int CELL = 32;
	static final int CELLS = 16;
	static final int SIZE = CELL * CELLS;
	static final int REALISTIC_LEAVES = 4;
	static final int WHITE = 4;
	private static final int FIRST_FREE = 5;

	record Kind(int leafCell, int barkCell, int leafColor, int barkColor) {
	}

	private final Map<Block, Kind> kinds = new IdentityHashMap<>();
	private final Kind fallback;
	private final @Nullable DynamicTexture texture;
	private final @Nullable DynamicTexture shadowTexture;
	private final @Nullable DynamicTexture fallenTexture;
	final VkBuf pixels;
	final BlockStateModelSet builtFrom;

	TreeAtlas(Minecraft mc) {
		this.builtFrom = mc.getModelManager().getBlockStateModelSet();
		NativeImage image = new NativeImage(SIZE, SIZE, true);
		for (int v = 0; v < REALISTIC_LEAVES; v++) {
			drawRealisticLeaf(image, v);
		}
		fillCell(image, WHITE, 0xFFFFFFFF);
		int next = FIRST_FREE;
		Map<Identifier, int[]> loaded = new java.util.HashMap<>();
		Kind oak = null;
		for (Block block : BuiltInRegistries.BLOCK) {
			if (!(block instanceof LeavesBlock)) {
				continue;
			}
			if (next + 2 > CELLS * CELLS) {
				break;
			}
			Identifier leafSprite = sprite(this.builtFrom, block);
			Identifier barkSprite = sprite(this.builtFrom, logFor(block));
			int[] leaf = loaded.computeIfAbsent(leafSprite, id -> readSprite(id));
			int[] bark = loaded.computeIfAbsent(barkSprite, id -> readSprite(id));
			if (leaf[0] < 0) {
				leaf[0] = next;
				copyInto(image, leaf, next++);
			}
			if (bark[0] < 0) {
				bark[0] = next;
				copyInto(image, bark, next++);
			}
			Kind kind = new Kind(leaf[0], bark[0], leaf[1], bark[1]);
			this.kinds.put(block, kind);
			if (block == Blocks.OAK_LEAVES) {
				oak = kind;
			}
		}
		this.fallback = oak != null ? oak : new Kind(0, WHITE, 0xFF6B9A3A, 0xFF6B5034);
		this.pixels = VkBuf.upload((long) SIZE * SIZE * 4L, VkBuf.STORAGE);
		ByteBuffer m = this.pixels.mapped();
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				m.putInt((x + y * SIZE) * 4, image.getPixel(x, y));
			}
		}
		this.pixels.flush(0, (long) SIZE * SIZE * 4L);
		DynamicTexture tex = null;
		DynamicTexture shadowTex = null;
		DynamicTexture fallenTex = null;
		NativeImage copy = new NativeImage(SIZE, SIZE, false);
		NativeImage fallenCopy = new NativeImage(SIZE, SIZE, false);
		for (int y = 0; y < SIZE; y++) {
			for (int x = 0; x < SIZE; x++) {
				copy.setPixel(x, y, image.getPixel(x, y));
				fallenCopy.setPixel(x, y, image.getPixel(x, y));
			}
		}
		try {
			tex = new DynamicTexture(() -> "Realistic tree leaves", image);
			mc.getTextureManager().register(ID, tex);
			shadowTex = new DynamicTexture(() -> "Realistic tree leaves (shadows)", copy);
			mc.getTextureManager().register(SHADOW_ID, shadowTex);
			fallenTex = new DynamicTexture(() -> "Fallen tree leaves", fallenCopy);
			mc.getTextureManager().register(FALLEN_ID, fallenTex);
		} catch (RuntimeException e) {
			RealisticWater.LOGGER.warn("Couldn't register the tree leaf texture; shader packs won't draw realistic leaves", e);
			if (tex == null) {
				image.close();
			}
			if (shadowTex == null) {
				copy.close();
			}
			if (fallenTex == null) {
				fallenCopy.close();
			}
		}
		this.texture = tex;
		this.shadowTexture = shadowTex;
		this.fallenTexture = fallenTex;
	}

	Kind kind(Block block) {
		return this.kinds.getOrDefault(block, this.fallback);
	}

	private static Block logFor(Block leaves) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(leaves);
		String path = id.getPath();
		String stem = path.endsWith("_leaves") ? path.substring(0, path.length() - "_leaves".length()) : path;
		if (stem.contains("azalea")) {
			return Blocks.OAK_LOG;
		}
		for (String suffix : new String[]{"_log", "_stem", "_wood"}) {
			Block log = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath(id.getNamespace(), stem + suffix));
			if (log != Blocks.AIR) {
				return log;
			}
		}
		return Blocks.OAK_LOG;
	}

	private static Identifier sprite(BlockStateModelSet models, Block block) {
		TextureAtlasSprite sprite = models.getParticleMaterial(block.defaultBlockState()).sprite();
		Identifier name = sprite.contents().name();
		return Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
	}

	private static int[] readSprite(Identifier id) {
		int[] out = new int[2 + CELL * CELL];
		out[0] = -1;
		try (InputStream in = Minecraft.getInstance().getResourceManager().open(id); NativeImage img = NativeImage.read(in)) {
			int w = img.getWidth();
			int h = Math.min(img.getHeight(), w);
			long r = 0;
			long g = 0;
			long b = 0;
			long n = 0;
			for (int y = 0; y < CELL; y++) {
				for (int x = 0; x < CELL; x++) {
					int argb = img.getPixel(x * w / CELL, y * h / CELL);
					out[2 + x + y * CELL] = argb;
					if ((argb >>> 24) > 127) {
						r += (argb >> 16) & 0xFF;
						g += (argb >> 8) & 0xFF;
						b += argb & 0xFF;
						n++;
					}
				}
			}
			out[1] = n == 0 ? 0xFF808080 : 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
		} catch (Exception e) {
			RealisticWater.LOGGER.warn("Couldn't read {} for realistic tree leaves", id, e);
			java.util.Arrays.fill(out, 2, out.length, 0xFF808080);
			out[1] = 0xFF808080;
		}
		return out;
	}

	private static void copyInto(NativeImage atlas, int[] sprite, int cell) {
		int cx = (cell % CELLS) * CELL;
		int cy = (cell / CELLS) * CELL;
		for (int y = 0; y < CELL; y++) {
			for (int x = 0; x < CELL; x++) {
				atlas.setPixel(cx + x, cy + y, sprite[2 + x + y * CELL]);
			}
		}
	}

	private static void fillCell(NativeImage atlas, int cell, int argb) {
		int cx = (cell % CELLS) * CELL;
		int cy = (cell / CELLS) * CELL;
		for (int y = 0; y < CELL; y++) {
			for (int x = 0; x < CELL; x++) {
				atlas.setPixel(cx + x, cy + y, argb);
			}
		}
	}

	private static void drawRealisticLeaf(NativeImage atlas, int variant) {
		int cx = (variant % CELLS) * CELL;
		int cy = (variant / CELLS) * CELL;
		float width = new float[]{0.40F, 0.33F, 0.46F, 0.36F}[variant];
		float point = new float[]{0.85F, 1.2F, 0.7F, 1.0F}[variant];
		float bend = new float[]{0.0F, 0.05F, -0.04F, 0.03F}[variant];
		int samples = 4;
		for (int py = 0; py < CELL; py++) {
			for (int px = 0; px < CELL; px++) {
				int inside = 0;
				float shadeSum = 0.0F;
				for (int sy = 0; sy < samples; sy++) {
					for (int sx = 0; sx < samples; sx++) {
						float u = (px + (sx + 0.5F) / samples) / CELL * 2.0F - 1.0F;
						float t = 1.0F - (py + (sy + 0.5F) / samples) / CELL;
						float blade = (t - 0.12F) / 0.86F;
						float mid = bend * (float) Math.sin(Math.PI * Math.max(blade, 0.0F));
						float x = u - mid;
						float shade;
						if (blade < 0.0F) {
							if (Math.abs(x) > 0.045F || t < 0.02F) {
								continue;
							}
							shade = 0.8F;
						} else if (blade > 1.0F) {
							continue;
						} else {
							float half = width * (float) Math.pow(Math.sin(Math.PI * Math.pow(blade, 0.75)), point) * (1.0F - 0.2F * blade);
							if (Math.abs(x) > half) {
								continue;
							}
							float across = Math.abs(x) / Math.max(half, 1e-3F);
							shade = 0.92F - 0.16F * across * across + 0.06F * blade;
							if (Math.abs(x) < 0.03F) {
								shade += 0.14F;
							}
							float vein = (float) Math.abs(Math.sin((blade * 7.0F - Math.abs(x) * 5.0F) * Math.PI));
							if (vein < 0.12F && across < 0.9F) {
								shade += 0.07F;
							}
						}
						inside++;
						shadeSum += shade;
					}
				}
				int argb = 0;
				if (inside * 2 >= samples * samples) {
					int grey = Math.clamp(Math.round(shadeSum / inside * 255.0F), 0, 255);
					argb = 0xFF000000 | grey << 16 | grey << 8 | grey;
				}
				atlas.setPixel(cx + px, cy + py, argb);
			}
		}
	}

	@Override
	public void close() {
		if (this.texture != null) {
			Minecraft.getInstance().getTextureManager().release(ID);
		}
		if (this.shadowTexture != null) {
			Minecraft.getInstance().getTextureManager().release(SHADOW_ID);
		}
		if (this.fallenTexture != null) {
			Minecraft.getInstance().getTextureManager().release(FALLEN_ID);
		}
		me.skaffy.client.vk.Vk.destroyLater(this.pixels);
	}
}
