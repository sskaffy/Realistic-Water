package me.skaffy.mixin;

import java.util.concurrent.CompletableFuture;
import me.skaffy.realistic.GridSpace;
import net.minecraft.util.StaticCache2D;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatusTasks.class)
public abstract class ChunkStatusTasksMixin {
	@Inject(method = {"buildTerrain", "generateFeatures", "generateSpawn", "generateStructureReferences"}, at = @At("HEAD"), cancellable = true)
	private static void realisticWater$skipGridChunkGeneration(final WorldGenContext context, final ChunkStep step,
		final StaticCache2D<GenerationChunkHolder> chunks, final ChunkAccess chunk, final CallbackInfoReturnable<CompletableFuture<ChunkAccess>> info) {
		if (GridSpace.isGridChunk(chunk.getPos())) {
			info.setReturnValue(CompletableFuture.completedFuture(chunk));
		}
	}
}
