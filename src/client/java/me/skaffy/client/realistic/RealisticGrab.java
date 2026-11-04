package me.skaffy.client.realistic;

import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import java.lang.reflect.Method;
import java.util.List;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.physics.PhysicsWorld;
import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.RealisticBlockEntity;
import me.skaffy.realistic.RealisticBoatEntity;
import me.skaffy.realistic.RealisticCollision;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public final class RealisticGrab {
	private static final double OMEGA = 7.0;
	private static final double MAX_ACCEL = 60.0;
	private static final double TICK = 0.05;
	private static final double SPIN_CALMING = 0.12;
	private static final double UNLIMITED = 1.0e12;
	private static final Reference2BooleanOpenHashMap<Class<?>> INTERACTIVE = new Reference2BooleanOpenHashMap<>();

	private static @Nullable RealisticBlockEntity held;
	private static final Vector3d local = new Vector3d();
	private static double distance;

	private RealisticGrab() {
	}

	public static boolean holding() {
		return held != null;
	}

	public static @Nullable RealisticBlockEntity held() {
		return held;
	}

	public static boolean start(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (held != null) {
			return true;
		}
		if (!WaterSettings.grabBodies || player == null || player.isSpectator() || !(mc.hitResult instanceof RealisticHit hit)
			|| !player.getMainHandItem().isEmpty()) {
			return false;
		}
		RealisticBlockEntity e = hit.body();
		boolean usesClick = e instanceof RealisticBoatEntity || interactive(e, hit);
		if (usesClick && !player.isShiftKeyDown()) {
			return false;
		}
		if (carries(player, e)) {
			return false;
		}
		int owner = e.owner();
		if (owner == RealisticBlockEntity.LOCKED || owner >= 0 && owner != player.getId()) {
			return false;
		}
		held = e;
		RealisticCollision.carried = e;
		local.set(hit.local);
		distance = player.getEyePosition().distanceTo(hit.getLocation());
		if (!e.isSimulated()) {
			e.startSimulating();
		}
		player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
		return true;
	}

	private static boolean carries(LocalPlayer player, RealisticBlockEntity e) {
		return !WaterSettings.grabCarrying && (player.getVehicle() == e || RealisticPlatform.standingOn() == e);
	}

	static void addLoads(PhysicsWorld world, List<RealisticBlockEntity> simulated) {
		RealisticBlockEntity e = held;
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (e == null) {
			return;
		}
		if (player == null || e.isRemoved() || !mc.options.keyUse.isDown() || !WaterSettings.grabBodies || carries(player, e)
			|| !simulated.contains(e)) {
			held = null;
			RealisticCollision.carried = null;
			return;
		}
		RigidBody b = e.body;
		Vector3d p = b.toWorld(local.x, local.y, local.z, new Vector3d());
		Vec3 move = player.getDeltaMovement();
		Vec3 eye = player.getEyePosition().add(move);
		Vec3 look = player.getViewVector(1.0F);
		Vector3d target = new Vector3d(eye.x + look.x * distance, eye.y + look.y * distance, eye.z + look.z * distance);
		Vector3d now = b.velocityAt(p.x, p.y, p.z, new Vector3d());
		Vector3d accel = new Vector3d(target).sub(p).mul(OMEGA * OMEGA)
			.sub(new Vector3d(now).sub(move.x * 20.0, move.y * 20.0, move.z * 20.0).mul(2.0 * OMEGA));
		if (accel.length() > MAX_ACCEL) {
			accel.normalize(MAX_ACCEL);
		}
		Vector3d want = new Vector3d(now).fma(TICK, accel);
		MobEffectInstance strengthEffect = player.getEffect(MobEffects.STRENGTH);
		int strength = strengthEffect != null ? strengthEffect.getAmplifier() + 1 : 0;
		double force = WaterSettings.opGrabbing ? UNLIMITED : Math.max(WaterSettings.grabStrength, 0.0F) * (1.0 + 0.5 * strength);
		double[] w = {want.x, want.y, want.z};
		for (int a = 0; a < 3; a++) {
			for (double sign = -1.0; sign <= 1.0; sign += 2.0) {
				world.pushes.add(new PhysicsWorld.Push(b, p.x, p.y, p.z, a == 0 ? sign : 0.0, a == 1 ? sign : 0.0, a == 2 ? sign : 0.0,
					w[a] * sign, force, 0.0));
			}
		}
		double spin = b.ang.length();
		if (spin > 1.0e-4) {
			world.turns.add(new PhysicsWorld.Turn(b, b.ang.x / spin, b.ang.y / spin, b.ang.z / spin, spin * (1.0 - SPIN_CALMING),
				WaterSettings.opGrabbing ? UNLIMITED : force * 0.3));
		}
	}

	private static boolean interactive(RealisticBlockEntity e, RealisticHit hit) {
		RigidBody.Cell cell = e.body.cellAt(hit.x, hit.y, hit.z);
		if (cell == null || !(cell.payload instanceof BlockState state)) {
			return false;
		}
		Block block = state.getBlock();
		return INTERACTIVE.computeIfAbsent(block.getClass(), c -> overrides((Class<?>) c, "useWithoutItem") || overrides((Class<?>) c, "getMenuProvider"));
	}

	private static boolean overrides(Class<?> type, String name) {
		for (Class<?> c = type; c != null && c != BlockBehaviour.class && c != Block.class; c = c.getSuperclass()) {
			for (Method m : c.getDeclaredMethods()) {
				if (m.getName().equals(name)) {
					return true;
				}
			}
		}
		return false;
	}
}
