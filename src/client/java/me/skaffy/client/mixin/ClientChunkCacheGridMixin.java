package me.skaffy.client.mixin;

import me.skaffy.client.realistic.ClientGrid;
import me.skaffy.realistic.GridSpace;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheGridMixin {
	@Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;",
		at = @At("HEAD"), cancellable = true)
	private void realisticWater$gridChunk(final int x, final int z, final ChunkStatus status, final boolean loadOrGenerate,
		final CallbackInfoReturnable<LevelChunk> info) {
		if (!GridSpace.isGridChunk(x)) {
			return;
		}
		info.setReturnValue(loadOrGenerate ? ClientGrid.chunkOrCreate(x, z) : ClientGrid.chunk(x, z));
	}
}
