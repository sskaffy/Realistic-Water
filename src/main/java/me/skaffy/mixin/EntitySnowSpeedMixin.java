package me.skaffy.mixin;

import me.skaffy.block.RealisticSnowBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntitySnowSpeedMixin {
	@Inject(method = "getBlockSpeedFactor", at = @At("RETURN"), cancellable = true)
	private void realisticWater$deepSnow(CallbackInfoReturnable<Float> cir) {
		Entity self = (Entity) (Object) this;
		BlockPos pos = self.blockPosition();
		BlockState state = self.level().getBlockState(pos);
		if (state.getBlock() instanceof RealisticSnowBlock) {
			cir.setReturnValue(cir.getReturnValueF() * RealisticSnowBlock.speedFactor(state, self.level(), pos));
		}
	}
}
