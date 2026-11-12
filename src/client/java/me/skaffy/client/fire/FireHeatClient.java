package me.skaffy.client.fire;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.skaffy.net.FireHeatPayload;
import net.minecraft.client.multiplayer.ClientLevel;

/** Burn progress (0 to 1) the server reported for blocks that are heating up, and when we last heard about each. */
public final class FireHeatClient {
	private static final long STALE_TICKS = 160;
	private static final Long2ObjectOpenHashMap<float[]> LEVELS = new Long2ObjectOpenHashMap<>();
	private static final Long2LongOpenHashMap HEARD = new Long2LongOpenHashMap();
	private static ClientLevel owner;

	private FireHeatClient() {
	}

	public static void receive(ClientLevel level, FireHeatPayload payload) {
		if (owner != level) {
			clear();
			owner = level;
		}
		long now = level.getGameTime();
		for (int i = 0; i < payload.positions().length; i++) {
			long pos = payload.positions()[i];
			float p = payload.percent()[i] / 100.0F;
			if (p <= 0.0F) {
				LEVELS.remove(pos);
				HEARD.remove(pos);
			} else {
				LEVELS.computeIfAbsent(pos, k -> new float[1])[0] = p;
				HEARD.put(pos, now);
			}
		}
	}

	/** Drops entries the server stopped talking about, and everything when the world changed. */
	public static void prune(ClientLevel level) {
		if (owner != level) {
			clear();
			owner = level;
			return;
		}
		long now = level.getGameTime();
		var it = HEARD.long2LongEntrySet().fastIterator();
		while (it.hasNext()) {
			var e = it.next();
			if (now - e.getLongValue() > STALE_TICKS) {
				LEVELS.remove(e.getLongKey());
				it.remove();
			}
		}
	}

	public static Long2ObjectOpenHashMap<float[]> levels() {
		return LEVELS;
	}

	public static void clear() {
		LEVELS.clear();
		HEARD.clear();
		owner = null;
	}
}
