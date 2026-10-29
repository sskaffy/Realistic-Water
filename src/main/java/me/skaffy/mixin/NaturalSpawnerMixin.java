package me.skaffy.mixin;

import java.util.List;
import me.skaffy.realistic.GridSpace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
	@Inject(method = "spawnForChunk", at = @At("HEAD"), cancellable = true)
	private static void realisticWater$noSpawningInGrid(final ServerLevel level, final LevelChunk chunk, final NaturalSpawner.SpawnState state,
		final List<MobCategory> spawningCategories, final CallbackInfo info) {
		if (GridSpace.isGridChunk(chunk.getPos())) {
			info.cancel();
		}
	}
}
