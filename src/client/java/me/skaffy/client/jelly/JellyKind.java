package me.skaffy.client.jelly;

import me.skaffy.block.WobblyHoneyBlock;
import me.skaffy.block.WobblySlimeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public enum JellyKind {
	SLIME(60.0F, 650.0F, 3.5F, 0.18F, 0.9F, 0.0F, 0.55F, 0.4F, 1.0, 0.0),
	HONEY(14.0F, 450.0F, 12.0F, 0.12F, 0.3F, 0.85F, 0.35F, 0.22F, 0.9375, 0.0625);

	final float stiffness;
	final float coupling;
	final float damping;
	final float press;
	final float impact;
	final float lift;
	final float bulge;
	final float maxDisplacement;
	final double top;
	final double inset;

	JellyKind(float stiffness, float coupling, float damping, float press, float impact, float lift, float bulge, float maxDisplacement, double top,
		double inset) {
		this.stiffness = stiffness;
		this.coupling = coupling;
		this.damping = damping;
		this.press = press;
		this.impact = impact;
		this.lift = lift;
		this.bulge = bulge;
		this.maxDisplacement = maxDisplacement;
		this.top = top;
		this.inset = inset;
	}

	public static @Nullable JellyKind of(BlockState state) {
		if (state.getBlock() instanceof WobblySlimeBlock) {
			return SLIME;
		}
		return state.getBlock() instanceof WobblyHoneyBlock ? HONEY : null;
	}
}
