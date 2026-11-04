package me.skaffy.client.compat;

import dev.vitrail.render.BlockStateIds;
import dev.vitrail.uniform.UniformCatalog;
import dev.vitrail.uniform.UniformShape;
import dev.vitrail.uniform.UniformSource;
import dev.vitrail.uniform.Val;
import dev.vitrail.uniform.WorldState;
import net.minecraft.world.level.block.Blocks;

public final class VitrailSeaUniforms {
	private VitrailSeaUniforms() {
	}

	public static void register(UniformCatalog.Builder builder) {
		builder.add(VitrailSea.CAM, UniformShape.VEC4, (world, out) -> {
			VitrailSea.ensure(world.frameCounter());
			float[] c = VitrailSea.cam;
			out.set(c[0], c[1], c[2], c[3]);
		});
		builder.add(VitrailSea.GRID_INFO, UniformShape.IVEC4, (world, out) -> {
			VitrailSea.ensure(world.frameCounter());
			int[] g = VitrailSea.grid;
			out.set(g[0], g[1], g[2], BlockStateIds.packedFluid(Blocks.WATER.defaultBlockState()));
		});
		builder.add(VitrailSea.WAVE, UniformShape.VEC4, new UniformSource() {
			@Override
			public void read(WorldState world, Val out) {
				this.read(world, out, 0);
			}

			@Override
			public void read(WorldState world, Val out, int element) {
				VitrailSea.ensure(world.frameCounter());
				int i = Math.clamp(element, 0, VitrailSea.WAVES - 1) * 4;
				float[] w = VitrailSea.waves;
				out.set(w[i], w[i + 1], w[i + 2], w[i + 3]);
			}
		});
		builder.add(VitrailSea.PHASE, UniformShape.VEC4, new UniformSource() {
			@Override
			public void read(WorldState world, Val out) {
				this.read(world, out, 0);
			}

			@Override
			public void read(WorldState world, Val out, int element) {
				VitrailSea.ensure(world.frameCounter());
				int i = Math.clamp(element, 0, VitrailSea.WAVES / 4 - 1) * 4;
				float[] p = VitrailSea.phases;
				out.set(p[i], p[i + 1], p[i + 2], p[i + 3]);
			}
		});
		builder.add(VitrailSea.COLUMNS, UniformShape.IVEC4, new UniformSource() {
			@Override
			public void read(WorldState world, Val out) {
				this.read(world, out, 0);
			}

			@Override
			public void read(WorldState world, Val out, int element) {
				VitrailSea.ensure(world.frameCounter());
				int i = Math.clamp(element, 0, VitrailSea.GRID * VitrailSea.GRID / 4 - 1) * 4;
				int[] c = VitrailSea.columns;
				out.set(c[i], c[i + 1], c[i + 2], c[i + 3]);
			}
		});
		builder.add(VitrailSea.FAR_INFO, UniformShape.IVEC4, (world, out) -> {
			VitrailSea.ensure(world.frameCounter());
			int[] f = VitrailSea.farInfo;
			out.set(f[0], f[1], f[2], Math.max(1, f[3]));
		});
		builder.add(VitrailSea.FAR, UniformShape.IVEC4, new UniformSource() {
			@Override
			public void read(WorldState world, Val out) {
				this.read(world, out, 0);
			}

			@Override
			public void read(WorldState world, Val out, int element) {
				VitrailSea.ensure(world.frameCounter());
				int i = Math.clamp(element, 0, VitrailSea.FAR_INTS / 4 - 1) * 4;
				int[] c = VitrailSea.far;
				out.set(c[i], c[i + 1], c[i + 2], c[i + 3]);
			}
		});
	}
}
