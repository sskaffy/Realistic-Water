package me.skaffy.realistic;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import me.skaffy.net.BodyPushPayload;
import me.skaffy.physics.PhysicsView;
import me.skaffy.physics.PhysicsWorld;
import me.skaffy.physics.RigidBody;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

public final class RealisticPhysics {
	private static final Map<ServerLevel, RealisticPhysics> LEVELS = new WeakHashMap<>();
	public static final int SUBSTEPS = 6;
	public static final double WALK_FORCE = 15000.0;
	public static final double SPRINT_FORCE = 25000.0;
	public static final double SNEAK_FORCE = 5000.0;
	public static final double SPRINT_SPEED = 5.6;
	public static final double PLAYER_MASS = 80.0;
	private static final double MOB_FORCE = 8000.0;

	private final ServerLevel level;
	private final PhysicsWorld world = newWorld();
	private final Map<RigidBody, RealisticBlockEntity> owners = new IdentityHashMap<>();
	private final LevelView view;
	private final Set<RigidBody.Cell> burnedOut = new LinkedHashSet<>();
	private final Map<RigidBody.Cell, RealisticBlockEntity> burnedOwner = new IdentityHashMap<>();

	private RealisticPhysics(ServerLevel level) {
		this.level = level;
		this.view = new LevelView(level);
	}

	public static PhysicsWorld newWorld() {
		PhysicsWorld world = new PhysicsWorld();
		world.waterDensity *= BlockMaterials.WEIGHT;
		world.lavaDensity *= BlockMaterials.WEIGHT;
		return world;
	}

	public static double pushForce(double base, int strength, double speed) {
		double fast = Math.max(1.0, speed / SPRINT_SPEED);
		return base * (1.0 + 0.5 * strength) * fast * fast;
	}

	public static void track(RealisticBlockEntity e) {
		RealisticPhysics p = LEVELS.computeIfAbsent((ServerLevel) e.level(), RealisticPhysics::new);
		if (p.owners.put(e.body, e) == null) {
			p.world.bodies.add(e.body);
		}
	}

	public static void tick(ServerLevel level) {
		RealisticPhysics p = LEVELS.get(level);
		if (p != null) {
			p.step();
		}
	}

	public static void reportFlow(RealisticBlockEntity e, double vx, double vy, double vz, float surfaceY) {
		e.body.flow.set(vx, vy, vz);
		e.body.flowAge = 0.0;
		if (!Float.isNaN(surfaceY)) {
			e.body.surfaceY = surfaceY;
			e.body.surfaceAge = 0.0;
		}
	}

	public static void clear() {
		LEVELS.clear();
	}

	public static void forget(ServerLevel level) {
		LEVELS.remove(level);
	}

	public static void push(RealisticBlockEntity e, double px, double py, double pz, double dx, double dy, double dz, double target, double force, double kick) {
		if (!(e.level() instanceof ServerLevel server) || e.body.mass <= 0.0) {
			return;
		}
		if (e.level().getEntity(e.owner()) instanceof ServerPlayer owner) {
			ServerPlayNetworking.send(owner, new BodyPushPayload(e.getId(), (float) px, (float) py, (float) pz, (float) dx, (float) dy, (float) dz, (float) target,
				(float) force, (float) kick));
			return;
		}
		RealisticPhysics p = LEVELS.get(server);
		if (p != null && p.owners.containsKey(e.body)) {
			p.world.pushes.add(new PhysicsWorld.Push(e.body, px, py, pz, dx, dy, dz, target, force, kick));
		}
	}

	static void explosion(RealisticBlockEntity e, Vec3 knockback) {
		RigidBody b = e.body;
		double len = knockback.length();
		if (b.mass <= 0.0 || len < 1.0E-9) {
			return;
		}
		double impulse = 20.0 * Math.min(1.0, 1500.0 / b.mass) * b.mass * len;
		var random = e.level().getRandom();
		double px = b.pos.x + (random.nextDouble() - 0.5) * 0.8;
		double py = b.pos.y + (random.nextDouble() - 0.5) * 0.8;
		double pz = b.pos.z + (random.nextDouble() - 0.5) * 0.8;
		push(e, px, py, pz, knockback.x / len, knockback.y / len, knockback.z / len, Double.POSITIVE_INFINITY, 0.0, impulse);
	}

