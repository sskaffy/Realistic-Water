package me.skaffy.client.water;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.lang.reflect.Field;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec3;

public final class WaterCommands {
	private WaterCommands() {
	}

	private static LiteralArgumentBuilder<FabricClientCommandSource> op(String name) {
		return literal(name).requires(WaterCommands::isOperator);
	}

	private static boolean isOperator(FabricClientCommandSource source) {
		return source.getPlayer().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
		dispatcher.register(literal("water")
			.executes(WaterCommands::openMenu)
			.then(op("gui").executes(WaterCommands::openMenu))
			.then(op("stats").executes(c -> {
				c.getSource().sendFeedback(Component.literal(WaterWorld.get().statsLine()));
				return 1;
			}))
			.then(op("pause").executes(c -> {
				boolean paused = WaterWorld.get().togglePause();
				c.getSource().sendFeedback(Component.literal(paused ? "Simulation paused" : "Simulation running"));
				return 1;
			}))
			.then(op("reload").executes(c -> {
				WaterWorld.get().reload(c.getSource().getLevel());
				c.getSource().sendFeedback(Component.literal("Re-reading realistic water blocks around you into fresh simulations"));
				return 1;
			}))
			.then(op("ball")
				.executes(c -> ball(c, 3, Material.WATER))
				.then(argument("size", IntegerArgumentType.integer(1, 64)).executes(c -> ball(c, IntegerArgumentType.getInteger(c, "size"), Material.WATER))))
			.then(op("sand")
				.executes(c -> ball(c, 2, Material.SAND))
				.then(argument("size", IntegerArgumentType.integer(1, 64)).executes(c -> ball(c, IntegerArgumentType.getInteger(c, "size"), Material.SAND))))
			.then(op("lava")
				.executes(c -> ball(c, 3, Material.LAVA))
				.then(argument("size", IntegerArgumentType.integer(1, 64)).executes(c -> ball(c, IntegerArgumentType.getInteger(c, "size"), Material.LAVA))))
			.then(op("slime")
				.executes(c -> ball(c, 2, Material.SLIME))
				.then(argument("size", IntegerArgumentType.integer(1, 64)).executes(c -> ball(c, IntegerArgumentType.getInteger(c, "size"), Material.SLIME))))
			.then(op("leaves")
				.executes(c -> ball(c, 2, Material.LEAVES))
				.then(argument("size", IntegerArgumentType.integer(1, 64)).executes(c -> ball(c, IntegerArgumentType.getInteger(c, "size"), Material.LEAVES))))
			.then(op("freeze")
				.then(op("water").executes(c -> freeze(c, "water")))
				.then(op("slush").executes(c -> freeze(c, "slush")))
				.then(op("ice").executes(c -> freeze(c, "ice"))))
			.then(op("remove")
				.executes(c -> {
					WaterWorld.get().removeAll(Minecraft.getInstance().options.renderDistance().get() + 2, Material.WATER);
					c.getSource().sendFeedback(Component.literal("Removing all realistic water around you (springs included)"));
					return 1;
				})
				.then(op("sand").executes(c -> {
					WaterWorld.get().removeAll(Minecraft.getInstance().options.renderDistance().get() + 2, Material.SAND);
					c.getSource().sendFeedback(Component.literal("Removing all realistic sand around you"));
					return 1;
				}))
				.then(op("lava").executes(c -> {
					WaterWorld.get().removeAll(Minecraft.getInstance().options.renderDistance().get() + 2, Material.LAVA);
					c.getSource().sendFeedback(Component.literal("Removing all realistic lava around you (springs included)"));
					return 1;
				}))
				.then(op("slime").executes(c -> {
					WaterWorld.get().removeAll(Minecraft.getInstance().options.renderDistance().get() + 2, Material.SLIME);
					c.getSource().sendFeedback(Component.literal("Removing all realistic slime around you"));
					return 1;
				}))
				.then(op("leaves").executes(c -> {
					WaterWorld.get().removeAll(Minecraft.getInstance().options.renderDistance().get() + 2, Material.LEAVES);
					c.getSource().sendFeedback(Component.literal("Removing all realistic leaves around you"));
					return 1;
				}))
				.then(op("fire").executes(c -> {
					int removed = 0;
					var connection = c.getSource().getPlayer().connection;
					for (var source : java.util.List.copyOf(me.skaffy.client.fire.FireWorld.get().activeSources())) {
						var kind = source.kind();
						if (kind == me.skaffy.client.fire.FireWorld.Kind.REALISTIC_FIRE || kind == me.skaffy.client.fire.FireWorld.Kind.REALISTIC_SOUL_FIRE) {
							var p = source.blockPos();
							connection.sendCommand("setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " air");
							if (++removed >= 400) {
								break;
							}
						}
					}
					WaterWorld.get().removeFire();
					c.getSource().sendFeedback(Component.literal("Removing realistic fire around you (" + removed + " blocks"
						+ (removed >= 400 ? ", run it again for more" : "") + ") and clearing flames and smoke"));
					return 1;
				})))
			.then(op("set")
				.then(argument("name", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(WaterSettings.fields().keySet(), b))
					.then(argument("value", StringArgumentType.greedyString()).executes(WaterCommands::set))))
			.then(op("settings").executes(WaterCommands::listSettings))
			.then(op("render")
				.executes(c -> {
					c.getSource().sendFeedback(Component.literal(ClipRecorder.get().statusLine()));
					return 1;
				})
				.then(op("stop").executes(c -> {
					c.getSource().sendFeedback(Component.literal(ClipRecorder.get().stop("stopped")));
					return 1;
				}))
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.05))
					.executes(c -> render(c, 60))
					.then(argument("fps", IntegerArgumentType.integer(1, 240)).executes(c -> render(c, IntegerArgumentType.getInteger(c, "fps")))))));
	}

	private static int freeze(CommandContext<FabricClientCommandSource> c, String phase) {
		c.getSource().sendFeedback(Component.literal(WaterWorld.get().forcePhase(phase)));
		return 1;
	}

	private static int ball(CommandContext<FabricClientCommandSource> c, int size, Material material) {
		var player = c.getSource().getPlayer();
		Vec3 center = player.getEyePosition().add(player.getLookAngle().scale(size + 3.0));
		double half = size * 0.5;
		WaterWorld.get().addEmitter(new WaterRegion.Emitter(
			center.x - half, center.y - half, center.z - half, center.x + half, center.y + half, center.z + half, 0, 0, 0), material);
		c.getSource().sendFeedback(Component.literal("Dropped a " + size + "^3 block cube of " + material.label()));
		return 1;
	}

	private static int openMenu(CommandContext<FabricClientCommandSource> c) {
		WaterSettingsScreen.openNextTick();
		return 1;
	}

	private static int render(CommandContext<FabricClientCommandSource> c, int fps) {
		double seconds = DoubleArgumentType.getDouble(c, "seconds");
		c.getSource().sendFeedback(Component.literal(ClipRecorder.get().start(seconds, fps)));
		return 1;
	}

	private static int set(CommandContext<FabricClientCommandSource> c) {
		String name = StringArgumentType.getString(c, "name");
		String value = StringArgumentType.getString(c, "value");
		try {
			c.getSource().sendFeedback(Component.literal(name + " = " + WaterSettings.set(name, value)));
			WaterSettings.save();
		} catch (ReflectiveOperationException | NumberFormatException e) {
			c.getSource().sendError(Component.literal("Unknown setting or bad value: " + name));
		}
		return 1;
	}

	private static int listSettings(CommandContext<FabricClientCommandSource> c) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Field> e : WaterSettings.fields().entrySet()) {
			try {
				sb.append(e.getKey()).append('=').append(e.getValue().get(null)).append("  ");
			} catch (IllegalAccessException ignored) {
			}
		}
		c.getSource().sendFeedback(Component.literal(sb.toString()));
		return 1;
	}
}
