package me.skaffy.block;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import me.skaffy.net.FireHeatPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * How close each block is to catching fire. Fire feeds heat into the fuel around it a little every tick, and a block only
 * ignites once it has soaked up enough, so a fire creeps over a wall instead of jumping from block to block. Heat that is no
 * longer being fed cools off again. Progress is sent to nearby players so the client can draw the flames spreading.
 */
public final class FireHeat {
	private static final double SEND_RANGE = 96.0;
	private static final int SEND_INTERVAL = 4;
	private static final int COOL_DELAY = 60;

	private static final class Cell {
		float heat;
		long fed;
		byte sent = -1;
	}

	private static final Map<ServerLevel, Long2ObjectOpenHashMap<Cell>> CELLS = new WeakHashMap<>();

	private FireHeat() {
	}

	private static Long2ObjectOpenHashMap<Cell> cells(ServerLevel level) {
		return CELLS.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
	}

	/** Adds heat to a block. Returns true once it has taken enough to catch fire (the progress is then reset). */
	public static boolean feed(ServerLevel level, BlockPos pos, double amount) {
		Cell cell = cells(level).computeIfAbsent(pos.asLong(), k -> new Cell());
		cell.heat += (float) amount;
		cell.fed = level.getGameTime();
		if (cell.heat >= 1.0F) {
			cell.heat = 0.0F;
			return true;
		}
		return false;
	}

	/** Forget a block's progress, e.g. because it just caught fire or was broken. */
	public static void clear(ServerLevel level, BlockPos pos) {
		Cell cell = cells(level).get(pos.asLong());
		if (cell != null) {
			cell.heat = 0.0F;
		}
	}

	public static void tick(ServerLevel level) {
		long time = level.getGameTime();
		if (time % SEND_INTERVAL != 0) {
			return;
		}
		Long2ObjectOpenHashMap<Cell> map = cells(level);
		if (map.isEmpty()) {
			return;
		}
		LongArrayList changedPos = new LongArrayList();
		List<Byte> changedPercent = new ArrayList<>();
		var it = map.long2ObjectEntrySet().fastIterator();
		while (it.hasNext()) {
			var entry = it.next();
			Cell cell = entry.getValue();
			if (time - cell.fed > COOL_DELAY) {
				cell.heat -= 0.03F;
			}
			byte percent = (byte) Math.clamp(Math.round(cell.heat * 100.0F), 0, 100);
			if (percent != cell.sent) {
				// Only whole steps of a few percent are worth telling the clients about.
				if (percent == 0 || cell.sent < 0 || Math.abs(percent - cell.sent) >= 4) {
					changedPos.add(entry.getLongKey());
					changedPercent.add(percent);
					cell.sent = percent;
				}
			}
			if (cell.heat <= 0.0F && cell.sent <= 0) {
				it.remove();
			}
		}
		if (changedPos.isEmpty()) {
			return;
		}
		for (ServerPlayer player : level.players()) {
			LongArrayList pos = new LongArrayList();
			List<Byte> pct = new ArrayList<>();
			for (int i = 0; i < changedPos.size(); i++) {
				BlockPos p = BlockPos.of(changedPos.getLong(i));
				if (player.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) <= SEND_RANGE * SEND_RANGE) {
					pos.add(changedPos.getLong(i));
					pct.add(changedPercent.get(i));
				}
			}
			for (int start = 0; start < pos.size(); start += FireHeatPayload.MAX_ENTRIES) {
				int n = Math.min(FireHeatPayload.MAX_ENTRIES, pos.size() - start);
				long[] positions = new long[n];
				byte[] percent = new byte[n];
				for (int k = 0; k < n; k++) {
					positions[k] = pos.getLong(start + k);
					percent[k] = pct.get(start + k);
				}
				ServerPlayNetworking.send(player, new FireHeatPayload(positions, percent));
			}
		}
	}

	public static void forgetAll() {
		CELLS.clear();
	}
}
