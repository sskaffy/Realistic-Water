package me.skaffy.client.realistic;

import java.util.ArrayList;
import java.util.List;
import me.skaffy.net.BodyPushPayload;
import me.skaffy.net.BodyStatePayload;
import me.skaffy.physics.PhysicsWorld;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.physics.RigidBody;
import me.skaffy.physics.SweptCollider;
import me.skaffy.realistic.BlockMaterials;
import me.skaffy.realistic.RealisticBlockEntity;
import me.skaffy.realistic.RealisticCollision;
import me.skaffy.realistic.RealisticPhysics;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

public final class ClientBodies {
	private static final double RANGE = 64.0;
	private static final double KICK_BOUNCE = 0.6;
	private static final Direction.Axis[] AXES = {Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z};
	private static final double LOOKAHEAD = 0.02;

	private static PhysicsWorld world = RealisticPhysics.newWorld();
	private static RealisticPhysics.@Nullable LevelView view;
	private static @Nullable ClientLevel viewLevel;
	private static long tick;
	private static boolean stepped;
	private static final List<BodyPushPayload> incoming = new ArrayList<>();
	private static final List<RealisticBlockEntity> simulated = new ArrayList<>();

	private ClientBodies() {
	}

	public static void startTick(Minecraft mc) {
		tick++;
		stepped = false;
	}

	public static void receive(BodyPushPayload push) {
		incoming.add(push);
	}

	public static void beforePlayerMove(LocalPlayer player, Vec3 movement) {
		Minecraft mc = Minecraft.getInstance();
		if (stepped || mc.level == null || player != mc.player) {
			return;
		}
		if (!player.isSpectator()) {
			pushesFrom(player, movement);
		}
		step(mc.level);
	}

	public static void endTick(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			world = RealisticPhysics.newWorld();
			view = null;
			viewLevel = null;
			incoming.clear();
			ClientGrid.clear();
			simulated.clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		if (!stepped) {
			step(mc.level);
		}
		syncGrid(mc.level, mc.player);
		claimAndRelease(mc.level, mc.player);
		for (RealisticBlockEntity e : simulated) {
			boolean touching = e.touchedTick == tick || e.hasPassenger(mc.player);
			send(e, (byte) ((touching ? BodyStatePayload.TOUCHING : 0) | (e.body.floating() ? BodyStatePayload.FLOATING : 0) | e.stateFlags()));
		}
	}


	private static void step(ClientLevel level) {
		stepped = true;
		if (level != viewLevel || view == null) {
			view = new RealisticPhysics.LevelView(level);
			viewLevel = level;
			world = RealisticPhysics.newWorld();
		}
		view.clear();
		world.bodies.clear();
		simulated.clear();
		Minecraft mc = Minecraft.getInstance();
		AABB area = mc.player != null ? mc.player.getBoundingBox().inflate(RANGE) : null;
		if (area != null) {
			for (RealisticBlockEntity e : level.getEntitiesOfClass(RealisticBlockEntity.class, area)) {
				if (e.body.cells.isEmpty() || e.body.mass <= 0.0) {
					continue;
				}
				if (e.isSimulated()) {
					e.body.kinematic = false;
					simulated.add(e);
				} else {
					asObstacle(e);
				}
				world.bodies.add(e.body);
			}
		}
		LocalPlayer me = mc.player;
		for (RealisticBlockEntity e : simulated) {
			Input driver = me != null && me.getVehicle() == e && e.getControllingPassenger() == me ? me.input.keyPresses : null;
			e.addLoads(world, driver);
		}
		for (BodyPushPayload p : incoming) {
			if (level.getEntity(p.entityId()) instanceof RealisticBlockEntity e && e.isSimulated()) {
				world.pushes.add(new PhysicsWorld.Push(e.body, p.px(), p.py(), p.pz(), p.dx(), p.dy(), p.dz(), p.target(), p.force(), p.kick()));
			}
		}
		incoming.clear();
		if (!simulated.isEmpty()) {
			BlockMaterials.gravity = WaterSettings.blockGravity;
			BlockMaterials.setGravityOverrides(WaterSettings.blockGravityPerBlock);
			world.gravity = BlockMaterials.gravity;
			world.gravityOf = BlockMaterials.hasGravityOverrides() ? BlockMaterials::gravityOf : null;
			double dt = 0.05 / RealisticPhysics.SUBSTEPS;
			for (int s = 0; s < RealisticPhysics.SUBSTEPS; s++) {
				world.step(view, dt, (body, cell) -> {
				});
			}
			for (RealisticBlockEntity e : simulated) {
				e.afterClientStep();
				for (Entity rider : e.getPassengers()) {
					e.positionRider(rider);
				}
			}
		}
		world.pushes.clear();
		world.turns.clear();
	}

