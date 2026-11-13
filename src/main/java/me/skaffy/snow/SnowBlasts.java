package me.skaffy.snow;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.skaffy.net.SnowBlastPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class SnowBlasts {
	private static final double REACH = 160.0;

	private record Blast(Vec3 center, float radius, LongArrayList positions, ByteArrayList layers) {
	}

	private static final Map<ResourceKey<Level>, List<Blast>> PENDING = new HashMap<>();

	private SnowBlasts() {
	}

	public static void record(ServerLevel level, BlockPos pos, int layers, Vec3 center, float radius) {
		List<Blast> blasts = PENDING.computeIfAbsent(level.dimension(), k -> new ArrayList<>());
		Blast blast = null;
		for (Blast b : blasts) {
			if (b.center.distanceToSqr(center) < 1.0e-6 && b.radius == radius) {
				blast = b;
				break;
			}
		}
		if (blast == null) {
			blast = new Blast(center, radius, new LongArrayList(), new ByteArrayList());
			blasts.add(blast);
		}
		if (blast.positions.size() < SnowBlastPayload.MAX_ENTRIES) {
			blast.positions.add(pos.asLong());
			blast.layers.add((byte) layers);
		}
	}

	public static void tick(ServerLevel level) {
		List<Blast> blasts = PENDING.remove(level.dimension());
		if (blasts == null) {
			return;
		}
		for (Blast b : blasts) {
			SnowBlastPayload payload = new SnowBlastPayload(b.center.x, b.center.y, b.center.z, b.radius, b.positions.toLongArray(), b.layers.toByteArray());
			for (ServerPlayer player : level.players()) {
				if (player.distanceToSqr(b.center) < REACH * REACH && ServerPlayNetworking.canSend(player, SnowBlastPayload.TYPE)) {
					ServerPlayNetworking.send(player, payload);
				}
			}
		}
	}

	public static void clear() {
		PENDING.clear();
	}
}
