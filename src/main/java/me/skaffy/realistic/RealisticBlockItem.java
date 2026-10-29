package me.skaffy.realistic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.skaffy.ModContent;
import me.skaffy.RealisticWater;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

public class RealisticBlockItem extends Item {
	public RealisticBlockItem(Properties properties) {
		super(properties);
	}

	private static final Set<Block> REFUSED = Set.of(Blocks.BEDROCK, Blocks.BARRIER, Blocks.LIGHT, Blocks.STRUCTURE_VOID, Blocks.STRUCTURE_BLOCK,
		Blocks.JIGSAW, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK, Blocks.SPAWNER, Blocks.TRIAL_SPAWNER,
		Blocks.VAULT, Blocks.END_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.END_GATEWAY, Blocks.NETHER_PORTAL, Blocks.MOVING_PISTON);

	public static boolean convertible(ItemStack stack) {
		if (stack.getItem() instanceof BoatItem) {
			return true;
		}
		if (!(stack.getItem() instanceof BlockItem item)) {
			return false;
		}
		Block block = item.getBlock();
		if (block == ModContent.WAVE_MAKER) {
			return true;
		}
		return !REFUSED.contains(block) && !BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(RealisticWater.MOD_ID);
	}

	public static @Nullable BlockItem itemFor(Block block) {
		if (block.asItem() instanceof BlockItem direct) {
			return direct;
		}
		return ITEM_FOR_BLOCK.computeIfAbsent(block, b -> {
			for (Item item : BuiltInRegistries.ITEM) {
				if (item instanceof BlockItem blockItem && blockItem.getBlock() == b) {
					return Optional.of(blockItem);
				}
			}
			return Optional.<BlockItem>empty();
		}).orElse(null);
	}

	private static final Map<Block, Optional<BlockItem>> ITEM_FOR_BLOCK = new ConcurrentHashMap<>();

	public static ItemStack of(Block block, int count) {
		ItemStack stack = new ItemStack(ModContent.REALISTIC_BLOCK_ITEM, count);
		stack.set(ModContent.SOURCE_BLOCK, BuiltInRegistries.BLOCK.getKey(block));
		BlockItem item = itemFor(block);
		if (item != null) {
			stack.set(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(item));
		}
		stack.set(DataComponents.ITEM_NAME, Component.translatable("item.realistic-water.realistic_block.of", block.getName()));
		return stack;
	}

	public static ItemStack convert(ItemStack stack) {
		if (stack.getItem() instanceof BoatItem boat) {
			return RealisticBoatItem.of(boat, stack.getCount());
		}
		return convertible(stack) ? of(((BlockItem) stack.getItem()).getBlock(), stack.getCount()) : ItemStack.EMPTY;
	}

	public static @Nullable Block blockOf(ItemStack stack) {
		if (!stack.is(ModContent.REALISTIC_BLOCK_ITEM)) {
			return null;
		}
		Identifier id = stack.get(ModContent.SOURCE_BLOCK);
		return id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
	}

	@Override
	public InteractionResult useOn(final UseOnContext context) {
		ItemStack stack = context.getItemInHand();
		Block block = blockOf(stack);
		if (block == null) {
			return InteractionResult.FAIL;
		}
		BlockPlaceContext place = new BlockPlaceContext(context);
		if (!place.canPlace()) {
			return InteractionResult.FAIL;
		}
		Level level = context.getLevel();
		BlockPos pos = place.getClickedPos();
		Vector3d center = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		List<RealisticBlockEntity> near = level.getEntitiesOfClass(RealisticBlockEntity.class, new AABB(pos).inflate(1.0));
		for (RealisticBlockEntity e : near) {
			if (e.body.containsWorld(center.x, center.y, center.z)) {
				return InteractionResult.FAIL;
			}
		}
		BlockState state = block.getStateForPlacement(place);
		if (state == null) {
			state = block.defaultBlockState();
		}
		if (level instanceof ServerLevel server) {
			RealisticBlockEntity spawned = RealisticBlockEntity.spawn(server, List.of(BlockMaterials.cell(0, 0, 0, state)), center, null);
			Player placer = context.getPlayer();
			if (placer != null) {
				spawned.finishPlacement(placer, stack, state);
			}
			SoundType sound = state.getSoundType();
			level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
			Player player = context.getPlayer();
			level.gameEvent(player, GameEvent.BLOCK_PLACE, pos);
			if (player == null || !player.hasInfiniteMaterials()) {
				stack.shrink(1);
			}
		}
		return InteractionResult.SUCCESS;
	}
}
