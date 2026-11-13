package me.skaffy.mixin;

import me.skaffy.ModContent;
import me.skaffy.ModSettings;
import me.skaffy.block.RealisticSnowBlock;
import me.skaffy.block.SnowOrigin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public class ServerLevelSnowfallMixin {
	@Inject(method = "tickPrecipitation", at = @At("HEAD"))
	private void realisticWater$realisticSnowfall(BlockPos pos, CallbackInfo ci) {
		ServerLevel level = (ServerLevel) (Object) this;
		if (!ModSettings.realisticSnow || !level.isRaining()) {
			return;
		}
		int maxHeight = level.getGameRules().get(GameRules.MAX_SNOW_ACCUMULATION_HEIGHT);
		if (maxHeight <= 0) {
			return;
		}
		BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
		Biome biome = level.getBiome(top).value();
		if (biome.getPrecipitationAt(top, level.getSeaLevel()) != Biome.Precipitation.SNOW || !level.isInsideBuildHeight(top.getY())
			|| level.getBrightness(LightLayer.BLOCK, top) >= 10) {
			return;
		}
		BlockState state = level.getBlockState(top);
		int layers = RealisticSnowBlock.layers(state);
		if (state.is(Blocks.SNOW)) {
			layers = state.getValue(SnowLayerBlock.LAYERS);
		} else if (layers == 0 && !state.isAir()) {
			return;
		}
		if (layers >= Math.min(maxHeight, RealisticSnowBlock.MAX_LAYERS)) {
			return;
		}
		SnowOrigin origin = state.getBlock() instanceof RealisticSnowBlock ? state.getValue(RealisticSnowBlock.ORIGIN) : SnowOrigin.LAYER;
		BlockState next = ModContent.REALISTIC_SNOW.defaultBlockState().setValue(RealisticSnowBlock.LAYERS, layers + 1)
			.setValue(RealisticSnowBlock.ORIGIN, origin);
		if (!next.canSurvive(level, top)) {
			return;
		}
		Block.pushEntitiesUp(state, next, level, top);
		level.setBlockAndUpdate(top, next);
	}

	@Redirect(method = "tickPrecipitation", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/biome/Biome;shouldSnow(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Z"))
	private boolean realisticWater$noVanillaSnowLayers(Biome biome, LevelReader level, BlockPos pos) {
		return !ModSettings.realisticSnow && biome.shouldSnow(level, pos);
	}
}
