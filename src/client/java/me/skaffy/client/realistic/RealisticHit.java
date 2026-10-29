package me.skaffy.client.realistic;

import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public final class RealisticHit extends EntityHitResult {
	public final int x;
	public final int y;
	public final int z;
	public final int face;
	public final org.joml.Vector3d local;

	public RealisticHit(RealisticBlockEntity entity, Vec3 location, int x, int y, int z, int face, org.joml.Vector3d local) {
		super(entity, location);
		this.x = x;
		this.y = y;
		this.z = z;
		this.face = face;
		this.local = local;
	}

	public RealisticBlockEntity body() {
		return (RealisticBlockEntity) this.getEntity();
	}
}
