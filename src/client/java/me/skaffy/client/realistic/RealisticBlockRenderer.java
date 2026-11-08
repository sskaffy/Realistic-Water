package me.skaffy.client.realistic;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.TntMinecartRenderer;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.world.level.block.Blocks;
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
		int litIndex = -1;
		float fuse = -1.0F;
		final BlockModelRenderState litModel = new BlockModelRenderState();
		int litLight;
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

	private final BlockModelResolver blockModelResolver;

	public RealisticBlockRenderer(final EntityRendererProvider.Context context) {
		super(context);
		this.shadowRadius = 0.0F;
		this.blockModelResolver = context.getBlockModelResolver();
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
		state.litIndex = -1;
		int fuse = entity.syncedFuse();
		state.fuse = fuse >= 0 ? fuse - partialTicks + 1.0F : -1.0F;
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
				state.outlineShape = outline(cell);
			}
			float[] offset = {(float) (cell.x - body.com.x), (float) (cell.y - body.com.y), (float) (cell.z - body.com.z)};
			if (fuse >= 0 && state.litIndex < 0 && block.blockState.is(Blocks.TNT)) {
				state.litIndex = state.blocks.size();
				this.blockModelResolver.update(state.litModel, block.blockState, TntRenderer.BLOCK_DISPLAY_CONTEXT);
				state.litLight = LightCoordsUtil.pack(block.getBrightness(LightLayer.BLOCK, block.blockPos), block.getBrightness(LightLayer.SKY, block.blockPos));
			}
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

	private static final java.util.Map<Object, VoxelShape> OUTLINES = new java.util.concurrent.ConcurrentHashMap<>();

	private static VoxelShape outline(RigidBody.Cell cell) {
		if (cell.boxes == null) {
			return Shapes.block();
		}
		return OUTLINES.computeIfAbsent(cell.payload, k -> {
			VoxelShape shape = Shapes.empty();
			for (double[] b : cell.boxes) {
				shape = Shapes.or(shape, Shapes.box(b[0], b[1], b[2], b[3], b[4], b[5]));
			}
			return shape.isEmpty() ? Shapes.block() : shape.optimize();
		});
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
			if (i == state.litIndex) {
				poseStack.pushPose();
				if (state.fuse < 10.0F) {
					float scale = 1.0F + TntRenderer.getSwellAmount(state.fuse);
					poseStack.translate(0.5F, 0.5F, 0.5F);
					poseStack.scale(scale, scale, scale);
					poseStack.translate(-0.5F, -0.5F, -0.5F);
				}
				TntMinecartRenderer.submitWhiteSolidBlock(state.litModel, poseStack, collector, state.litLight, TntRenderer.isLit(state.fuse), state.outlineColor);
				poseStack.popPose();
			} else if (!me.skaffy.client.jelly.JellyWorld.drawsBodyCell(block.blockState) && !me.skaffy.client.ice.IceWorld.drawsBodyCell(block.blockState)) {
				collector.submitMovingBlock(poseStack, block, state.outlineColor);
			}
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