	private static void asObstacle(RealisticBlockEntity e) {
		e.syncClientBody(e.position(), e.renderRotation(1.0F));
		Vector3fc v = e.syncedVelocity();
		Vector3fc w = e.syncedSpin();
		e.body.vel.set(v.x(), v.y(), v.z());
		e.body.ang.set(w.x(), w.y(), w.z());
		e.body.kinematic = true;
	}


	private static void claimAndRelease(ClientLevel level, LocalPlayer player) {
		int me = player.getId();
		double claim = RealisticBlockEntity.CLAIM_RANGE;
		simulated.clear();
		for (RealisticBlockEntity e : level.getEntitiesOfClass(RealisticBlockEntity.class, player.getBoundingBox().inflate(RANGE))) {
			int owner = e.owner();
			double d2 = e.distanceToSqr(player);
			if (e.isSimulated()) {
				if (owner == RealisticBlockEntity.LOCKED || owner >= 0 && owner != me) {
					e.stopSimulating();
					continue;
				}
				if (owner == me) {
					e.claimAge = 0;
				} else if (++e.claimAge > 20) {
					e.stopSimulating();
					e.claimCooldown = 40;
					continue;
				}
				if (d2 > (claim + 8.0) * (claim + 8.0)) {
					send(e, BodyStatePayload.RELEASE);
					e.stopSimulating();
					e.claimCooldown = 20;
					continue;
				}
				simulated.add(e);
			} else if (owner >= 0 && owner != me && player.getVehicle() == e && e.getControllingPassenger() == player) {
				send(e, BodyStatePayload.TOUCHING);
			} else if (e.claimCooldown > 0) {
				e.claimCooldown--;
			} else if (owner == me || owner == RealisticBlockEntity.FREE && d2 < claim * claim) {
				e.startSimulating();
				simulated.add(e);
			}
		}
	}

	private static void send(RealisticBlockEntity e, byte flags) {
		if (!ClientPlayNetworking.canSend(BodyStatePayload.TYPE)) {
			return;
		}
		RigidBody b = e.body;
		ClientPlayNetworking.send(new BodyStatePayload(e.getId(), b.pos.x, b.pos.y, b.pos.z, (float) b.rot.x, (float) b.rot.y, (float) b.rot.z, (float) b.rot.w,
			(float) b.vel.x, (float) b.vel.y, (float) b.vel.z, (float) b.ang.x, (float) b.ang.y, (float) b.ang.z, flags));
	}


	private static void syncGrid(ClientLevel level, LocalPlayer player) {
		for (RealisticBlockEntity e : level.getEntitiesOfClass(RealisticBlockEntity.class,
			player.getBoundingBox().inflate(RealisticBlockEntity.CLAIM_RANGE * 2.0))) {
			if (!e.clientGridDirty) {
				continue;
			}
			e.clientGridDirty = false;
			ClientGrid.forget(e.gridBlocks());
			ClientGrid.sync(e);
			e.reapplyClientBlockEntities();
		}
	}

	private static void pushesFrom(LocalPlayer player, Vec3 movement) {
		AABB box = player.getBoundingBox();
		AABB sweep = box.expandTowards(movement).inflate(1.0E-3);
		for (RealisticBlockEntity e : player.level().getEntitiesOfClass(RealisticBlockEntity.class, sweep.inflate(0.5))) {
			if (!e.isSimulated()) {
				asObstacle(e);
			}
			List<SweptCollider.Oriented> boxes = e.orientedBoxes(sweep);
			if (boxes.isEmpty()) {
				continue;
			}
			double[] bounds = {box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ};
			for (Direction.Axis axis : AXES) {
				double want = movement.get(axis);
				if (Math.abs(want) < 1.0E-7) {
					continue;
				}
				double reach = axis == Direction.Axis.Y ? want : want + Math.signum(want) * LOOKAHEAD;
				int index = axis == Direction.Axis.X ? 0 : axis == Direction.Axis.Y ? 1 : 2;
				double allowed = SweptCollider.allowedAlong(bounds, index, reach, boxes, RealisticCollision.MAX_SLOPE);
				if (Math.abs(reach - allowed) > 1.0E-7) {
					contact(player, e, box, boxes, axis, want, Math.min(Math.abs(allowed), Math.abs(want)) * Math.signum(want));
				}
			}
		}
	}

