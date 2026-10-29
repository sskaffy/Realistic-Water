package me.skaffy.client.water;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

final class BackgroundWorld {
	final ClientLevel level;
	final Identifier dimension;
	final Map<Integer, SolidCache> solids;
	final LongOpenHashSet portals;
	final Map<BlockPos, Material> sources;
	private final Map<Material, Long2IntOpenHashMap> written = new EnumMap<>(Material.class);

	BackgroundWorld(ClientLevel level, Map<Integer, SolidCache> solids, LongOpenHashSet portals, Map<BlockPos, Material> sources) {
		this.level = level;
		this.dimension = level.dimension().identifier();
		this.solids = solids;
		this.portals = portals;
		this.sources = new LinkedHashMap<>(sources);
	}

	int amount(long key, int inWorld, Material material) {
		Long2IntOpenHashMap map = this.written.get(material);
		return map != null && map.containsKey(key) ? map.get(key) : inWorld;
	}

	void wrote(long key, int amount, Material material) {
		this.written.computeIfAbsent(material, m -> new Long2IntOpenHashMap()).put(key, amount);
	}
}
