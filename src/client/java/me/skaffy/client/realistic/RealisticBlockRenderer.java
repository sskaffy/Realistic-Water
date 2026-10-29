package me.skaffy.client.realistic;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;

public class RealisticBlockRenderer extends EntityRenderer<RealisticBlockEntity, RealisticBlockRenderer.State> {
	public static class State extends EntityRenderState {
		final Quaternionf rotation = new Quaternionf();
		final List<MovingBlockRenderState> blocks = new ArrayList<>();
		final List<float[]> offsets = new ArrayList<>();
		final List<BlockEntityRenderState> blockEntities = new ArrayList<>();
		final List<float[]> blockEntityOffsets = new ArrayList<>();
		int crackIndex = -1;
		int crackStage;
		int outlineIndex = -1;
		VoxelShape outlineShape = Shapes.block();
		float lineWidth = 1.0F;
	}

	static final class EvenlyLit extends MovingBlockRenderState {
		int sky;
		int block;

		@Override
		public int getBrightness(final LightLayer layer, final BlockPos pos) {
			return layer == LightLayer.SKY ? this.sky : this.block;
		}
	}

	private static void lightUp(EvenlyLit cell, ClientLevel level, BlockPos at) {
		int sky = 0;
		int block = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int d = -1; d < 6; d++) {
			if (d < 0) {
				p.set(at);
			} else {
				p.setWithOffset(at, Direction.from3DDataValue(d));
			}
			if (d >= 0 && level.getBlockState(p).canOcclude()) {
				continue;
			}
			sky = Math.max(sky, level.getBrightness(LightLayer.SKY, p));
			block = Math.max(block, level.getBrightness(LightLayer.BLOCK, p));
		}
		cell.sky = sky;
		cell.block = block;
	}

	public RealisticBlockRenderer(final EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(final RealisticBlockEntity entity, final State state, final float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		Vec3 at = entity.renderPosition(partialTicks);
		state.x = at.x;
		state.y = at.y;
		state.z = at.z;
		state.rotation.set(entity.renderRotation(partialTicks));
		state.blocks.clear();
		state.offsets.clear();
		state.blockEntities.clear();
		state.blockEntityOffsets.clear();
		state.crackIndex = -1;
		state.outlineIndex = -1;
		RigidBody body = entity.body;
		entity.syncClientBody(at, state.rotation);
		ClientLevel level = entity.level() instanceof ClientLevel client ? client : null;
		Minecraft mc = Minecraft.getInstance();
		RealisticHit hovered = mc.hitResult instanceof RealisticHit hit && hit.getEntity() == entity ? hit : null;
		state.lineWidth = mc.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;
		CardinalLighting shading = level != null ? turned(level.cardinalLighting(), body) : CardinalLighting.DEFAULT;
		Vector3d center = new Vector3d();
		for (RigidBody.Cell cell : body.cells) {
			EvenlyLit block = new EvenlyLit();
			block.blockState = (BlockState) cell.payload;
			body.toWorld(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, center);
			block.blockPos = BlockPos.containing(center.x, center.y, center.z);
			block.randomSeedPos = new BlockPos(cell.x, cell.y, cell.z);
			if (level != null) {
				block.biome = level.getBiome(block.blockPos);
				block.cardinalLighting = shading;
				block.lightEngine = level.getLightEngine();
				lightUp(block, level, block.blockPos);
			}
			int stage = RealisticMining.stage(entity.getId(), cell.x, cell.y, cell.z);
			if (stage >= 0) {
				state.crackIndex = state.blocks.size();
				state.crackStage = stage;
			}
			if (hovered != null && hovered.x == cell.x && hovered.y == cell.y && hovered.z == cell.z) {
				state.outlineIndex = state.blocks.size();
				state.outlineShape = block.blockState.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
				if (state.outlineShape.isEmpty()) {
					state.outlineShape = Shapes.block();
				}
			}
			float[] offset = {(float) (cell.x - body.com.x), (float) (cell.y - body.com.y), (float) (cell.z - body.com.z)};
			state.blocks.add(block);
			state.offsets.add(offset);
			this.extractBlockEntity(entity, cell, block, offset, state, partialTicks);
		}
	}

	private void extractBlockEntity(RealisticBlockEntity entity, RigidBody.Cell cell, MovingBlockRenderState lit, float[] offset, State state,
		float partialTicks) {
		BlockEntity be = entity.clientGridBlockEntity(cell.x, cell.y, cell.z);
		if (be == null) {
			return;
		}
		BlockEntityRenderState beState = Minecraft.getInstance().getBlockEntityRenderDispatcher().tryExtractRenderState(be, partialTicks, null, false);
		if (beState == null) {
			return;
		}
		beState.lightCoords = LightCoordsUtil.pack(lit.getBrightness(LightLayer.BLOCK, lit.blockPos), lit.getBrightness(LightLayer.SKY, lit.blockPos));
		state.blockEntities.add(beState);
		state.blockEntityOffsets.add(offset);
	}

	private static CardinalLighting turned(CardinalLighting base, RigidBody body) {
		Vector3d n = new Vector3d();
		float[] out = new float[6];
		double[][] faces = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
		for (int f = 0; f < 6; f++) {
			body.dirToWorld(faces[f][0], faces[f][1], faces[f][2], n);
			out[f] = (float) (n.x * n.x * (n.x > 0 ? base.east() : base.west()) + n.y * n.y * (n.y > 0 ? base.up() : base.down())
				+ n.z * n.z * (n.z > 0 ? base.south() : base.north()));
		}
		return new CardinalLighting(out[0], out[1], out[2], out[3], out[4], out[5]);
	}

	@Override
	public void submit(final State state, final PoseStack poseStack, final SubmitNodeCollector collector, final CameraRenderState camera) {
		poseStack.pushPose();
		poseStack.rotate(state.rotation);
		for (int i = 0; i < state.blocks.size(); i++) {
			MovingBlockRenderState block = state.blocks.get(i);
			if (block.blockState.getRenderShape() != RenderShape.MODEL) {
				continue;
			}
			float[] o = state.offsets.get(i);
			poseStack.pushPose();
			poseStack.translate(o[0], o[1], o[2]);
			collector.submitMovingBlock(poseStack, block, state.outlineColor);
			if (i == state.crackIndex) {
				BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(block.blockState);
				List<BlockStateModelPart> parts = new ArrayList<>();
				model.collectParts(RandomSource.create(42L), parts);
				collector.submitBreakingBlockModel(poseStack, List.copyOf(parts), state.crackStage, model.hasMaterialFlag(1));
			}
			if (i == state.outlineIndex) {
				var lines = Minecraft.getInstance().gameRenderer.useImprovedTransparency()
					? RenderTypes.linesTranslucentNoDepthWrite() : RenderTypes.linesTranslucent();
				collector.submitShapeOutline(poseStack, state.outlineShape, lines, ARGB.black(102), state.lineWidth, false);
			}
			poseStack.popPose();
		}
		for (int i = 0; i < state.blockEntities.size(); i++) {
			float[] o = state.blockEntityOffsets.get(i);
			poseStack.pushPose();
			poseStack.translate(o[0], o[1], o[2]);
			Minecraft.getInstance().getBlockEntityRenderDispatcher().submit(state.blockEntities.get(i), poseStack, collector, camera);
			poseStack.popPose();
		}
		poseStack.popPose();
		super.submit(state, poseStack, collector, camera);
	}
}
