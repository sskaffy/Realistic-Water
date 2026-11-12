package me.skaffy.test;

import java.util.Map;
import me.skaffy.ModContent;
import me.skaffy.block.RealisticFireBlock;
import me.skaffy.client.fire.FireWorld;
import me.skaffy.client.water.WaterSettings;
import me.skaffy.mixin.FireBlockAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class FireClientGameTest implements FabricClientGameTest {
	private static void expect(Map<FireWorld.Kind, Integer> counts, FireWorld.Kind kind, int expected, String label) {
		int actual = counts.getOrDefault(kind, 0);
		if (actual != expected) {
			throw new AssertionError(label + ": expected " + expected + " " + kind + " sources but found " + actual + " (" + FireWorld.get().statsLine() + ")");
		}
		System.out.println("[fire-test] OK " + label + ": " + kind + "=" + actual);
	}

	private static void check(boolean condition, String label) {
		if (!condition) {
			throw new AssertionError(label);
		}
		System.out.println("[fire-test] OK " + label);
	}

	private static Map<FireWorld.Kind, Integer> counts(ClientGameTestContext context) {
		Map<FireWorld.Kind, Integer>[] out = new Map[1];
		context.runOnClient(client -> out[0] = FireWorld.get().countsByKind());
		return out[0];
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.runOnClient(client -> WaterSettings.fireVanillaBlocks = false);
			world.getServer().runCommand("gamemode creative @p");
			world.getServer().runCommand("execute at @p run setblock ~2 ~ ~ torch");
			world.getServer().runCommand("execute at @p run setblock ~2 ~ ~2 soul_torch");
			world.getServer().runCommand("execute at @p run setblock ~4 ~ ~ campfire[lit=true]");
			world.getServer().runCommand("execute at @p run setblock ~4 ~ ~2 campfire[lit=false]");
			world.getServer().runCommand("execute at @p run setblock ~4 ~ ~4 soul_campfire[lit=true]");
			world.getServer().runCommand("execute at @p run setblock ~6 ~ ~ realistic-water:realistic_fire");
			// Two layers of vanilla lava over a 3x3 patch: only the exposed top layer may count (when vanilla is enabled).
			world.getServer().runCommand("execute at @p run fill ~8 ~-1 ~8 ~10 ~-1 ~10 lava");
			world.getServer().runCommand("execute at @p run fill ~8 ~-2 ~8 ~10 ~-2 ~10 lava");
			context.waitTicks(40);

			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				BlockPos fire = player.blockPosition().offset(6, 0, 0);
				System.out.println("[fire-test] server block at " + fire + " = " + player.level().getBlockState(fire));
			});
			context.runOnClient(client -> {
				BlockPos fire = client.player.blockPosition().offset(6, 0, 0);
				System.out.println("[fire-test] client block at " + fire + " = " + client.level.getBlockState(fire) + " / " + FireWorld.get().statsLine());
			});

			// Default config: only the mod's own blocks emit.
			Map<FireWorld.Kind, Integer> c = counts(context);
			expect(c, FireWorld.Kind.REALISTIC_FIRE, 1, "realistic fire counted by default");
			expect(c, FireWorld.Kind.TORCH, 0, "vanilla torch ignored by default");
			expect(c, FireWorld.Kind.CAMPFIRE, 0, "vanilla campfire ignored by default");
			expect(c, FireWorld.Kind.LAVA, 0, "vanilla lava ignored by default");

			// Opting in brings the vanilla blocks back.
			context.runOnClient(client -> WaterSettings.fireVanillaBlocks = true);
			context.waitTicks(40);
			c = counts(context);
			expect(c, FireWorld.Kind.REALISTIC_FIRE, 1, "realistic fire still counted");
			expect(c, FireWorld.Kind.TORCH, 1, "torch counted when vanilla enabled");
			expect(c, FireWorld.Kind.SOUL_TORCH, 1, "soul torch");
			expect(c, FireWorld.Kind.CAMPFIRE, 1, "lit campfire only (unlit ignored)");
			expect(c, FireWorld.Kind.SOUL_CAMPFIRE, 1, "soul campfire");
			expect(c, FireWorld.Kind.LAVA, 9, "lava surface only (buried layer ignored)");

			// Removing a torch must drop it; covering lava must hide it; uncovering brings it back.
			world.getServer().runCommand("execute at @p run setblock ~2 ~ ~ air");
			world.getServer().runCommand("execute at @p run fill ~8 ~ ~8 ~10 ~ ~10 stone");
			context.waitTicks(20);
			c = counts(context);
			expect(c, FireWorld.Kind.TORCH, 0, "torch removed");
			expect(c, FireWorld.Kind.LAVA, 0, "lava covered by stone");
			world.getServer().runCommand("execute at @p run fill ~8 ~ ~8 ~10 ~ ~10 air");
			context.waitTicks(20);
			expect(counts(context), FireWorld.Kind.LAVA, 9, "lava uncovered");

			// Realistic flint and steel places realistic fire (and not vanilla fire).
			boolean[] placed = new boolean[1];
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				ItemStack flint = new ItemStack(ModContent.REALISTIC_FLINT_AND_STEEL);
				player.setItemInHand(InteractionHand.MAIN_HAND, flint);
				BlockPos ground = player.blockPosition().below().offset(0, 0, -3);
				BlockHitResult hit = new BlockHitResult(new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5), Direction.UP, ground, false);
				InteractionResult result = flint.getItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
				placed[0] = result.consumesAction() && RealisticFireBlock.isRealistic(player.level().getBlockState(ground.above()));
				System.out.println("[fire-test] flint and steel result=" + result + " damage=" + flint.getDamageValue());
			});
			check(placed[0], "realistic flint and steel placed realistic fire");
			context.waitTicks(10);
			expect(counts(context), FireWorld.Kind.REALISTIC_FIRE, 2, "second realistic fire counted");

			// Realistic fire must know what is flammable, otherwise it would never spread.
			boolean[] flammable = new boolean[1];
			world.getServer().runOnServer(server -> {
				FireBlockAccessor fire = (FireBlockAccessor) ModContent.REALISTIC_FIRE;
				flammable[0] = !fire.realisticWater$burnOdds().isEmpty() && !fire.realisticWater$igniteOdds().isEmpty();
			});
			check(flammable[0], "realistic fire copied vanilla flammability tables");

			// Realistic torches: placed by command and by item, standing and on a wall, plus soul torches.
			world.getServer().runCommand("execute at @p run setblock ~12 ~ ~-8 realistic-water:realistic_torch");
			world.getServer().runCommand("execute at @p run setblock ~14 ~ ~-8 stone");
			world.getServer().runCommand("execute at @p run setblock ~15 ~ ~-8 realistic-water:realistic_wall_torch[facing=east]");
			world.getServer().runCommand("execute at @p run setblock ~12 ~ ~-6 realistic-water:realistic_soul_torch");
			world.getServer().runCommand("execute at @p run setblock ~14 ~ ~-10 stone");
			boolean[] torchOk = new boolean[4];
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				var level = server.overworld();
				BlockPos origin = player.blockPosition();
				// Item on the top face of the ground places a standing torch.
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.REALISTIC_TORCH_ITEM));
				BlockPos ground = origin.offset(12, -1, -10);
				var top = new BlockHitResult(new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5), Direction.UP, ground, false);
				ModContent.REALISTIC_TORCH_ITEM.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, top));
				torchOk[0] = level.getBlockState(ground.above()).is(ModContent.REALISTIC_TORCH);
				// Item on the side of a stone block places a wall torch.
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.REALISTIC_TORCH_ITEM));
				BlockPos wall = origin.offset(14, 0, -10);
				var side = new BlockHitResult(new Vec3(wall.getX() + 1.0, wall.getY() + 0.5, wall.getZ() + 0.5), Direction.EAST, wall, false);
				ModContent.REALISTIC_TORCH_ITEM.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, side));
				torchOk[1] = level.getBlockState(wall.east()).is(ModContent.REALISTIC_WALL_TORCH);
				// Breaking a wall torch must drop the torch item, not the wall block (which has no item).
				var drops = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(origin.offset(15, 0, -8)), level, origin.offset(15, 0, -8), null);
				torchOk[2] = drops.size() == 1 && drops.get(0).is(ModContent.REALISTIC_TORCH_ITEM);
				System.out.println("[fire-test] wall torch drops=" + drops);
			});
			check(torchOk[0], "realistic torch item placed a standing torch");
			check(torchOk[1], "realistic torch item placed a wall torch");
			check(torchOk[2], "wall torch drops the realistic torch item");
			context.waitTicks(10);
			c = counts(context);
			expect(c, FireWorld.Kind.REALISTIC_TORCH, 4, "realistic torches (2 by command, 2 by item)");
			expect(c, FireWorld.Kind.REALISTIC_SOUL_TORCH, 1, "realistic soul torch");

			// Realistic campfires: lit/unlit, vanilla block entity (cooking), drops, flint and steel lighting.
			world.getServer().runCommand("execute at @p run setblock ~12 ~ ~0 realistic-water:realistic_campfire[lit=true]");
			world.getServer().runCommand("execute at @p run setblock ~12 ~ ~2 realistic-water:realistic_campfire[lit=false]");
			world.getServer().runCommand("execute at @p run setblock ~14 ~ ~0 realistic-water:realistic_soul_campfire[lit=true]");
			context.waitTicks(10);
			c = counts(context);
			expect(c, FireWorld.Kind.REALISTIC_CAMPFIRE, 1, "lit realistic campfire counted, unlit ignored");
			expect(c, FireWorld.Kind.REALISTIC_SOUL_CAMPFIRE, 1, "lit realistic soul campfire counted");
			boolean[] campfireOk = new boolean[5];
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				var level = server.overworld();
				BlockPos origin = player.blockPosition();
				BlockPos lit = origin.offset(12, 0, 0);
				BlockPos unlit = origin.offset(12, 0, 2);
				BlockPos soul = origin.offset(14, 0, 0);
				// The vanilla campfire block entity must accept the realistic block, and cooking must work.
				var be = level.getBlockEntity(lit);
				campfireOk[0] = be instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity;
				if (be instanceof net.minecraft.world.level.block.entity.CampfireBlockEntity campfire) {
					boolean accepted = campfire.placeFood(level, player, new ItemStack(net.minecraft.world.item.Items.POTATO));
					campfireOk[1] = accepted && !campfire.getItems().get(0).isEmpty();
				}
				// Drops: charcoal normally, soul soil for the soul campfire.
				var drops = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(lit), level, lit, null);
				var soulDrops = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(soul), level, soul, null);
				System.out.println("[fire-test] campfire drops=" + drops + " soul drops=" + soulDrops);
				campfireOk[2] = drops.size() == 1 && drops.get(0).is(net.minecraft.world.item.Items.CHARCOAL) && drops.get(0).getCount() == 2;
				campfireOk[3] = soulDrops.size() == 1 && soulDrops.get(0).is(net.minecraft.world.item.Items.SOUL_SOIL);
				// Realistic flint and steel lights an unlit realistic campfire (vanilla rules, via the campfires tag).
				boolean wasLightable = net.minecraft.world.level.block.CampfireBlock.canLight(level.getBlockState(unlit));
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.REALISTIC_FLINT_AND_STEEL));
				var hit = new BlockHitResult(new Vec3(unlit.getX() + 0.5, unlit.getY() + 1.0, unlit.getZ() + 0.5), Direction.UP, unlit, false);
				ModContent.REALISTIC_FLINT_AND_STEEL.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
				campfireOk[4] = wasLightable && level.getBlockState(unlit).getValue(net.minecraft.world.level.block.CampfireBlock.LIT);
			});
			check(campfireOk[0], "realistic campfire uses the vanilla campfire block entity");
			check(campfireOk[1], "realistic campfire cooks food");
			check(campfireOk[2], "realistic campfire drops 2 charcoal");
			check(campfireOk[3], "realistic soul campfire drops soul soil");
			check(campfireOk[4], "flint and steel lights an unlit realistic campfire");
			context.waitTicks(10);
			expect(counts(context), FireWorld.Kind.REALISTIC_CAMPFIRE, 2, "campfire counted after lighting");

			// Soul fire and fire charge: flint over soul soil gives realistic soul fire, the charge gives realistic fire.
			world.getServer().runCommand("execute at @p run setblock ~16 ~-1 ~4 soul_soil");
			boolean[] soulOk = new boolean[2];
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				var level = server.overworld();
				BlockPos origin = player.blockPosition();
				BlockPos soil = origin.offset(16, -1, 4);
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.REALISTIC_FLINT_AND_STEEL));
				var onSoil = new BlockHitResult(new Vec3(soil.getX() + 0.5, soil.getY() + 1.0, soil.getZ() + 0.5), Direction.UP, soil, false);
				ModContent.REALISTIC_FLINT_AND_STEEL.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, onSoil));
				soulOk[0] = level.getBlockState(soil.above()).is(ModContent.REALISTIC_SOUL_FIRE);
				BlockPos grass = origin.offset(18, -1, 4);
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.REALISTIC_FIRE_CHARGE));
				var onGrass = new BlockHitResult(new Vec3(grass.getX() + 0.5, grass.getY() + 1.0, grass.getZ() + 0.5), Direction.UP, grass, false);
				ModContent.REALISTIC_FIRE_CHARGE.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, onGrass));
				soulOk[1] = RealisticFireBlock.isRealistic(level.getBlockState(grass.above()));
			});
			check(soulOk[0], "flint and steel over soul soil placed realistic soul fire");
			check(soulOk[1], "realistic fire charge placed realistic fire");
			context.waitTicks(10);
			expect(counts(context), FireWorld.Kind.REALISTIC_SOUL_FIRE, 1, "realistic soul fire counted");

			// Copper torches and redstone torches (a lit redstone torch must still power the block above it).
			world.getServer().runCommand("execute at @p run setblock ~12 ~ ~-18 realistic-water:realistic_copper_torch");
			world.getServer().runCommand("execute at @p run setblock ~14 ~ ~-18 stone");
			world.getServer().runCommand("execute at @p run setblock ~15 ~ ~-18 realistic-water:realistic_copper_wall_torch[facing=east]");
			world.getServer().runCommand("execute at @p run setblock ~12 ~1 ~-20 redstone_lamp");
			world.getServer().runCommand("execute at @p run setblock ~12 ~ ~-20 realistic-water:realistic_redstone_torch[lit=true]");
			world.getServer().runCommand("execute at @p run setblock ~14 ~ ~-20 realistic-water:realistic_redstone_torch[lit=false]");
			context.waitTicks(20);
			c = counts(context);
			expect(c, FireWorld.Kind.REALISTIC_COPPER_TORCH, 2, "realistic copper torch (standing and wall)");
			expect(c, FireWorld.Kind.REALISTIC_REDSTONE_TORCH, 2, "both unpowered realistic redstone torches relit themselves and are counted");
			boolean[] redstoneOk = new boolean[2];
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				var level = server.overworld();
				BlockPos origin = player.blockPosition();
				redstoneOk[0] = level.getBlockState(origin.offset(12, 1, -20)).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT);
				var drops = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(origin.offset(15, 0, -18)), level, origin.offset(15, 0, -18), null);
				redstoneOk[1] = drops.size() == 1 && drops.get(0).is(ModContent.REALISTIC_COPPER_TORCH_ITEM);
			});
			check(redstoneOk[0], "realistic redstone torch powers the lamp above it");
			check(redstoneOk[1], "realistic copper wall torch drops the copper torch item");

			// Furnaces, blast furnaces, smokers and candles.
			world.getServer().runCommand("execute at @p run setblock ~12 ~ ~-14 realistic-water:realistic_furnace[lit=true,facing=north]");
			world.getServer().runCommand("execute at @p run setblock ~14 ~ ~-14 realistic-water:realistic_blast_furnace[lit=true,facing=north]");
			world.getServer().runCommand("execute at @p run setblock ~16 ~ ~-14 realistic-water:realistic_smoker[lit=true,facing=north]");
			world.getServer().runCommand("execute at @p run setblock ~18 ~ ~-14 realistic-water:realistic_furnace[lit=false,facing=north]");
			world.getServer().runCommand("execute at @p run setblock ~12 ~ ~-16 realistic-water:realistic_red_candle[lit=true,candles=2]");
			world.getServer().runCommand("execute at @p run setblock ~14 ~ ~-16 realistic-water:realistic_candle[lit=false]");
			context.waitTicks(10);
			c = counts(context);
			expect(c, FireWorld.Kind.REALISTIC_FURNACE, 2, "lit realistic furnace and blast furnace counted, unlit ignored");
			expect(c, FireWorld.Kind.REALISTIC_SMOKER, 1, "lit realistic smoker counted");
			expect(c, FireWorld.Kind.REALISTIC_CANDLE, 1, "lit realistic candle counted, unlit ignored");
			boolean[] stoveOk = new boolean[6];
			world.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().get(0);
				var level = server.overworld();
				BlockPos origin = player.blockPosition();
				stoveOk[0] = level.getBlockEntity(origin.offset(12, 0, -14)) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity
					&& level.getBlockEntity(origin.offset(14, 0, -14)) instanceof net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity
					&& level.getBlockEntity(origin.offset(16, 0, -14)) instanceof net.minecraft.world.level.block.entity.SmokerBlockEntity;
				// Start smelting in the unlit furnace: the vanilla ticker must run for the realistic block.
				if (level.getBlockEntity(origin.offset(18, 0, -14)) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace) {
					furnace.setItem(0, new ItemStack(net.minecraft.world.item.Items.RAW_IRON));
					furnace.setItem(1, new ItemStack(net.minecraft.world.item.Items.COAL));
					stoveOk[1] = true;
				}
				var furnaceDrops = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(origin.offset(12, 0, -14)), level, origin.offset(12, 0, -14), null);
				stoveOk[2] = furnaceDrops.size() == 1 && furnaceDrops.get(0).is(ModContent.REALISTIC_FURNACE_ITEM);
				var candleDrops = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(origin.offset(12, 0, -16)), level, origin.offset(12, 0, -16), null);
				stoveOk[3] = candleDrops.size() == 1 && candleDrops.get(0).is(ModContent.REALISTIC_CANDLE_ITEMS.get("red_candle")) && candleDrops.get(0).getCount() == 2;
				System.out.println("[fire-test] furnace drops=" + furnaceDrops + " candle drops=" + candleDrops);
				// Flint and steel lights an unlit realistic candle (via the minecraft:candles tag).
				BlockPos candle = origin.offset(14, 0, -16);
				boolean lightable = net.minecraft.world.level.block.CandleBlock.canLight(level.getBlockState(candle));
				player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModContent.REALISTIC_FLINT_AND_STEEL));
				var hit = new BlockHitResult(new Vec3(candle.getX() + 0.5, candle.getY() + 0.5, candle.getZ() + 0.5), Direction.UP, candle, false);
				ModContent.REALISTIC_FLINT_AND_STEEL.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
				stoveOk[4] = lightable && level.getBlockState(candle).getValue(net.minecraft.world.level.block.CandleBlock.LIT);
			});
			check(stoveOk[0], "realistic furnace, blast furnace and smoker use the vanilla block entities");
			check(stoveOk[1], "realistic furnace accepted smelting items");
			check(stoveOk[2], "realistic furnace drops itself");
			check(stoveOk[3], "realistic candle drops its stack");
			check(stoveOk[4], "flint and steel lights an unlit realistic candle");
			context.waitTicks(10);
			expect(counts(context), FireWorld.Kind.REALISTIC_CANDLE, 2, "candle counted after lighting");

			// Spreading: realistic fire next to planks must burn it and the new flames must be realistic, never vanilla.
			// Vanilla lava ignites nearby planks with vanilla fire, which would confound the check below, so remove it first.
			world.getServer().runCommand("execute at @p run fill ~8 ~-2 ~8 ~10 ~-1 ~10 stone");
			world.getServer().runCommand("execute at @p run fill ~0 ~-1 ~8 ~5 ~-1 ~13 oak_planks");
			world.getServer().runCommand("execute at @p run fill ~0 ~ ~8 ~5 ~2 ~13 oak_planks");
			for (String at : new String[]{"~2 ~3 ~10", "~0 ~3 ~8", "~4 ~3 ~9", "~1 ~3 ~12", "~5 ~3 ~13", "~3 ~3 ~11"}) {
				world.getServer().runCommand("execute at @p run setblock " + at + " realistic-water:realistic_fire");
			}
			int[] tally = new int[3];
			for (int round = 0; round < 6; round++) {
				context.waitTicks(100);
				world.getServer().runOnServer(server -> {
					ServerPlayer player = server.getPlayerList().getPlayers().get(0);
					BlockPos origin = player.blockPosition();
					int vanillaFire = 0;
					int realisticFire = 0;
					int planks = 0;
					for (int x = -2; x <= 8; x++) {
						for (int y = -3; y <= 6; y++) {
							for (int z = 5; z <= 16; z++) {
								var state = player.level().getBlockState(origin.offset(x, y, z));
								if (state.is(net.minecraft.world.level.block.Blocks.FIRE)) {
									vanillaFire++;
								} else if (RealisticFireBlock.isRealistic(state)) {
									realisticFire++;
								} else if (state.is(net.minecraft.tags.BlockTags.PLANKS)) {
									planks++;
								}
							}
						}
					}
					tally[0] = Math.max(tally[0], vanillaFire);
					tally[1] = Math.max(tally[1], realisticFire);
					tally[2] = planks;
				});
				System.out.println("[fire-test] spread round " + round + ": max vanilla=" + tally[0] + " max realistic=" + tally[1] + " planks left=" + tally[2]);
			}
			boolean[] smelted = new boolean[1];
			world.getServer().runOnServer(server -> {
				BlockPos furnacePos = server.getPlayerList().getPlayers().get(0).blockPosition().offset(18, 0, -14);
				if (server.overworld().getBlockEntity(furnacePos) instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace) {
					System.out.println("[fire-test] furnace output slot=" + furnace.getItem(2));
					smelted[0] = furnace.getItem(2).is(net.minecraft.world.item.Items.IRON_INGOT);
				}
			});
			check(smelted[0], "realistic furnace smelted raw iron into an iron ingot");
			check(tally[0] == 0, "spreading realistic fire never produced vanilla fire");
			check(tally[1] >= 7 || tally[2] < 144, "realistic fire spread or burned planks (max flames " + tally[1] + ", planks left " + tally[2] + ")");

			// Every item the mod adds must be reachable from the creative inventory.
			java.util.List<String> missing = new java.util.ArrayList<>();
			context.runOnClient(client -> {
				net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(client.level.enabledFeatures(), true, client.level.registryAccess());
				var shown = net.minecraft.world.item.CreativeModeTabs.searchTab().getSearchTabDisplayItems();
				for (var entry : net.minecraft.core.registries.BuiltInRegistries.ITEM.entrySet()) {
					if (!entry.getKey().identifier().getNamespace().equals("realistic-water")) {
						continue;
					}
					boolean found = shown.stream().anyMatch(stack -> stack.is(entry.getValue()));
					if (!found) {
						missing.add(entry.getKey().identifier().getPath());
					}
				}
			});
			check(missing.isEmpty(), "every realistic item is in the creative inventory (missing: " + missing + ")");

			// Phase 2: realistic fire draws particles, and the switch turns them off.
			world.getServer().runCommand("tp @p ~ ~ ~");
			world.getServer().runCommand("execute at @p run setblock ^ ^ ^4 realistic-water:realistic_campfire[lit=true]");
			world.getServer().runCommand("execute at @p run setblock ^2 ^ ^4 realistic-water:realistic_torch");
			world.getServer().runCommand("execute at @p run setblock ^-2 ^ ^4 realistic-water:realistic_fire");
			context.runOnClient(client -> {
				WaterSettings.fireParticles = true;
				FireWorld.get().rescan(client);
			});
			context.waitTicks(10);
			long[] marks = new long[3];
			context.runOnClient(client -> marks[0] = me.skaffy.client.fire.FireEffects.totalSpawned());
			context.waitTicks(40);
			context.runOnClient(client -> marks[1] = me.skaffy.client.fire.FireEffects.totalSpawned());
			context.takeScreenshot("fire-phase2-particles");
			check(marks[1] - marks[0] > 40, "realistic fire sources spawned particles (" + (marks[1] - marks[0]) + " in 40 ticks)");
			context.runOnClient(client -> WaterSettings.fireParticles = false);
			context.waitTicks(5);
			context.runOnClient(client -> marks[0] = me.skaffy.client.fire.FireEffects.totalSpawned());
			context.waitTicks(20);
			context.runOnClient(client -> marks[2] = me.skaffy.client.fire.FireEffects.totalSpawned());
			check(marks[2] == marks[0], "fireParticles=false stops all fire particles");
			context.runOnClient(client -> WaterSettings.fireParticles = true);

			// Phase 3: steam beside water, embers over realistic lava, wind moves smoke.
			world.getServer().runCommand("execute at @p run setblock ^6 ^ ^4 realistic-water:realistic_fire");
			world.getServer().runCommand("execute at @p run setblock ^7 ^ ^4 water");
			world.getServer().runCommand("execute at @p run setblock ^-6 ^-1 ^4 realistic-water:realistic_lava");
			context.runOnClient(client -> FireWorld.get().rescan(client));
			context.waitTicks(10);
			long[] phase3 = new long[4];
			context.runOnClient(client -> {
				phase3[0] = me.skaffy.client.fire.FireEffects.steamSpawned();
				phase3[1] = me.skaffy.client.fire.FireEffects.emberSpawned();
			});
			context.waitTicks(200);
			context.runOnClient(client -> {
				phase3[2] = me.skaffy.client.fire.FireEffects.steamSpawned();
				phase3[3] = me.skaffy.client.fire.FireEffects.emberSpawned();
				System.out.println("[fire-test] wind " + me.skaffy.client.fire.FireEffects.wind());
			});
			context.takeScreenshot("fire-phase3-interactions");
			check(phase3[2] > phase3[0], "fire beside water made steam (" + (phase3[2] - phase3[0]) + ")");
			check(phase3[3] > phase3[1], "realistic lava threw embers (" + (phase3[3] - phase3[1]) + ")");
			boolean[] windy = new boolean[1];
			context.runOnClient(client -> windy[0] = me.skaffy.client.fire.FireEffects.wind().lengthSqr() > 0.0);
			check(windy[0], "wind is blowing");

			context.runOnClient(client -> System.out.println("[fire-test] " + FireWorld.get().statsLine()));
			context.takeScreenshot("fire-phase1-1-sources");
		}
	}
}
