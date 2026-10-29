package me.skaffy.net;

import me.skaffy.RealisticWater;
import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.GridInteraction;
import me.skaffy.realistic.GridMirror;
import me.skaffy.realistic.GridSpace;
import me.skaffy.realistic.RealisticBlockEntity;
import me.skaffy.realistic.RealisticBlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

public record BreakRealisticPayload(int entityId, int x, int y, int z) implements CustomPacketPayload {
	public static final Type<BreakRealisticPayload> TYPE = new Type<>(RealisticWater.id("break_realistic"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BreakRealisticPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, BreakRealisticPayload::entityId,
		ByteBufCodecs.VAR_INT, BreakRealisticPayload::x,
		ByteBufCodecs.VAR_INT, BreakRealisticPayload::y,
		ByteBufCodecs.VAR_INT, BreakRealisticPayload::z,
		BreakRealisticPayload::new);

	@Override
	public Type<BreakRealisticPayload> type() {
		return TYPE;
	}

	public void apply(ServerPlayer player) {
		ServerLevel level = player.level();
		if (!(level.getEntity(this.entityId) instanceof RealisticBlockEntity e) || !player.mayBuild()) {
			return;
		}
		RigidBody.Cell cell = e.body.cellAt(this.x, this.y, this.z);
		if (cell == null) {
			return;
		}
		Vector3d c = e.body.toWorld(this.x + 0.5, this.y + 0.5, this.z + 0.5, new Vector3d());
		double reach = player.blockInteractionRange() + 1.5;
		if (player.getEyePosition().distanceToSqr(c.x, c.y, c.z) > reach * reach) {
			return;
		}
		BlockState state = (BlockState) cell.payload;
		GridMirror grid = e.grid();
		GridSpace.Slot slot = grid == null ? null : grid.slot();
		if (slot != null) {
			BlockPos at = slot.world(this.x, this.y, this.z);
			GridInteraction.asGridPlayer(player, e, slot, () -> level.destroyBlock(at, false, player));
			grid.pull();
		} else {
			level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, BlockPos.containing(c.x, c.y, c.z), Block.getId(state));
			e.removeCell(this.x, this.y, this.z);
		}
		if (!player.hasInfiniteMaterials() && state.getBlock().asItem() != net.minecraft.world.item.Items.AIR) {
			ItemEntity drop = new ItemEntity(level, c.x, c.y, c.z, RealisticBlockItem.of(state.getBlock(), 1));
			drop.setDefaultPickUpDelay();
			level.addFreshEntity(drop);
		}
	}
}
