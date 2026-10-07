package me.skaffy.client.water;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.lang.reflect.Field;
import java.util.Map;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

public final class WaterCommands {
	private WaterCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
		dispatcher.register(literal("water")
			.then(literal("stats").executes(c -> {
				c.getSource().sendFeedback(Component.literal(WaterWorld.get().statsLine()));
				return 1;
			}))
			.then(literal("pause").executes(c -> {
				boolean paused = WaterWorld.get().togglePause();
				c.getSource().sendFeedback(Component.literal(paused ? "Simulation paused" : "Simulation running"));
				return 1;
			}))
			.then(literal("reload").executes(c -> {
				WaterWorld.get().reload(c.getSource().getLevel());
				c.getSource().sendFeedback(Component.literal("Re-reading realistic water blocks around you into fresh simulations"));
				return 1;
			}))
			.then(literal("ball")
				.executes(c -> ball(c, 3, Material.WATER))
				.then(argument("size", IntegerArgumentType.integer(1, 64)).executes(c -> ball(c, IntegerArgumentType.getInteger(c, "size"), Material.WATER))))
			.then(literal("sand")
				.executes(c -> ball(c, 2, Material.SAND))
				.then(argument("size", IntegerArgumentType.integer(1, 64)).executes(c -> ball(c, IntegerArgumentType.getInteger(c, "size"), Material.SAND))))
			.then(literal("remove")
				.executes(c -> {
					WaterWorld.get().removeAll(Minecraft.getInstance().options.renderDistance().get() + 2, Material.WATER);
					c.getSource().sendFeedback(Component.literal("Removing all realistic water around you (springs included)"));
					return 1;
				})
				.then(literal("sand").executes(c -> {
					WaterWorld.get().removeAll(Minecraft.getInstance().options.renderDistance().get() + 2, Material.SAND);
					c.getSource().sendFeedback(Component.literal("Removing all realistic sand around you"));
					return 1;
				})))
			.then(literal("set")
				.then(argument("name", StringArgumentType.word())
					.suggests((c, b) -> SharedSuggestionProvider.suggest(WaterSettings.fields().keySet(), b))
					.then(argument("value", StringArgumentType.greedyString()).executes(WaterCommands::set))))
			.then(literal("settings").executes(WaterCommands::listSettings))
			.then(literal("render")
				.executes(c -> {
					c.getSource().sendFeedback(Component.literal(ClipRecorder.get().statusLine()));
					return 1;
				})
				.then(literal("stop").executes(c -> {
					c.getSource().sendFeedback(Component.literal(ClipRecorder.get().stop("stopped")));
					return 1;
				}))
				.then(argument("seconds", DoubleArgumentType.doubleArg(0.05))
					.executes(c -> render(c, 60))
					.then(argument("fps", IntegerArgumentType.integer(1, 240)).executes(c -> render(c, IntegerArgumentType.getInteger(c, "fps")))))));
	}

	private static int ball(CommandContext<FabricClientCommandSource> c, int size, Material material) {
		var player = c.getSource().getPlayer();
		Vec3 center = player.getEyePosition().add(player.getLookAngle().scale(size + 3.0));
		double half = size * 0.5;
		WaterWorld.get().addEmitter(new WaterRegion.Emitter(
			center.x - half, center.y - half, center.z - half, center.x + half, center.y + half, center.z + half, 0, 0, 0), material);
		c.getSource().sendFeedback(Component.literal("Dropped a " + size + "^3 block cube of " + (material == Material.SAND ? "sand" : "water")));
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
