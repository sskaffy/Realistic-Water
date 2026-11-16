package me.skaffy.mixin;

import me.skaffy.block.PistonPassable;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PistonStructureResolver.class)
public class PistonStructureResolverMixin {
	@Redirect(method = "addBlockLine", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"))
	private boolean realisticWater$passableIsAir(BlockState state) {
		return PistonPassable.is(state);
	}
}