	private void step() {
		this.world.gravity = BlockMaterials.gravity;
		this.world.gravityOf = BlockMaterials.hasGravityOverrides() ? BlockMaterials::gravityOf : null;
		for (RealisticBlockEntity body : List.copyOf(this.owners.values())) {
			body.pullFromGrid();
		}
		this.owners.entrySet().removeIf(entry -> {
			if (entry.getValue().isRemoved() || entry.getKey().cells.isEmpty()) {
				this.world.bodies.remove(entry.getKey());
				return true;
			}
			return false;
		});
		if (this.owners.isEmpty()) {
			return;
		}
		for (Map.Entry<RigidBody, RealisticBlockEntity> entry : this.owners.entrySet()) {
			entry.getKey().kinematic = entry.getValue().isClientAuthoritative();
		}
		this.view.clear();
		this.mobs();
		for (Map.Entry<RigidBody, RealisticBlockEntity> entry : this.owners.entrySet()) {
			if (!entry.getKey().kinematic) {
				entry.getValue().addLoads(this.world, null);
			}
		}
		double dt = 0.05 / SUBSTEPS;
		for (int s = 0; s < SUBSTEPS; s++) {
			this.world.step(this.view, dt, (body, cell) -> {
				RealisticBlockEntity e = this.owners.get(body);
				if (e != null && this.burnedOut.add(cell)) {
					this.burnedOwner.put(cell, e);
				}
			});
		}
		this.world.pushes.clear();
		this.world.turns.clear();
		for (Map.Entry<RigidBody, RealisticBlockEntity> entry : this.owners.entrySet()) {
			RealisticBlockEntity e = entry.getValue();
			for (RigidBody.Cell c : entry.getKey().cells) {
				if (c.burning) {
					e.burningTicks = 10;
					break;
				}
			}
			if (!entry.getKey().kinematic) {
				e.afterPhysics();
			}
		}
		if (!this.burnedOut.isEmpty()) {
			for (RigidBody.Cell cell : new ArrayList<>(this.burnedOut)) {
				RealisticBlockEntity e = this.burnedOwner.get(cell);
				Vector3d c = e.body.toWorld(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, new Vector3d());
				this.level.sendParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y + 0.3, c.z, 8, 0.3, 0.3, 0.3, 0.02);
				this.level.sendParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 6, 0.3, 0.3, 0.3, 0.01);
				this.level.playSound(null, c.x, c.y, c.z, SoundEvents.GENERIC_BURN, SoundSource.BLOCKS, 0.6F, 0.8F + this.level.getRandom().nextFloat() * 0.3F);
				e.removeCell(cell.x, cell.y, cell.z);
			}
			this.burnedOut.clear();
			this.burnedOwner.clear();
		}
	}


	private void mobs() {
		for (Map.Entry<RigidBody, RealisticBlockEntity> entry : this.owners.entrySet()) {
			RigidBody body = entry.getKey();
			AABB area = new AABB(body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ).inflate(0.6);
			for (Mob mob : this.level.getEntitiesOfClass(Mob.class, area)) {
				if (mob.zza <= 0.0F) {
					continue;
				}
				AABB box = mob.getBoundingBox();
				double yaw = Math.toRadians(mob.getYRot());
				double dx = -Math.sin(yaw);
				double dz = Math.cos(yaw);
				double reach = (box.maxX - box.minX) * 0.5 + 0.1;
				double cx = (box.minX + box.maxX) * 0.5 + dx * reach;
				double cz = (box.minZ + box.maxZ) * 0.5 + dz * reach;
				for (double h : new double[]{0.25, 0.9, 1.5}) {
					double py = box.minY + Math.min(h, box.maxY - box.minY - 0.05);
					if (body.containsWorld(cx, py, cz)) {
						push(entry.getValue(), cx, py, cz, dx, 0.0, dz, Math.max(mob.getSpeed() * 20.0, 1.0), MOB_FORCE, 0.0);
						break;
					}
				}
			}
		}
	}


	public static final class LevelView implements PhysicsView {
		private final Level level;
		private final Long2ObjectOpenHashMap<double[][]> boxes = new Long2ObjectOpenHashMap<>();
		private final Long2IntOpenHashMap media = new Long2IntOpenHashMap();
		private final it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap grips = new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
		private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		private static final double[][] NONE = new double[0][];
		private static final double[] NO_FLOW = new double[0];
		private final Long2ObjectOpenHashMap<double[]> flows = new Long2ObjectOpenHashMap<>();

		public LevelView(Level level) {
			this.level = level;
			this.media.defaultReturnValue(-1);
		}

		public void clear() {
			this.boxes.clear();
			this.media.clear();
			this.flows.clear();
			this.grips.clear();
		}

		@Override
		public double[][] boxes(int x, int y, int z) {
			long key = BlockPos.asLong(x, y, z);
			double[][] cached = this.boxes.get(key);
			if (cached != null) {
				return cached;
			}
			this.pos.set(x, y, z);
			double[][] out;
			if (!this.level.hasChunkAt(this.pos)) {
				out = new double[][]{{x, y, z, x + 1, y + 1, z + 1}};
			} else {
				BlockState state = this.level.getBlockState(this.pos);
				VoxelShape shape = state.getCollisionShape(this.level, this.pos);
				if (shape.isEmpty()) {
					out = NONE;
				} else {
					List<AABB> list = shape.toAabbs();
					out = new double[list.size()][];
					for (int i = 0; i < list.size(); i++) {
						AABB b = list.get(i);
						out[i] = new double[]{x + b.minX, y + b.minY, z + b.minZ, x + b.maxX, y + b.maxY, z + b.maxZ};
					}
				}
			}
			this.boxes.put(key, out);
			return out;
		}

		@Override
		public int medium(int x, int y, int z) {
			long key = BlockPos.asLong(x, y, z);
			int cached = this.media.get(key);
			if (cached >= 0) {
				return cached;
			}
			this.pos.set(x, y, z);
			int m = 0;
			if (this.level.hasChunkAt(this.pos)) {
				BlockState state = this.level.getBlockState(this.pos);
				FluidState fluid = state.getFluidState();
				if (!fluid.isEmpty()) {
					boolean full = this.level.getFluidState(this.pos.above()).getType().isSame(fluid.getType());
					int eighths = full ? 8 : Mth.clamp(fluid.getAmount(), 1, 8);
					if (fluid.is(FluidTags.WATER)) {
						m |= eighths;
					} else if (fluid.is(FluidTags.LAVA)) {
						m |= eighths << PhysicsView.LAVA_SHIFT;
						m |= PhysicsView.FIRE;
					}
				}
				if (state.is(BlockTags.FIRE)) {
					m |= PhysicsView.FIRE;
				}
			}
			this.media.put(key, m);
			return m;
		}

		@Override
		public boolean flow(int x, int y, int z, double[] out) {
			long key = BlockPos.asLong(x, y, z);
			double[] cached = this.flows.get(key);
			if (cached == null) {
				cached = NO_FLOW;
				this.pos.set(x, y, z);
				if (this.level.hasChunkAt(this.pos)) {
					FluidState fluid = this.level.getFluidState(this.pos);
					if (!fluid.isEmpty()) {
						Vec3 f = fluid.getFlow(this.level, this.pos);
						if (f.lengthSqr() >= 1e-6) {
							cached = new double[]{f.x * 1.5, f.y * 1.5, f.z * 1.5};
						}
					}
				}
				this.flows.put(key, cached);
			}
			if (cached == NO_FLOW) {
				return false;
			}
			System.arraycopy(cached, 0, out, 0, 3);
			return true;
		}

		@Override
		public double friction(int x, int y, int z) {
			long key = BlockPos.asLong(x, y, z);
			if (this.grips.containsKey(key)) {
				return this.grips.get(key);
			}
			this.pos.set(x, y, z);
			double g = BlockMaterials.grip(this.level.getBlockState(this.pos).getBlock().getFriction());
			this.grips.put(key, g);
			return g;
		}
	}
}
