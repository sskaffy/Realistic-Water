package me.skaffy.mixin;

import me.skaffy.realistic.GridSpace;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class ChunkMapSaveMixin {
	@Inject(method = "save", at = @At("HEAD"), cancellable = true)
	private void realisticWater$neverSaveGridChunks(final ChunkAccess chunk, final CallbackInfoReturnable<Boolean> info) {
		if (GridSpace.isGridChunk(chunk.getPos())) {
			info.setReturnValue(false);
		}
	}
}
