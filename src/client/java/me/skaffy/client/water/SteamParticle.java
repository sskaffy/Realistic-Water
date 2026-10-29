package me.skaffy.client.water;

import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class SteamParticle extends SingleQuadParticle {
	private static final double BUOYANCY = 0.006;
	private static final double DRAG = 0.9;
	private static final double SWIRL = 0.006;
	private static final double ENTITY_DRAG = 0.35;
	private static final double ENTITY_PUSH = 0.03;
	private static final double SPREAD = 0.04;

	private final SpriteSet sprites;
	private final float startSize;

	SteamParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd, SpriteSet sprites) {
		super(level, x, y, z, sprites.first());
		this.sprites = sprites;
		this.xd = xd;
		this.yd = yd + 0.02;
		this.zd = zd;
		boolean dark = this.random.nextFloat() < 0.35F;
		float shade = dark ? 0.38F + this.random.nextFloat() * 0.17F : 0.82F + this.random.nextFloat() * 0.16F;
		this.rCol = shade;
		this.gCol = shade;
		this.bCol = shade;
		this.startSize = this.quadSize * (1.2F + this.random.nextFloat() * 1.0F);
		this.quadSize = this.startSize;
		this.lifetime = 50 + this.random.nextInt(50);
		this.hasPhysics = true;
		this.setSize(0.25F, 0.25F);
		this.setSpriteFromAge(sprites);
	}

	@Override
	public SingleQuadParticle.FacingCameraMode getFacingCameraMode() {
		return SingleQuadParticle.FacingCameraMode.LOOKAT_XYZ;
	}

	@Override
	protected SingleQuadParticle.Layer getLayer() {
		return SingleQuadParticle.Layer.TRANSLUCENT;
	}

	@Override
	public float getQuadSize(float a) {
		float t = Mth.clamp((this.age + a) / this.lifetime, 0.0F, 1.0F);
		return this.startSize * (0.5F + 1.2F * t);
	}

	@Override
	public void tick() {
		this.xo = this.x;
		this.yo = this.y;
		this.zo = this.z;
		if (this.age++ >= this.lifetime) {
			this.remove();
			return;
		}
		float t = (float) this.age / this.lifetime;
		double heat = 1.0 - t;
		this.yd += BUOYANCY * heat;
		this.xd += (this.random.nextDouble() - 0.5) * SWIRL;
		this.zd += (this.random.nextDouble() - 0.5) * SWIRL;
		this.pushedByEntities();
		this.move(this.xd, this.yd, this.zd);
		this.xd *= DRAG;
		this.yd *= DRAG;
		this.zd *= DRAG;
		this.alpha = t < 0.7F ? 0.85F : 0.85F * (1.0F - (t - 0.7F) / 0.3F);
		this.setSpriteFromAge(this.sprites);
	}

	private void pushedByEntities() {
		AABB box = this.getBoundingBox().inflate(0.15);
		List<Entity> near = this.level.getEntities((Entity) null, box, e -> !e.isSpectator());
		for (Entity e : near) {
			double vx = e.getX() - e.xo;
			double vy = e.getY() - e.yo;
			double vz = e.getZ() - e.zo;
			this.xd += (vx - this.xd) * ENTITY_DRAG;
			this.yd += (vy - this.yd) * ENTITY_DRAG * 0.5;
			this.zd += (vz - this.zd) * ENTITY_DRAG;
			double dx = this.x - e.getX();
			double dz = this.z - e.getZ();
			double d = Math.sqrt(dx * dx + dz * dz);
			if (d > 1.0E-4) {
				this.xd += dx / d * ENTITY_PUSH;
				this.zd += dz / d * ENTITY_PUSH;
			}
		}
	}

	@Override
	public void move(double xa, double ya, double za) {
		Vec3 wanted = new Vec3(xa, ya, za);
		Vec3 allowed = wanted.lengthSqr() < 1.0E-12 ? wanted
			: Entity.collideBoundingBox(CollisionContext.positionContext(this.y), wanted, this.getBoundingBox(), this.level, List.of());
		if (allowed.lengthSqr() > 0.0) {
			this.setBoundingBox(this.getBoundingBox().move(allowed));
			this.setLocationFromBoundingbox();
		}
		boolean hitX = allowed.x != xa;
		boolean hitY = allowed.y != ya;
		boolean hitZ = allowed.z != za;
		this.onGround = hitY && ya < 0.0;
		if (hitY) {
			double away = Math.abs(ya) + SPREAD;
			double angle = this.random.nextDouble() * Math.PI * 2.0;
			this.xd += Math.cos(angle) * away * 0.5;
			this.zd += Math.sin(angle) * away * 0.5;
			this.yd = 0.0;
		}
		if (hitX) {
			this.yd += Math.abs(xa) * 0.5;
			this.zd += (this.random.nextDouble() - 0.5) * Math.abs(xa);
			this.xd = -this.xd * 0.2;
		}
		if (hitZ) {
			this.yd += Math.abs(za) * 0.5;
			this.xd += (this.random.nextDouble() - 0.5) * Math.abs(za);
			this.zd = -this.zd * 0.2;
		}
	}

	public static final class Provider implements ParticleProvider<SimpleParticleType> {
		private final SpriteSet sprites;

		public Provider(SpriteSet sprites) {
			this.sprites = sprites;
		}

		@Override
		public Particle createParticle(SimpleParticleType options, ClientLevel level, double x, double y, double z, double xAux, double yAux, double zAux,
			RandomSource random) {
			return new SteamParticle(level, x, y, z, xAux, yAux, zAux, this.sprites);
		}
	}
}
