package me.skaffy.mixin;

import me.skaffy.block.PistonPassable;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PistonBaseBlock.class)
public class PistonBaseBlockMixin {
	@Redirect(method = "isPushable", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"))
	private static boolean realisticWater$passableIsAir(BlockState state) {
		return PistonPassable.is(state);
	}
}
