package me.skaffy.mixin;

import me.skaffy.ocean.OceanServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SerializableChunkData.class)
public abstract class SerializableChunkDataMixin {
	@Inject(method = "copyOf", at = @At("RETURN"))
	private static void realisticWater$saveCalmSea(final ServerLevel level, final ChunkAccess chunk, final CallbackInfoReturnable<SerializableChunkData> info) {
		OceanServer.cleanForSave(info.getReturnValue());
	}
}
