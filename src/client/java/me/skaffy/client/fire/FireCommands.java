package me.skaffy.client.fire;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class FireCommands {
	private FireCommands() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, CommandBuildContext context) {
		dispatcher.register(literal("fire")
			.then(literal("stats").executes(c -> {
				c.getSource().sendFeedback(Component.literal(FireWorld.get().statsLine()));
				return 1;
			}))
			.then(literal("rescan").executes(c -> {
				FireWorld.get().rescan(Minecraft.getInstance());
				c.getSource().sendFeedback(Component.literal("Re-reading chunks around you for fire sources"));
				return 1;
			}))
			.then(literal("list")
				.executes(c -> list(c, 10))
				.then(argument("count", IntegerArgumentType.integer(1, 100)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "count"))))));
	}

	private static int list(com.mojang.brigadier.context.CommandContext<FabricClientCommandSource> c, int count) {
		List<FireWorld.Source> active = FireWorld.get().activeSources();
		if (active.isEmpty()) {
			c.getSource().sendFeedback(Component.literal("No active fire sources"));
			return 1;
		}
		for (int i = 0; i < Math.min(count, active.size()); i++) {
			FireWorld.Source s = active.get(i);
			BlockPos p = s.blockPos();
			c.getSource().sendFeedback(Component.literal(
				s.kind().name().toLowerCase() + " at " + p.getX() + " " + p.getY() + " " + p.getZ() + " (intensity " + s.intensity() + ")"));
		}
		return 1;
	}
}
