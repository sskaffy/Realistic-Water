package me.skaffy.mixin;

import me.skaffy.realistic.GridSpace;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
public abstract class LevelBlockEntityGridMixin {
	@Inject(method = "blockEntityChanged", at = @At("HEAD"), cancellable = true)
	private void realisticWater$gridBlockEntityChanged(final BlockPos pos, final CallbackInfo info) {
		if (!GridSpace.isGridPos(pos)) {
			return;
		}
		info.cancel();
		RealisticBlockEntity body = GridSpace.bodyAt((Level) (Object) this, pos);
		if (body != null) {
			body.gridBlockEntityChanged(pos);
		}
	}
}
