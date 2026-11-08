package me.skaffy.client.realistic;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.Map;
import me.skaffy.realistic.RealisticBoatEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.AbstractBoatRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.BoatRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

public class RealisticBoatRenderer extends EntityRenderer<RealisticBoatEntity, RealisticBoatRenderer.State> {
	public static class State extends BoatRenderState {
		final Quaternionf rotation = new Quaternionf();
		final Vector3d origin = new Vector3d();
		@Nullable EntityType<?> type;
	}

	private static final Map<EntityType<?>, Entity> STAND_INS = new HashMap<>();

	public RealisticBoatRenderer(final EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(final RealisticBoatEntity entity, final State state, final float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		Vec3 at = entity.renderPosition(partialTicks);
		state.x = at.x;
		state.y = at.y;
		state.z = at.z;
		state.rotation.set(entity.renderRotation(partialTicks));
		Vector3f o = state.rotation.transform(new Vector3f((float) -entity.body.com.x, (float) -entity.body.com.y, (float) -entity.body.com.z));
		state.origin.set(o.x, o.y, o.z);
		state.yRot = 0.0F;
		state.hurtTime = 0.0F;
		state.hurtDir = 1;
		state.damageTime = 0.0F;
		state.bubbleAngle = 0.0F;
		state.isUnderWater = !BoatWaterMask.needed(entity.level(), entity.getBoundingBox());
		state.rowingTimeLeft = entity.rowingTime(0, partialTicks);
		state.rowingTimeRight = entity.rowingTime(1, partialTicks);
		state.type = entity.vanillaType();
	}

	@Override
	public void submit(final State state, final PoseStack poseStack, final SubmitNodeCollector collector, final CameraRenderState camera) {
		AbstractBoatRenderer vanilla = vanillaRenderer(state.type);
		if (vanilla == null) {
			return;
		}
		poseStack.pushPose();
		poseStack.translate(state.origin.x, state.origin.y, state.origin.z);
		poseStack.rotate(state.rotation);
		vanilla.submit(state, poseStack, collector, camera);
		poseStack.popPose();
	}

	private static @Nullable AbstractBoatRenderer vanillaRenderer(@Nullable EntityType<?> type) {
		Minecraft mc = Minecraft.getInstance();
		if (type == null || mc.level == null) {
			return null;
		}
		Entity standIn = STAND_INS.computeIfAbsent(type, t -> t.create(mc.level, EntitySpawnReason.LOAD));
		if (standIn == null) {
			return null;
		}
		if (standIn.level() != mc.level) {
			standIn = type.create(mc.level, EntitySpawnReason.LOAD);
			STAND_INS.put(type, standIn);
		}
		Object renderer = mc.getEntityRenderDispatcher().getRenderer(standIn);
		return renderer instanceof AbstractBoatRenderer boat ? boat : null;
	}
}
