package me.skaffy;

import me.skaffy.block.RealisticSandBlock;
import me.skaffy.block.RealisticWaterBlock;
import me.skaffy.fluid.RealisticWaterFluid;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

public final class ModContent {
	public static final FlowingFluid FLOWING_REALISTIC_WATER = Registry.register(
		BuiltInRegistries.FLUID, RealisticWater.id("flowing_realistic_water"), new RealisticWaterFluid.Flowing());
	public static final FlowingFluid REALISTIC_WATER = Registry.register(
		BuiltInRegistries.FLUID, RealisticWater.id("realistic_water"), new RealisticWaterFluid.Source());

	public static final ResourceKey<Block> REALISTIC_WATER_BLOCK_KEY = ResourceKey.create(Registries.BLOCK, RealisticWater.id("realistic_water"));
	public static final Block REALISTIC_WATER_BLOCK = Registry.register(BuiltInRegistries.BLOCK, REALISTIC_WATER_BLOCK_KEY, new RealisticWaterBlock(
		REALISTIC_WATER,
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.WATER)
			.replaceable()
			.noCollision()
			.strength(100.0F)
			.pushReaction(PushReaction.POPPED)
			.noLootTable()
			.liquid()
			.sound(SoundType.EMPTY)
			.setId(REALISTIC_WATER_BLOCK_KEY)
	));

	public static final ResourceKey<Item> REALISTIC_WATER_BUCKET_KEY = ResourceKey.create(Registries.ITEM, RealisticWater.id("realistic_water_bucket"));
	public static final Item REALISTIC_WATER_BUCKET = Registry.register(BuiltInRegistries.ITEM, REALISTIC_WATER_BUCKET_KEY, new BucketItem(
		REALISTIC_WATER, new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1).setId(REALISTIC_WATER_BUCKET_KEY)));

	public static final ResourceKey<Block> REALISTIC_SAND_KEY = ResourceKey.create(Registries.BLOCK, RealisticWater.id("realistic_sand"));
	public static final Block REALISTIC_SAND = Registry.register(BuiltInRegistries.BLOCK, REALISTIC_SAND_KEY, new RealisticSandBlock(
		BlockBehaviour.Properties.of()
			.mapColor(MapColor.SAND)
			.instrument(NoteBlockInstrument.SNARE)
			.strength(0.5F)
			.sound(SoundType.SAND)
			.noOcclusion()
			.pushReaction(PushReaction.POPPED)
			.isSuffocating((state, level, pos) -> false)
			.isViewBlocking((state, level, pos, box) -> false)
			.setId(REALISTIC_SAND_KEY)
	));

	public static final ResourceKey<Item> REALISTIC_SAND_ITEM_KEY = ResourceKey.create(Registries.ITEM, RealisticWater.id("realistic_sand"));
	public static final Item REALISTIC_SAND_ITEM = registerBlockItem(REALISTIC_SAND_ITEM_KEY, REALISTIC_SAND);

	private ModContent() {
	}

	private static Item registerBlockItem(ResourceKey<Item> key, Block block) {
		BlockItem item = new BlockItem(block, new Item.Properties().useBlockDescriptionPrefix().setId(key));
		item.registerBlocks(Item.BY_BLOCK, item);
		return Registry.register(BuiltInRegistries.ITEM, key, item);
	}

	static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
			.register(output -> output.insertAfter(Items.WATER_BUCKET, REALISTIC_WATER_BUCKET));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS)
			.register(output -> output.insertAfter(Items.SAND, REALISTIC_SAND_ITEM));
	}
}
