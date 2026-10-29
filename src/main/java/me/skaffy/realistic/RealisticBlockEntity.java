package me.skaffy.realistic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import me.skaffy.net.BodyStatePayload;
import me.skaffy.net.GridBlockEntityPayload;
import me.skaffy.net.GridBlockEventPayload;
import me.skaffy.net.GridLevelEventPayload;
import me.skaffy.net.GridSoundPayload;
import me.skaffy.physics.PhysicsWorld;
import me.skaffy.physics.RigidBody;
import me.skaffy.physics.SweptCollider;
import me.skaffy.portal.PortalTransit;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

public class RealisticBlockEntity extends Entity {
	private static final EntityDataAccessor<String> DATA_CELLS = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Quaternionfc> DATA_ROT = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.QUATERNION);
	private static final EntityDataAccessor<Boolean> DATA_FLOATING = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Integer> DATA_OWNER = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Vector3fc> DATA_VEL = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.VECTOR3);
	private static final EntityDataAccessor<Vector3fc> DATA_SPIN = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.VECTOR3);
	private static final EntityDataAccessor<BlockPos> DATA_ORIGIN = SynchedEntityData.defineId(RealisticBlockEntity.class, EntityDataSerializers.BLOCK_POS);
	public static final int FREE = -1;
	public static final int LOCKED = -2;
	private static final int LOCK_TICKS = 10;
	private static final int OWNER_TIMEOUT = 20;
	public static final double CLAIM_RANGE = 32.0;

	public final RigidBody body = new RigidBody();
	private boolean bodyNeedsPosition;
	int burningTicks;
	private @Nullable GridMirror mirror;
	private boolean portalArrived;
	private final Quaternionf rotO = new Quaternionf();
	private final Quaternionf rotNow = new Quaternionf();
	private final Quaternionf rotTarget = new Quaternionf();
	private int lastStateTick;
	private int ownerTouchTick;
	private int lockTicks;
	private boolean simulated;
	public int claimAge;
	public int claimCooldown;
	public long touchedTick = Long.MIN_VALUE;
	private final Vector3d renderFrom = new Vector3d();
	private final Vector3d renderTo = new Vector3d();

	public RealisticBlockEntity(EntityType<? extends RealisticBlockEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	public static RealisticBlockEntity spawn(ServerLevel level, List<RigidBody.Cell> cells, Vector3d center, @Nullable RigidBody like) {
		RealisticBlockEntity e = new RealisticBlockEntity(ModContent.REALISTIC_BLOCK_ENTITY, level);
		e.body.setCells(cells);
		if (like != null) {
			e.body.rot.set(like.rot);
		}
		e.body.rebuild();
		e.body.pos.set(center);
		e.body.updateDerived();
		if (like != null) {
			like.velocityAt(center.x, center.y, center.z, e.body.vel);
			e.body.ang.set(like.ang);
		}
		e.syncShape();
		e.syncRotation();
		e.setPos(center.x, center.y, center.z);
		level.addFreshEntity(e);
		return e;
	}


	@Override
	protected void defineSynchedData(final SynchedEntityData.Builder builder) {
		builder.define(DATA_CELLS, "");
		builder.define(DATA_ROT, new Quaternionf());
		builder.define(DATA_FLOATING, false);
		builder.define(DATA_OWNER, FREE);
		builder.define(DATA_VEL, new Vector3f());
		builder.define(DATA_SPIN, new Vector3f());
		builder.define(DATA_ORIGIN, BlockPos.ZERO);
	}

	public BlockPos gridOrigin() {
		return this.entityData.get(DATA_ORIGIN);
	}

	void setGridOrigin(BlockPos origin) {
		if (!origin.equals(this.entityData.get(DATA_ORIGIN))) {
			this.entityData.set(DATA_ORIGIN, origin);
		}
	}

	public RigidBody.@Nullable Cell cellAtGrid(BlockPos gridPos) {
		BlockPos origin = this.gridOrigin();
		if (origin.equals(BlockPos.ZERO)) {
			return null;
		}
		return this.body.cellAt(gridPos.getX() - origin.getX(), gridPos.getY() - origin.getY(), gridPos.getZ() - origin.getZ());
	}

	public Vec3 gridToWorld(BlockPos gridPos, float partialTicks) {
		BlockPos origin = this.gridOrigin();
		Vector3d out = this.body.toWorld(gridPos.getX() - origin.getX() + 0.5, gridPos.getY() - origin.getY() + 0.5,
			gridPos.getZ() - origin.getZ() + 0.5, new Vector3d());
		return new Vec3(out.x, out.y, out.z);
	}

	public int owner() {
		return this.entityData.get(DATA_OWNER);
	}

	private void setOwner(int owner) {
		this.entityData.set(DATA_OWNER, owner);
	}

	@Override
	public boolean isClientAuthoritative() {
		return this.owner() >= 0;
	}

	@Override
	protected boolean isLocalClientAuthoritative() {
		return this.simulated;
	}

	public boolean isSimulated() {
		return this.simulated;
	}

	public Vector3fc syncedVelocity() {
		return this.entityData.get(DATA_VEL);
	}

	public Vector3fc syncedSpin() {
		return this.entityData.get(DATA_SPIN);
	}

	void syncShape() {
		StringBuilder sb = new StringBuilder();
		for (RigidBody.Cell c : this.body.cells) {
			if (!sb.isEmpty()) {
				sb.append(';');
			}
			sb.append(c.x).append(',').append(c.y).append(',').append(c.z).append(',').append(Block.getId((BlockState) c.payload));
		}
		this.entityData.set(DATA_CELLS, sb.toString());
	}

	void syncRotation() {
		this.entityData.set(DATA_ROT, new Quaternionf((float) this.body.rot.x, (float) this.body.rot.y, (float) this.body.rot.z, (float) this.body.rot.w));
	}

	@Override
	public void onSyncedDataUpdated(final EntityDataAccessor<?> accessor) {
		super.onSyncedDataUpdated(accessor);
		if (!this.level().isClientSide()) {
			return;
		}
		if (DATA_ORIGIN.equals(accessor)) {
			this.clientGridDirty = true;
		} else if (DATA_CELLS.equals(accessor)) {
			this.clientGridDirty = true;
			this.cellsChanged();
		} else if (DATA_ROT.equals(accessor)) {
			Quaternionfc q = this.entityData.get(DATA_ROT);
			boolean first = this.tickCount == 0;
			this.rotTarget.set(q);
			if (first && !this.simulated) {
				this.rotNow.set(q);
				this.rotO.set(q);
			}
		}
	}

	protected void cellsChanged() {
		List<RigidBody.Cell> cells = new ArrayList<>();
		for (String part : this.entityData.get(DATA_CELLS).split(";")) {
			String[] f = part.split(",");
			if (f.length == 4) {
				BlockState state = Block.stateById(Integer.parseInt(f[3]));
				cells.add(this.makeCell(Integer.parseInt(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]), state));
			}
		}
		if (this.sameCells(cells)) {
			boolean reshaped = false;
			for (RigidBody.Cell c : cells) {
				RigidBody.Cell old = this.body.cellAt(c.x, c.y, c.z);
				if (old.payload != c.payload) {
					reshaped |= !java.util.Arrays.deepEquals(old.boxes, c.boxes) || old.density != c.density;
					this.body.replaceCell(c);
				}
			}
			if (reshaped) {
				this.body.rebuild();
				if (this.simulated) {
					this.setPos(this.body.pos.x, this.body.pos.y, this.body.pos.z);
				}
			}
			return;
		}
		boolean had = !this.body.cells.isEmpty() && this.body.mass > 0.0;
		Vector3d oldCom = new Vector3d(this.body.com);
		this.body.setCells(cells);
		this.clientBlockEntities.keySet().removeIf(key -> !this.body.hasCell(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key)));
		this.clientBlockEntityTags.keySet().removeIf(key -> !this.body.hasCell(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key)));
		this.body.rebuild();
		if (!had) {
			this.stopSimulating();
			this.setPos(this.position());
			return;
		}
		Vector3d shift = this.body.rot.transform(new Vector3d(this.body.com).sub(oldCom));
		this.xo += shift.x;
		this.yo += shift.y;
		this.zo += shift.z;
		this.setPos(this.getX() + shift.x, this.getY() + shift.y, this.getZ() + shift.z);
		if (this.simulated) {
			this.body.pos.add(shift);
			this.renderFrom.add(shift);
			this.renderTo.add(shift);
			this.body.updateDerived();
		}
	}

	protected RigidBody.Cell makeCell(int x, int y, int z, BlockState state) {
		return BlockMaterials.cell(x, y, z, state);
	}

	protected double[][] playerBoxes(RigidBody.Cell cell) {
		return cell.payload instanceof BlockState state ? BlockMaterials.collisionBoxes(state) : CUBE;
	}

	public void addLoads(PhysicsWorld world, @Nullable Input driver) {
	}

	public byte stateFlags() {
		return 0;
	}

	protected void receivedFlags(byte flags) {
	}

	private boolean sameCells(List<RigidBody.Cell> cells) {
		if (cells.size() != this.body.cells.size() || cells.isEmpty()) {
			return false;
		}
		for (RigidBody.Cell c : cells) {
			if (!this.body.hasCell(c.x, c.y, c.z)) {
				return false;
			}
		}
		return true;
	}

	@Override
	protected InterpolationHandler createInterpolationHandler() {
		return LinearInterpolationHandler.create(this, 1);
	}

	public void startSimulating() {
		this.syncClientBody(this.position(), this.rotNow);
		Vector3fc v = this.syncedVelocity();
		Vector3fc w = this.syncedSpin();
		this.body.vel.set(v.x(), v.y(), v.z());
		this.body.ang.set(w.x(), w.y(), w.z());
		this.renderFrom.set(this.body.pos);
		this.renderTo.set(this.body.pos);
		this.rotO.set(this.rotNow);
		this.claimAge = 0;
		this.simulated = true;
	}

	public void stopSimulating() {
		if (!this.simulated) {
			return;
		}
		this.simulated = false;
		this.rotTarget.set(this.rotNow);
	}

	public void afterClientStep() {
		this.renderFrom.set(this.renderTo);
		this.renderTo.set(this.body.pos);
		this.rotO.set(this.rotNow);
		this.rotNow.set((float) this.body.rot.x, (float) this.body.rot.y, (float) this.body.rot.z, (float) this.body.rot.w);
		this.setPos(this.body.pos.x, this.body.pos.y, this.body.pos.z);
	}

	public Vec3 renderPosition(float partialTicks) {
		if (!this.simulated) {
			return this.getPosition(partialTicks);
		}
		return new Vec3(this.renderFrom.x + (this.renderTo.x - this.renderFrom.x) * partialTicks, this.renderFrom.y + (this.renderTo.y - this.renderFrom.y) * partialTicks,
			this.renderFrom.z + (this.renderTo.z - this.renderFrom.z) * partialTicks);
	}

	public Vector3d clientVelocity() {
		if (this.simulated) {
			return new Vector3d(this.body.vel);
		}
		return new Vector3d((this.getX() - this.xo) * 20.0, (this.getY() - this.yo) * 20.0, (this.getZ() - this.zo) * 20.0);
	}

	public Vector3d clientSpin() {
		Quaternionf dq = new Quaternionf(this.rotNow).mul(new Quaternionf(this.rotO).conjugate());
		if (dq.w < 0.0F) {
			dq.set(-dq.x, -dq.y, -dq.z, -dq.w);
		}
		double s = Math.sqrt(dq.x * dq.x + dq.y * dq.y + dq.z * dq.z);
		if (s < 1e-6) {
			return new Vector3d();
		}
		double angle = 2.0 * Math.atan2(s, dq.w) * 20.0;
		return new Vector3d(dq.x / s * angle, dq.y / s * angle, dq.z / s * angle);
	}

	public Quaternionf renderRotation(float partialTicks) {
		return new Quaternionf(this.rotO).slerp(this.rotNow, partialTicks);
	}

	public void syncClientBody(Vec3 position, Quaternionfc rotation) {
		if (this.simulated) {
			return;
		}
		this.body.pos.set(position.x, position.y, position.z);
		this.body.rot.set(rotation.x(), rotation.y(), rotation.z(), rotation.w());
		if (!this.body.cells.isEmpty()) {
			this.body.updateDerived();
		}
	}


	@Override
	public void tick() {
		if (this.level().isClientSide()) {
			if (!this.simulated) {
				this.rotO.set(this.rotNow);
				this.rotNow.set(this.rotTarget);
				this.setPos(this.position());
			}
			return;
		}
		if (this.body.cells.isEmpty()) {
			this.discard();
			return;
		}
		if (this.bodyNeedsPosition) {
			this.bodyNeedsPosition = false;
			this.body.pos.set(this.getX(), this.getY(), this.getZ());
			this.body.updateDerived();
		}
		if (this.burningTicks > 0) {
			this.burningTicks--;
		}
		this.checkOwner();
		GridMirror grid = this.grid();
		if (grid != null) {
			grid.push();
		}
		if (this.isRemoved() || this.body.cells.isEmpty()) {
			return;
		}
		if (grid != null) {
			grid.randomTick(((ServerLevel) this.level()).getGameRules().get(GameRules.RANDOM_TICK_SPEED));
		}
		this.setSharedFlagOnFire(this.burningTicks > 0);
		if (this.getY() < this.level().getMinY() - 64) {
			this.discard();
			return;
		}
		if (this.passPortal((ServerLevel) this.level())) {
			return;
		}
		RealisticPhysics.track(this);
	}

	private boolean passPortal(ServerLevel level) {
		BlockPos touched = PortalTransit.touchedPortal(level, this);
		if (touched == null) {
			this.portalArrived = false;
			return false;
		}
		if (this.portalArrived || this.isPassenger() || !(level.getBlockState(touched).getBlock() instanceof Portal portal)) {
			return false;
		}
		TeleportTransition exit = portal.getPortalDestination(level, this, touched);
		if (exit == null || !level.isAllowedToEnterPortal(exit.newLevel())
			|| exit.newLevel().dimension() != level.dimension() && !this.canTeleport(level, exit.newLevel())) {
			return false;
		}
		Vec3 at = exit.position().add(0.0, this.getY() - this.body.minY, 0.0);
		this.lock();
		Entity moved = this.teleport(new TeleportTransition(exit.newLevel(), at, exit.deltaMovement(), exit.yRot(), exit.xRot(), exit.relatives(),
			exit.postTeleportTransition()));
		if (moved instanceof RealisticBlockEntity arrived) {
			arrived.portalArrived = true;
		}
		return moved != null && moved != this;
	}

	@Override
	public void onRemoval(final RemovalReason reason) {
		super.onRemoval(reason);
		if (reason == RemovalReason.CHANGED_DIMENSION && this.mirror != null) {
			this.mirror.releaseMoved();
			this.mirror = null;
		}
	}

	void afterPhysics() {
		this.afterPhysics(this.body.floating());
	}

	private void afterPhysics(boolean floating) {
		Vector3d p = this.body.pos;
		if (p.x != this.getX() || p.y != this.getY() || p.z != this.getZ()) {
			this.setPos(p.x, p.y, p.z);
		}
		this.syncRotation();
		this.entityData.set(DATA_FLOATING, floating);
		this.entityData.set(DATA_VEL, new Vector3f((float) this.body.vel.x, (float) this.body.vel.y, (float) this.body.vel.z));
		this.entityData.set(DATA_SPIN, new Vector3f((float) this.body.ang.x, (float) this.body.ang.y, (float) this.body.ang.z));
	}


	private int serverTick() {
		return ((ServerLevel) this.level()).getServer().getTickCount();
	}

	private void checkOwner() {
		int owner = this.owner();
		if (owner == LOCKED) {
			if (--this.lockTicks <= 0) {
				this.setOwner(FREE);
			}
		} else if (owner >= 0 && (this.serverTick() - this.lastStateTick > OWNER_TIMEOUT || !(this.level().getEntity(owner) instanceof ServerPlayer p)
			|| p.distanceToSqr(this) > CLAIM_RANGE * CLAIM_RANGE * 4.0)) {
			this.setOwner(FREE);
		}
	}

	void setCellStates(Long2ObjectMap<BlockState> changes) {
		boolean reshaped = false;
		for (Long2ObjectMap.Entry<BlockState> entry : changes.long2ObjectEntrySet()) {
			long k = entry.getLongKey();
			RigidBody.Cell old = this.body.cellAt(BlockPos.getX(k), BlockPos.getY(k), BlockPos.getZ(k));
			if (old == null) {
				continue;
			}
			RigidBody.Cell next = this.makeCell(old.x, old.y, old.z, entry.getValue());
			reshaped |= !java.util.Arrays.deepEquals(old.boxes, next.boxes) || old.density != next.density;
			this.body.replaceCell(next);
		}
		if (reshaped) {
			this.body.rebuild();
			this.setPos(this.body.pos.x, this.body.pos.y, this.body.pos.z);
		}
		this.syncShape();
	}

	protected boolean usesGrid() {
		return true;
	}

	public @Nullable GridMirror grid() {
		if (!this.usesGrid() || !(this.level() instanceof ServerLevel server)) {
			return null;
		}
		if (this.mirror == null) {
			this.mirror = new GridMirror(this, server);
		}
		return this.mirror;
	}

	void markGridStale() {
		if (this.mirror != null) {
			this.mirror.invalidate();
		}
	}

	public void gridChanged(BlockPos gridPos) {
		if (this.mirror != null) {
			this.mirror.noteChanged(gridPos);
		}
	}

	public void gridBlockEntityChanged(BlockPos gridPos) {
		if (this.mirror != null) {
			this.mirror.noteBlockEntityChanged(gridPos);
		}
	}

	public void sendGridBlockEvent(BlockPos gridPos, int paramA, int paramB) {
		GridSpace.Slot slot = this.mirror == null ? null : this.mirror.slot();
		if (slot == null) {
			return;
		}
		GridBlockEventPayload payload = new GridBlockEventPayload(this.getId(), slot.gridX(gridPos.getX()), slot.gridY(gridPos.getY()),
			slot.gridZ(gridPos.getZ()), paramA, paramB);
		for (ServerPlayer player : PlayerLookup.tracking(this)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	public void sendGridSound(float x, float y, float z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch) {
		GridSoundPayload payload = new GridSoundPayload(this.getId(), x, y, z, sound, source.ordinal(), volume, pitch);
		for (ServerPlayer player : PlayerLookup.tracking(this)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	public void sendGridLevelEvent(float x, float y, float z, int event, int data) {
		GridLevelEventPayload payload = new GridLevelEventPayload(this.getId(), x, y, z, event, data);
		for (ServerPlayer player : PlayerLookup.tracking(this)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	public Vec3 cellToWorld(double x, double y, double z) {
		Vector3d out = this.body.toWorld(x, y, z, new Vector3d());
		return new Vec3(out.x, out.y, out.z);
	}

	public void applyClientBlockEvent(int x, int y, int z, int paramA, int paramB) {
		BlockPos origin = this.gridOrigin();
		if (origin.equals(BlockPos.ZERO)) {
			return;
		}
		BlockPos at = new BlockPos(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
		BlockState state = this.level().getBlockState(at);
		if (!state.isAir()) {
			state.triggerEvent(this.level(), at, paramA, paramB);
		}
	}

	@Override
	public void startSeenByPlayer(final ServerPlayer player) {
		super.startSeenByPlayer(player);
		if (this.mirror == null) {
			return;
		}
		for (GridBlockEntityPayload payload : this.mirror.fullBlockEntityState()) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	public final Long2ObjectMap<BlockEntity> clientBlockEntities = new Long2ObjectOpenHashMap<>();
	private java.util.List<BlockPos> gridBlocks = java.util.List.of();
	public boolean clientGridDirty = true;

	public void rememberGridBlocks(java.util.List<BlockPos> positions) {
		this.gridBlocks = positions;
	}

	public java.util.List<BlockPos> gridBlocks() {
		return this.gridBlocks;
	}
	private final Long2ObjectMap<CompoundTag> clientBlockEntityTags = new Long2ObjectOpenHashMap<>();

	public void applyClientBlockEntity(int x, int y, int z, @Nullable CompoundTag tag) {
		long key = BlockPos.asLong(x, y, z);
		if (tag == null) {
			this.clientBlockEntityTags.remove(key);
			return;
		}
		this.clientBlockEntityTags.put(key, tag);
		this.applyTag(x, y, z, tag);
	}

	private void applyTag(int x, int y, int z, CompoundTag tag) {
		BlockEntity be = this.clientGridBlockEntity(x, y, z);
		if (be == null) {
			return;
		}
		BlockEntity loaded = BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(), tag, this.registryAccess());
		if (loaded != null) {
			this.clientBlockEntities.put(BlockPos.asLong(x, y, z), loaded);
			loaded.setLevel(this.level());
		}
	}

	public void reapplyClientBlockEntities() {
		for (Long2ObjectMap.Entry<CompoundTag> entry : this.clientBlockEntityTags.long2ObjectEntrySet()) {
			long key = entry.getLongKey();
			this.applyTag(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key), entry.getValue());
		}
	}

	public @Nullable BlockEntity clientGridBlockEntity(int x, int y, int z) {
		BlockEntity own = this.clientBlockEntities.get(BlockPos.asLong(x, y, z));
		if (own != null) {
			return own;
		}
		BlockPos origin = this.gridOrigin();
		if (origin.equals(BlockPos.ZERO)) {
			return null;
		}
		return this.level().getBlockEntity(origin.offset(x, y, z));
	}

	void pullFromGrid() {
		if (this.mirror != null) {
			this.mirror.pull();
		}
	}

	public void finishPlacement(Player player, ItemStack stack, BlockState state) {
		GridMirror grid = this.grid();
		if (grid == null) {
			return;
		}
		grid.push();
		GridSpace.Slot slot = grid.slot();
		if (slot == null) {
			return;
		}
		BlockPos at = slot.world(0, 0, 0);
		ServerLevel server = (ServerLevel) this.level();
		GridInteraction.asGridPlayer(player, this, slot, () -> {
			BlockItem.updateCustomBlockEntityTag(server, player, at, stack);
			state.getBlock().setPlacedBy(server, at, state, player, stack);
			return null;
		});
		grid.pull();
	}

	void adoptCell(int x, int y, int z, BlockState state) {
		if (state.isAir() || this.body.hasCell(x, y, z)) {
			return;
		}
		this.body.addCell(this.makeCell(x, y, z, state));
		this.body.rebuild();
		this.setPos(this.body.pos.x, this.body.pos.y, this.body.pos.z);
		this.syncShape();
		this.afterPhysics();
	}

	@Override
	public void remove(final RemovalReason reason) {
		super.remove(reason);
		if (this.mirror != null) {
			this.mirror.release();
			this.mirror = null;
		}
	}

	private void lock() {
		this.setOwner(LOCKED);
		this.lockTicks = LOCK_TICKS;
	}

	public void acceptState(ServerPlayer player, BodyStatePayload s) {
		if (this.isRemoved() || this.body.cells.isEmpty() || this.owner() == LOCKED || player.distanceToSqr(this) > CLAIM_RANGE * CLAIM_RANGE * 4.0) {
			return;
		}
		int now = this.serverTick();
		int owner = this.owner();
		if (owner != player.getId()) {
			boolean free = owner == FREE || now - this.lastStateTick > OWNER_TIMEOUT;
			boolean idleOwner = s.has(BodyStatePayload.TOUCHING) && now - this.ownerTouchTick > 10 || this.getControllingPassenger() == player;
			if (s.has(BodyStatePayload.RELEASE) || !free && !idleOwner || player.distanceToSqr(this) > CLAIM_RANGE * CLAIM_RANGE) {
				return;
			}
		}
		double[] all = {s.x(), s.y(), s.z(), s.qx(), s.qy(), s.qz(), s.qw(), s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz()};
		for (double d : all) {
			if (!Double.isFinite(d)) {
				return;
			}
		}
		if (this.body.pos.distanceSquared(s.x(), s.y(), s.z()) > 64.0) {
			return;
		}
		this.body.pos.set(s.x(), s.y(), s.z());
		this.body.rot.set(s.qx(), s.qy(), s.qz(), s.qw());
		this.body.vel.set(s.vx(), s.vy(), s.vz());
		this.body.ang.set(s.wx(), s.wy(), s.wz());
		double speed = this.body.vel.length();
		if (speed > 60.0) {
			this.body.vel.mul(60.0 / speed);
		}
		this.body.updateDerived();
		this.afterPhysics(s.has(BodyStatePayload.FLOATING));
		this.receivedFlags(s.flags());
		if (s.has(BodyStatePayload.RELEASE)) {
			this.setOwner(FREE);
			return;
		}
		this.setOwner(player.getId());
		this.lastStateTick = now;
		if (s.has(BodyStatePayload.TOUCHING)) {
			this.ownerTouchTick = now;
		}
	}

	public boolean isFloating() {
		return this.entityData.get(DATA_FLOATING);
	}

	@Override
	protected AABB makeBoundingBox(final Vec3 position) {
		if (this.body == null || this.body.cells.isEmpty()) {
			return super.makeBoundingBox(position);
		}
		if (this.level() != null && this.level().isClientSide()) {
			this.syncClientBody(position, this.rotNow);
		} else {
			this.body.pos.set(position.x, position.y, position.z);
			this.body.updateDerived();
		}
		return new AABB(this.body.minX, this.body.minY, this.body.minZ, this.body.maxX, this.body.maxY, this.body.maxZ);
	}

	public List<SweptCollider.Oriented> orientedBoxes(AABB area) {
		List<SweptCollider.Oriented> out = new ArrayList<>();
		RigidBody b = this.body;
		if (b.cells.isEmpty()) {
			return out;
		}
		Vector3d ax = b.dirToWorld(1.0, 0.0, 0.0, new Vector3d());
		Vector3d ay = b.dirToWorld(0.0, 1.0, 0.0, new Vector3d());
		Vector3d az = b.dirToWorld(0.0, 0.0, 1.0, new Vector3d());
		double[] axes = {ax.x, ax.y, ax.z, ay.x, ay.y, ay.z, az.x, az.y, az.z};
		Vector3d centre = new Vector3d();
		for (RigidBody.Cell cell : b.cells) {
			for (double[] box : this.playerBoxes(cell)) {
				double hx = (box[3] - box[0]) * 0.5;
				double hy = (box[4] - box[1]) * 0.5;
				double hz = (box[5] - box[2]) * 0.5;
				if (hx <= 0.0 || hy <= 0.0 || hz <= 0.0) {
					continue;
				}
				b.toWorld(cell.x + box[0] + hx, cell.y + box[1] + hy, cell.z + box[2] + hz, centre);
				SweptCollider.Oriented oriented = new SweptCollider.Oriented(centre.x, centre.y, centre.z, axes, hx, hy, hz);
				if (oriented.intersects(area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ)) {
					out.add(oriented);
				}
			}
		}
		return out;
	}

	public List<VoxelShape> collisionShapes(AABB area) {
		List<VoxelShape> out = new ArrayList<>();
		RigidBody b = this.body;
		if (b.cells.isEmpty()) {
			return out;
		}
		Vector3d c = new Vector3d();
		Vector3d ax = new Vector3d();
		Vector3d ay = new Vector3d();
		Vector3d az = new Vector3d();
		for (RigidBody.Cell cell : b.cells) {
			double[][] boxes = this.playerBoxes(cell);
			for (double[] box : boxes) {
				double sx = box[3] - box[0];
				double sy = box[4] - box[1];
				double sz = box[5] - box[2];
				b.dirToWorld(sx * 0.25, 0, 0, ax);
				b.dirToWorld(0, sy * 0.25, 0, ay);
				b.dirToWorld(0, 0, sz * 0.25, az);
				double ex = Math.abs(ax.x) + Math.abs(ay.x) + Math.abs(az.x);
				double ey = Math.abs(ax.y) + Math.abs(ay.y) + Math.abs(az.y);
				double ez = Math.abs(ax.z) + Math.abs(ay.z) + Math.abs(az.z);
				for (int s = 0; s < 8; s++) {
					b.toWorld(cell.x + box[0] + sx * (0.25 + 0.5 * (s & 1)), cell.y + box[1] + sy * (0.25 + 0.5 * ((s >> 1) & 1)),
						cell.z + box[2] + sz * (0.25 + 0.5 * (s >> 2)), c);
					AABB sub = new AABB(c.x - ex, c.y - ey, c.z - ez, c.x + ex, c.y + ey, c.z + ez);
					if (sub.intersects(area)) {
						out.add(Shapes.create(sub));
					}
				}
			}
		}
		return out;
	}

	@Override
	public boolean hurtServer(final ServerLevel level, final DamageSource source, final float damage) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return !this.isRemoved();
	}

	@Override
	public boolean canBeCollidedWith(final @Nullable Entity other) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isAttackable() {
		return false;
	}

	@Override
	public boolean skipAttackInteraction(final Entity source) {
		return true;
	}

	@Override
	public void push(final Vec3 impulse) {
	}

	@Override
	public void push(final double xa, final double ya, final double za) {
	}

	@Override
	public void push(final Entity entity) {
	}

	@Override
	public void pushFromExplosion(final Vec3 impulse) {
		if (!this.level().isClientSide()) {
			RealisticPhysics.explosion(this, impulse);
		}
	}

	@Override
	public void move(final MoverType moverType, final Vec3 delta) {
		if (this.level().isClientSide() || moverType == MoverType.SELF || moverType == MoverType.PLAYER) {
			return;
		}
		this.lock();
		this.body.pos.add(delta.x, delta.y, delta.z);
		this.body.vel.set(delta.x * 5.0, Math.max(delta.y * 5.0, this.body.vel.y), delta.z * 5.0);
		this.body.updateDerived();
		this.afterPhysics();
	}

	@Override
	public InteractionResult interact(final Player player, final InteractionHand hand, final Vec3 location) {
		Vec3 clicked = this.position().add(location);
		if (this.level().isClientSide()) {
			return this.pick(player, clicked) == null ? InteractionResult.PASS : InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	public void useFromClient(ServerPlayer player, InteractionHand hand, int x, int y, int z, int face, double localX, double localY, double localZ) {
		if (this.isRemoved() || !player.mayBuild() || this.body.cellAt(x, y, z) == null || face < 0 || face > 5) {
			RealisticWater.LOGGER.warn("[use] refused: removed={} mayBuild={} cell={} face={}",
				this.isRemoved(), player.mayBuild(), this.body.cellAt(x, y, z) != null, face);
			return;
		}
		Vector3d centre = this.body.toWorld(x + 0.5, y + 0.5, z + 0.5, new Vector3d());
		double reach = player.blockInteractionRange() + 1.5;
		if (player.getEyePosition().distanceToSqr(centre.x, centre.y, centre.z) > reach * reach) {
			return;
		}
		GridMirror grid = this.grid();
		if (grid == null) {
			return;
		}
		grid.push();
		GridSpace.Slot slot = grid.slot();
		if (slot == null) {
			return;
		}
		GridPick pick = new GridPick(x, y, z, Direction.from3DDataValue(face), new Vector3d(localX, localY, localZ));
		ItemStack stack = player.getItemInHand(hand);
		Block block = RealisticBlockItem.blockOf(stack);
		ServerLevel server = (ServerLevel) this.level();
		boolean blockFirst = !player.isSecondaryUseActive() || stack.isEmpty();
		InteractionResult result = InteractionResult.PASS;
		if (blockFirst) {
			result = GridInteraction.use(server, player, hand, this, slot, pick);
		}
		if (!result.consumesAction() && block != null) {
			result = GridInteraction.place(server, player, hand, block, this, slot, pick);
			if (result.consumesAction() && !player.hasInfiniteMaterials()) {
				stack.shrink(1);
			}
		}
		grid.pull();
	}

	public RigidBody.@Nullable Cell cellAt(Vec3 world) {
		Vector3d g = this.body.toGrid(world.x, world.y, world.z, new Vector3d());
		int bx = (int) Math.floor(g.x);
		int by = (int) Math.floor(g.y);
		int bz = (int) Math.floor(g.z);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					RigidBody.Cell cell = this.body.cellAt(bx + dx, by + dy, bz + dz);
					if (cell == null) {
						continue;
					}
					for (double[] box : cell.boxes == null ? CUBE : cell.boxes) {
						if (g.x > cell.x + box[0] - 0.03 && g.x < cell.x + box[3] + 0.03 && g.y > cell.y + box[1] - 0.03 && g.y < cell.y + box[4] + 0.03
							&& g.z > cell.z + box[2] - 0.03 && g.z < cell.z + box[5] + 0.03) {
							return cell;
						}
					}
				}
			}
		}
		return null;
	}

	private static final double[][] CUBE = {{0, 0, 0, 1, 1, 1}};
	public static final Direction[] RAY_FACES = {Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

	public record GridPick(int x, int y, int z, Direction face, Vector3d local) {
	}

	public @Nullable GridPick pick(Player player, Vec3 reported) {
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getViewVector(1.0F);
		RigidBody.Hit hit = this.body.rayCast(eye.x, eye.y, eye.z, look.x, look.y, look.z, player.blockInteractionRange() + 2.0);
		if (hit != null) {
			Vector3d local = this.body.toGrid(eye.x + look.x * hit.distance(), eye.y + look.y * hit.distance(), eye.z + look.z * hit.distance(),
				new Vector3d());
			return new GridPick(hit.x(), hit.y(), hit.z(), RAY_FACES[hit.face()], local);
		}
		return this.pick(reported);
	}

	public @Nullable GridPick pick(Vec3 world) {
		Vector3d g = this.body.toGrid(world.x, world.y, world.z, new Vector3d());
		GridPick outward = null;
		double outwardDist = 0.08;
		GridPick any = null;
		double anyDist = 0.08;
		int bx = (int) Math.floor(g.x);
		int by = (int) Math.floor(g.y);
		int bz = (int) Math.floor(g.z);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					RigidBody.Cell cell = this.body.cellAt(bx + dx, by + dy, bz + dz);
					if (cell == null) {
						continue;
					}
					for (double[] box : cell.boxes == null ? CUBE : cell.boxes) {
						double[] d = {g.x - cell.x - box[0], cell.x + box[3] - g.x, g.y - cell.y - box[1], cell.y + box[4] - g.y, g.z - cell.z - box[2],
							cell.z + box[5] - g.z};
						if (d[0] < -0.08 || d[1] < -0.08 || d[2] < -0.08 || d[3] < -0.08 || d[4] < -0.08 || d[5] < -0.08) {
							continue;
						}
						for (int f = 0; f < 6; f++) {
							double dist = Math.abs(d[f]);
							Direction face = RAY_FACES[f];
							GridPick made = new GridPick(cell.x, cell.y, cell.z, face, new Vector3d(g));
							if (dist < anyDist) {
								anyDist = dist;
								any = made;
							}
							if (dist < outwardDist && !this.body.hasCell(cell.x + face.getStepX(), cell.y + face.getStepY(), cell.z + face.getStepZ())) {
								outwardDist = dist;
								outward = made;
							}
						}
					}
				}
			}
		}
		return outward != null ? outward : any;
	}

	public void removeCell(int x, int y, int z) {
		if (this.body.removeCell(x, y, z) == null) {
			return;
		}
		this.lock();
		if (this.body.cells.isEmpty()) {
			this.discard();
			return;
		}
		List<List<RigidBody.Cell>> parts = this.body.components();
		if (parts.size() > 1 && this.level() instanceof ServerLevel server) {
			parts.sort(Comparator.comparingInt(List::size));
			for (List<RigidBody.Cell> part : parts.subList(0, parts.size() - 1)) {
				Long2ObjectOpenHashMap<CompoundTag> carried = this.mirror == null ? new Long2ObjectOpenHashMap<>() : this.mirror.takeBlockEntityTags(part);
				for (RigidBody.Cell c : part) {
					this.body.removeCell(c.x, c.y, c.z);
				}
				RigidBody probe = new RigidBody();
				probe.setCells(part);
				probe.rebuild();
				Vector3d center = this.body.toWorld(probe.com.x, probe.com.y, probe.com.z, new Vector3d());
				RealisticBlockEntity piece = spawn(server, part, center, this.body);
				GridMirror pieceGrid = piece.grid();
				if (pieceGrid != null && !carried.isEmpty()) {
					pieceGrid.loadBlockEntities(carried);
				}
			}
		}
		this.body.rebuild();
		this.syncShape();
		this.afterPhysics();
	}


	@Override
	protected void addAdditionalSaveData(final ValueOutput output) {
		ValueOutput.ValueOutputList list = output.childrenList("cells");
		for (RigidBody.Cell c : this.body.cells) {
			ValueOutput o = list.addChild();
			o.putInt("x", c.x);
			o.putInt("y", c.y);
			o.putInt("z", c.z);
			o.store("state", BlockState.CODEC, (BlockState) c.payload);
			o.putDouble("burn", c.burn);
		}
		if (this.mirror != null) {
			this.mirror.saveBlockEntities(output.childrenList("blockEntities"));
		}
		output.putDouble("qx", this.body.rot.x);
		output.putDouble("qy", this.body.rot.y);
		output.putDouble("qz", this.body.rot.z);
		output.putDouble("qw", this.body.rot.w);
		output.putDouble("vx", this.body.vel.x);
		output.putDouble("vy", this.body.vel.y);
		output.putDouble("vz", this.body.vel.z);
		output.putDouble("wx", this.body.ang.x);
		output.putDouble("wy", this.body.ang.y);
		output.putDouble("wz", this.body.ang.z);
	}

	@Override
	protected void readAdditionalSaveData(final ValueInput input) {
		List<RigidBody.Cell> cells = new ArrayList<>();
		input.childrenListOrEmpty("cells").stream().forEach(o -> o.read("state", BlockState.CODEC).ifPresent(state -> {
			RigidBody.Cell c = this.makeCell(o.getIntOr("x", 0), o.getIntOr("y", 0), o.getIntOr("z", 0), state);
			c.burn = o.getDoubleOr("burn", 0.0);
			cells.add(c);
		}));
		this.body.setCells(cells);
		Long2ObjectOpenHashMap<CompoundTag> blockEntities = new Long2ObjectOpenHashMap<>();
		input.childrenListOrEmpty("blockEntities").stream().forEach(o -> o.read("data", CompoundTag.CODEC).ifPresent(
			tag -> blockEntities.put(BlockPos.asLong(o.getIntOr("x", 0), o.getIntOr("y", 0), o.getIntOr("z", 0)), tag)));
		if (!blockEntities.isEmpty()) {
			GridMirror grid = this.grid();
			if (grid != null) {
				grid.loadBlockEntities(blockEntities);
			}
		}
		this.body.rot.set(input.getDoubleOr("qx", 0.0), input.getDoubleOr("qy", 0.0), input.getDoubleOr("qz", 0.0), input.getDoubleOr("qw", 1.0));
		this.body.vel.set(input.getDoubleOr("vx", 0.0), input.getDoubleOr("vy", 0.0), input.getDoubleOr("vz", 0.0));
		this.body.ang.set(input.getDoubleOr("wx", 0.0), input.getDoubleOr("wy", 0.0), input.getDoubleOr("wz", 0.0));
		this.body.rebuild();
		this.bodyNeedsPosition = true;
		this.setPos(this.position());
		this.syncShape();
		this.syncRotation();
	}
}