	private static void contact(LocalPlayer player, RealisticBlockEntity e, AABB box, List<SweptCollider.Oriented> shapes, Direction.Axis axis, double want,
		double allowed) {
		double sign = Math.signum(want);
		double nx = axis == Direction.Axis.X ? sign : 0.0;
		double ny = axis == Direction.Axis.Y ? sign : 0.0;
		double nz = axis == Direction.Axis.Z ? sign : 0.0;
		AABB moved = box.move(nx * Math.abs(want), ny * Math.abs(want), nz * Math.abs(want));
		Vector3d p = new Vector3d();
		double weight = 0.0;
		for (SweptCollider.Oriented shape : shapes) {
			double[] s = shape.bounds();
			AABB b = new AABB(s[0], s[1], s[2], s[3], s[4], s[5]);
			if (!b.intersects(moved)) {
				continue;
			}
			AABB o = b.intersect(moved);
			double w = Math.max(o.getXsize() * o.getYsize() * o.getZsize(), 1.0E-9);
			p.add(o.getCenter().x * w, o.getCenter().y * w, o.getCenter().z * w);
			weight += w;
		}
		if (weight <= 0.0) {
			return;
		}
		p.div(weight);
		double face = sign > 0 ? box.max(axis) + allowed : box.min(axis) + allowed;
		if (axis == Direction.Axis.X) {
			p.x = face;
		} else if (axis == Direction.Axis.Y) {
			p.y = face;
		} else {
			p.z = face;
		}
		boolean fresh = e.touchedTick < tick - 1;
		e.touchedTick = tick;
		double mass = RealisticPhysics.PLAYER_MASS;

		if (axis == Direction.Axis.Y && want < 0.0) {
			double fall = -want * 20.0;
			push(player, e, p, 0.0, -1.0, 0.0, Double.POSITIVE_INFINITY, mass * 9.81, fresh && fall > 4.0 ? mass * fall : 0.0);
			return;
		}
		double speed = Math.abs(want) * 20.0;
		if (axis != Direction.Axis.Y) {
			speed = Math.max(speed, walkSpeed(player) * walkAlong(player, nx, nz));
		}
		double into = speed - e.body.velocityAt(p.x, p.y, p.z, new Vector3d()).dot(nx, ny, nz);
		if (into <= 0.0) {
			return;
		}
		Input in = player.input.keyPresses;
		boolean sneak = in.shift();
		double base = sneak ? RealisticPhysics.SNEAK_FORCE : player.isSprinting() ? RealisticPhysics.SPRINT_FORCE : RealisticPhysics.WALK_FORCE;
		MobEffectInstance strengthEffect = player.getEffect(MobEffects.STRENGTH);
		int strength = strengthEffect != null ? strengthEffect.getAmplifier() + 1 : 0;
		double force = RealisticPhysics.pushForce(base, strength, speed);
		double target = speed;
		double kick = 0.0;
		double power = speed / RealisticPhysics.SPRINT_SPEED * (1.0 + 0.5 * strength);
		if (fresh && power > 1.0) {
			double bounce = KICK_BOUNCE * Math.clamp(1.0 - e.body.mass / 400.0, 0.0, 1.0) * Math.min(power - 1.0, 1.0);
			target = speed + bounce * into;
			kick = mass * into * (1.0 + bounce) * (1.0 + 0.5 * strength);
		}
		push(player, e, p, nx, ny, nz, target, force, kick);
	}

	private static double walkSpeed(LocalPlayer player) {
		double speed = player.getSpeed() * 43.17;
		if (player.input.keyPresses.shift()) {
			speed *= 0.3;
		}
		if (player.isInWater()) {
			speed *= 0.45;
		}
		return speed;
	}

	private static double walkAlong(LocalPlayer player, double nx, double nz) {
		Input in = player.input.keyPresses;
		double forward = (in.forward() ? 1 : 0) - (in.backward() ? 1 : 0);
		double strafe = (in.left() ? 1 : 0) - (in.right() ? 1 : 0);
		double len = Math.sqrt(forward * forward + strafe * strafe);
		if (len < 1.0E-6) {
			return 0.0;
		}
		double yaw = Math.toRadians(player.getYRot());
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double wx = (forward * fx + strafe * fz) / len;
		double wz = (forward * fz - strafe * fx) / len;
		return Math.max(wx * nx + wz * nz, 0.0);
	}

	private static void push(LocalPlayer player, RealisticBlockEntity e, Vector3d p, double dx, double dy, double dz, double target, double force, double kick) {
		int owner = e.owner();
		if (!e.isSimulated() && owner == RealisticBlockEntity.FREE && e.claimCooldown <= 0) {
			e.startSimulating();
		}
		if (e.isSimulated()) {
			world.pushes.add(new PhysicsWorld.Push(e.body, p.x, p.y, p.z, dx, dy, dz, target, force, kick));
			return;
		}
		if (ClientPlayNetworking.canSend(BodyPushPayload.TYPE)) {
			ClientPlayNetworking.send(new BodyPushPayload(e.getId(), (float) p.x, (float) p.y, (float) p.z, (float) dx, (float) dy, (float) dz, (float) target,
				(float) force, (float) kick));
		}
		if (owner >= 0 && owner != player.getId()) {
			send(e, BodyStatePayload.TOUCHING);
		}
	}
}
