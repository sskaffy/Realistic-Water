package me.skaffy.client.realistic;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import me.skaffy.physics.RigidBody;
import me.skaffy.realistic.RealisticBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

public final class DynamicLights {
	private static final double RANGE = 128.0;
	private static final int REFRESH_TICKS = 40;
	private static Long2IntOpenHashMap lights = new Long2IntOpenHashMap();
	private static int age;

	private DynamicLights() {
	}

	public static boolean any() {
		return !lights.isEmpty();
	}

	public static int emission(long blockNode) {
		return lights.get(blockNode);
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			lights = new Long2IntOpenHashMap();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		Long2IntOpenHashMap now = new Long2IntOpenHashMap();
		Vector3d c = new Vector3d();
		for (RealisticBlockEntity e : RealisticBlockEntity.near(level, mc.player.getBoundingBox().inflate(RANGE))) {
			for (RigidBody.Cell cell : e.body.cells) {
				int light = cell.payload instanceof BlockState state ? state.getLightEmission() : 0;
				if (light <= 0) {
					continue;
				}
				e.body.toWorld(cell.x + 0.5, cell.y + 0.5, cell.z + 0.5, c);
				long pos = BlockPos.asLong((int) Math.floor(c.x), (int) Math.floor(c.y), (int) Math.floor(c.z));
				if (light > now.get(pos)) {
					now.put(pos, light);
				}
			}
		}
		Long2IntOpenHashMap before = lights;
		boolean refresh = ++age >= REFRESH_TICKS;
		if (refresh) {
			age = 0;
		}
		lights = now;
		var engine = level.getLightEngine();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (Long2IntMap.Entry entry : before.long2IntEntrySet()) {
			if (now.get(entry.getLongKey()) != entry.getIntValue()) {
				engine.checkBlock(p.set(entry.getLongKey()));
			}
		}
		for (Long2IntMap.Entry entry : now.long2IntEntrySet()) {
			if (refresh || before.get(entry.getLongKey()) != entry.getIntValue()) {
				engine.checkBlock(p.set(entry.getLongKey()));
			}
		}
	}
}
