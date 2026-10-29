package me.skaffy.realistic;

import java.util.ArrayList;
import java.util.List;
import me.skaffy.ModContent;
import me.skaffy.mixin.BoatItemAccessor;
import me.skaffy.net.BodyStatePayload;
import me.skaffy.physics.PhysicsWorld;
import me.skaffy.physics.RigidBody;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Containers;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public class RealisticBoatEntity extends RealisticBlockEntity {
	private static final EntityDataAccessor<String> DATA_BOAT = SynchedEntityData.defineId(RealisticBoatEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Byte> DATA_PADDLES = SynchedEntityData.defineId(RealisticBoatEntity.class, EntityDataSerializers.BYTE);
	private static final double PADDLE_SPEED = 8.0;
	private static final double BACK_SPEED = 1.5;
	private static final double PADDLE_FORCE = 3000.0;
	private static final double TURN_SPEED = 2.5;
	private static final double TURN_TORQUE = 1500.0;
	private static final double KEEL_FORCE = 2500.0;
	private static final double CAPSIZED = 0.3;

	private float damage;
	private final SimpleContainer chest = new SimpleContainer(27) {
		@Override
		public boolean stillValid(final Player player) {
			return !RealisticBoatEntity.this.isRemoved() && player.distanceToSqr(RealisticBoatEntity.this) < 64.0;
		}
	};
	private final float[] paddlePositions = new float[2];
	private byte drivenPaddles;
	private float riderHeading = Float.NaN;

	public RealisticBoatEntity(EntityType<? extends RealisticBoatEntity> type, Level level) {
		super(type, level);
	}

	public static RealisticBoatEntity spawn(ServerLevel level, BoatItem boat, Vec3 at, float yaw) {
		RealisticBoatEntity e = new RealisticBoatEntity(ModContent.REALISTIC_BOAT, level);
		e.entityData.set(DATA_BOAT, BuiltInRegistries.ITEM.getKey(boat).toString());
		BlockState planks = e.planks();
		List<RigidBody.Cell> cells = new ArrayList<>();
		for (int[] c : BoatHull.CELLS) {
			cells.add(e.makeCell(c[0], c[1], c[2], planks));
		}
		e.body.setCells(cells);
		e.body.rot.identity().rotateY(Math.toRadians(-yaw));
		e.body.rebuild();
		Vector3d com = e.body.rot.transform(new Vector3d(e.body.com));
		e.body.pos.set(at.x + com.x, at.y + com.y + 0.01, at.z + com.z);
		e.body.updateDerived();
		e.syncShape();
		e.syncRotation();
		e.setPos(e.body.pos.x, e.body.pos.y, e.body.pos.z);
		level.addFreshEntity(e);
		return e;
	}

	@Override
	protected void defineSynchedData(final SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_BOAT, "minecraft:oak_boat");
		builder.define(DATA_PADDLES, (byte) 0);
	}


	private Identifier boatId() {
		Identifier id = Identifier.tryParse(this.entityData.get(DATA_BOAT));
		return id != null ? id : Identifier.withDefaultNamespace("oak_boat");
	}

	public @Nullable BoatItem boatItem() {
		return BuiltInRegistries.ITEM.getOptional(this.boatId()).orElse(null) instanceof BoatItem boat ? boat : null;
	}

	public @Nullable EntityType<? extends AbstractBoat> vanillaType() {
		BoatItem boat = this.boatItem();
		return boat != null ? ((BoatItemAccessor) boat).realisticWater$entityType() : null;
	}

	public boolean isRaft() {
		return this.boatId().getPath().contains("raft");
	}

	public boolean hasChest() {
		return this.boatId().getPath().contains("chest");
	}

	private BlockState planks() {
		String wood = this.boatId().getPath().replace("_chest_boat", "").replace("_chest_raft", "").replace("_boat", "").replace("_raft", "");
		return BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath(this.boatId().getNamespace(), wood + "_planks"))
			.orElse(Blocks.OAK_PLANKS).defaultBlockState();
	}

	@Override
	protected boolean usesGrid() {
		return false;
	}

	@Override
	protected RigidBody.Cell makeCell(int x, int y, int z, BlockState state) {
		boolean raft = this.isRaft();
		double density = BoatHull.PLANK_DENSITY * BlockMaterials.WEIGHT * (raft ? BoatHull.RAFT_DENSITY : 1.0);
		return new RigidBody.Cell(x, y, z, density, BlockMaterials.flammable(state), BlockMaterials.grip(state.getBlock().getFriction()), state,
			BoatHull.solid(raft, x, y, z), BoatHull.envelope(raft, x, y, z));
	}

	@Override
	protected double[][] playerBoxes(RigidBody.Cell cell) {
		return cell.boxes != null ? cell.boxes : super.playerBoxes(cell);
	}

	@Override
	public void onSyncedDataUpdated(final EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (DATA_BOAT.equals(accessor) && this.level().isClientSide() && !this.body.cells.isEmpty()) {
			List<RigidBody.Cell> cells = new ArrayList<>();
			for (RigidBody.Cell c : this.body.cells) {
				cells.add(this.makeCell(c.x, c.y, c.z, (BlockState) c.payload));
			}
			this.body.setCells(cells);
			this.body.rebuild();
			if (!this.isSimulated()) {
				this.setPos(this.position());
			}
		}
	}


	private int seats() {
		return this.hasChest() ? 1 : 2;
	}

	private boolean upright() {
		return this.body.dirToWorld(0, 1, 0, new Vector3d()).y > CAPSIZED;
	}

	@Override
	protected boolean canAddPassenger(final Entity passenger) {
		return this.getPassengers().size() < this.seats() && this.upright();
	}

	@Override
	public @Nullable LivingEntity getControllingPassenger() {
		return this.getFirstPassenger() instanceof Player player ? player : null;
	}

	@Override
	protected Vec3 getPassengerAttachmentPoint(final Entity passenger, final EntityDimensions dimensions, final float scale) {
		int riders = Math.max(this.getPassengers().size(), 1);
		int index = Math.max(this.getPassengers().indexOf(passenger), 0);
		double[] seat = BoatHull.seat(this.isRaft(), this.hasChest(), index, riders);
		Vector3d at = this.body.toWorld(seat[0], seat[1], seat[2], new Vector3d());
		return new Vec3(at.x - this.getX(), at.y - this.getY(), at.z - this.getZ());
	}

	private float heading() {
		Vector3d f = this.body.dirToWorld(0, 0, 1, new Vector3d());
		return (float) Math.toDegrees(Math.atan2(-f.x, f.z));
	}

	@Override
	protected void positionRider(final Entity passenger, final Entity.MoveFunction moveFunction) {
		super.positionRider(passenger, moveFunction);
		if (this.level().isClientSide() && passenger.isLocalInstanceAuthoritative()) {
			float heading = this.heading();
			if (!Float.isNaN(this.riderHeading)) {
				float turn = Mth.wrapDegrees(heading - this.riderHeading);
				passenger.setYRot(passenger.getYRot() + turn);
				passenger.setYHeadRot(passenger.getYHeadRot() + turn);
			}
			this.riderHeading = heading;
		}
	}

	@Override
	protected void removePassenger(final Entity passenger) {
		super.removePassenger(passenger);
		this.riderHeading = Float.NaN;
	}

	@Override
	public void addLoads(PhysicsWorld world, @Nullable Input driver) {
		RigidBody b = this.body;
		if (b.mass <= 0.0) {
			return;
		}
		List<Entity> riders = this.getPassengers();
		for (int i = 0; i < riders.size(); i++) {
			double[] seat = BoatHull.seat(this.isRaft(), this.hasChest(), i, riders.size());
			Vector3d p = b.toWorld(seat[0], seat[1], seat[2], new Vector3d());
			double mass = riders.get(i) instanceof Player ? RealisticPhysics.PLAYER_MASS : 40.0;
			world.pushes.add(new PhysicsWorld.Push(b, p.x, p.y, p.z, 0.0, -1.0, 0.0, Double.POSITIVE_INFINITY, mass * 9.81, 0.0));
		}
		this.drivenPaddles = 0;
		Vector3d f = b.dirToWorld(0, 0, 1, new Vector3d());
		f.y = 0.0;
		if (!b.inFluid() || f.lengthSquared() < 0.1) {
			return;
		}
		f.normalize();
		Vector3d c = b.pos;
		world.pushes.add(new PhysicsWorld.Push(b, c.x, c.y, c.z, f.z, 0.0, -f.x, 0.0, KEEL_FORCE, 0.0));
		world.pushes.add(new PhysicsWorld.Push(b, c.x, c.y, c.z, -f.z, 0.0, f.x, 0.0, KEEL_FORCE, 0.0));
		if (driver == null || !this.upright()) {
			return;
		}
		if (driver.forward()) {
			world.pushes.add(new PhysicsWorld.Push(b, c.x, c.y, c.z, f.x, 0.0, f.z, PADDLE_SPEED, PADDLE_FORCE, 0.0));
		} else if (driver.backward()) {
			world.pushes.add(new PhysicsWorld.Push(b, c.x, c.y, c.z, -f.x, 0.0, -f.z, BACK_SPEED, PADDLE_FORCE * 0.5, 0.0));
		}
		double along = b.vel.x * f.x + b.vel.z * f.z;
		boolean reversing = along < -0.2 || driver.backward() && !driver.forward() && along < 0.5;
		double turn = driver.left() == driver.right() ? 0.0 : driver.left() != reversing ? TURN_SPEED : -TURN_SPEED;
		world.turns.add(new PhysicsWorld.Turn(b, 0.0, 1.0, 0.0, turn, turn == 0.0 ? TURN_TORQUE * 0.5 : TURN_TORQUE));
		boolean left = driver.right() && !driver.left() || driver.forward();
		boolean right = driver.left() && !driver.right() || driver.forward();
		this.drivenPaddles = (byte) ((left ? BodyStatePayload.PADDLE_LEFT : 0) | (right ? BodyStatePayload.PADDLE_RIGHT : 0));
	}

	@Override
	public byte stateFlags() {
		return this.drivenPaddles;
	}

	@Override
	protected void receivedFlags(byte flags) {
		this.entityData.set(DATA_PADDLES, (byte) (flags & (BodyStatePayload.PADDLE_LEFT | BodyStatePayload.PADDLE_RIGHT)));
	}

	private boolean paddling(int side) {
		byte paddles = this.isSimulated() && this.getControllingPassenger() != null && this.getControllingPassenger().isLocalInstanceAuthoritative()
			? this.drivenPaddles : this.entityData.get(DATA_PADDLES);
		return (paddles & (side == 0 ? BodyStatePayload.PADDLE_LEFT : BodyStatePayload.PADDLE_RIGHT)) != 0;
	}

	public float rowingTime(int side, float partialTicks) {
		return this.paddling(side) ? Mth.clampedLerp(partialTicks, this.paddlePositions[side] - (float) (Math.PI / 8), this.paddlePositions[side]) : 0.0F;
	}

	@Override
	public void tick() {
		super.tick();
		if (this.level().isClientSide()) {
			for (int side = 0; side < 2; side++) {
				this.paddlePositions[side] = this.paddling(side) ? this.paddlePositions[side] + (float) (Math.PI / 8) : 0.0F;
			}
			return;
		}
		if (this.isRemoved()) {
			return;
		}
		if (this.damage > 0.0F) {
			this.damage = Math.max(this.damage - 1.0F, 0.0F);
		}
		if (this.isVehicle() && !this.upright()) {
			this.ejectPassengers();
		}
	}


	@Override
	public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
		if (player.isSecondaryUseActive()) {
			if (!this.hasChest()) {
				return InteractionResult.PASS;
			}
			if (!this.level().isClientSide()) {
				player.openMenu(new SimpleMenuProvider((id, inventory, p) -> ChestMenu.threeRows(id, inventory, this.chest), this.getDisplayName()));
			}
			return InteractionResult.SUCCESS;
		}
		if (player.isPassenger() || !this.canAddPassenger(player)) {
			return InteractionResult.PASS;
		}
		if (!this.level().isClientSide()) {
			return player.startRiding(this) ? InteractionResult.SUCCESS_SERVER : InteractionResult.PASS;
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public boolean isAttackable() {
		return true;
	}

	@Override
	public boolean skipAttackInteraction(final Entity source) {
		return false;
	}

	@Override
	public boolean hurtServer(final ServerLevel level, final DamageSource source, final float amount) {
		if (this.isRemoved() || this.isInvulnerableToBase(source)) {
			return false;
		}
		boolean creative = source.getEntity() instanceof Player p && p.hasInfiniteMaterials();
		this.damage += amount * 10.0F;
		if (creative || this.damage > 40.0F) {
			this.destroy(level, !creative);
		}
		return true;
	}

	@Override
	public void removeCell(int x, int y, int z) {
		if (this.level() instanceof ServerLevel level) {
			this.destroy(level, false);
		}
	}

	private void destroy(ServerLevel level, boolean drop) {
		this.ejectPassengers();
		if (this.hasChest()) {
			Containers.dropContents(level, this, this.chest);
		}
		BoatItem boat = this.boatItem();
		if (drop && boat != null) {
			this.spawnAtLocation(level, RealisticBoatItem.of(boat, 1));
		}
		this.discard();
	}

	@Override
	public ItemStack getPickResult() {
		BoatItem boat = this.boatItem();
		return boat != null ? RealisticBoatItem.of(boat, 1) : new ItemStack(Items.OAK_BOAT);
	}


	@Override
	protected void addAdditionalSaveData(final ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("boat", this.boatId().toString());
		if (this.hasChest()) {
			ContainerHelper.saveAllItems(output, this.chest.getItems());
		}
	}

	@Override
	protected void readAdditionalSaveData(final ValueInput input) {
		this.entityData.set(DATA_BOAT, input.getStringOr("boat", "minecraft:oak_boat"));
		super.readAdditionalSaveData(input);
		if (this.hasChest()) {
			NonNullList<ItemStack> items = this.chest.getItems();
			ContainerHelper.loadAllItems(input, items);
		}
	}
}
