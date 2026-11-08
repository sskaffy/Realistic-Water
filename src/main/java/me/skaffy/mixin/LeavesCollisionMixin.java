package me.skaffy.mixin;

import me.skaffy.trees.LeafSinking;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.class)
public abstract class LeavesCollisionMixin {
	@Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
	private void realisticWater$sinkIntoLeaves(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
		CallbackInfoReturnable<VoxelShape> cir) {
		if ((Object) this instanceof LeavesBlock && context instanceof EntityCollisionContext ec) {
			VoxelShape shape = LeafSinking.shape(level, pos, ec.getEntity());
			if (shape != null) {
				cir.setReturnValue(shape);
			}
		}
	}
}
