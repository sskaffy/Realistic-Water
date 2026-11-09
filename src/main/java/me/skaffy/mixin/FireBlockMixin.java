package me.skaffy.mixin;

import me.skaffy.ModContent;
import me.skaffy.block.RealisticFireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
	/**
	 * Vanilla fire rebuilds itself (aging, neighbour updates, spreading) through this method, which always
	 * returns the vanilla fire block. Keep realistic fire realistic, including the flames it spreads to.
	 */
	@Inject(method = "getStateWithAge", at = @At("RETURN"), cancellable = true)
	private void realisticWater$keepRealistic(LevelReader level, BlockPos pos, int age, CallbackInfoReturnable<BlockState> cir) {
		if ((Object) this instanceof RealisticFireBlock) {
			BlockState result = cir.getReturnValue();
			if (result.is(Blocks.FIRE)) {
				cir.setReturnValue(RealisticFireBlock.fromVanilla(result));
			} else if (result.is(Blocks.SOUL_FIRE)) {
				cir.setReturnValue(ModContent.REALISTIC_SOUL_FIRE.defaultBlockState());
			}
		}
	}
}
