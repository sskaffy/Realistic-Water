package me.skaffy.liquid;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import me.skaffy.RealisticWater;
import me.skaffy.net.LiquidDefsPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

public final class CustomLiquids extends SavedData {
	private static final Codec<CustomLiquids> CODEC = CustomLiquid.CODEC.listOf().xmap(CustomLiquids::new, CustomLiquids::list);
	public static final SavedDataType<CustomLiquids> TYPE = new SavedDataType<>(RealisticWater.id("custom_liquids"), CustomLiquids::new, CODEC,
		DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	private static volatile CustomLiquid[] client = new CustomLiquid[CustomLiquid.MAX_SLOTS];
	private static volatile int clientVersion;

	private final CustomLiquid[] slots = new CustomLiquid[CustomLiquid.MAX_SLOTS];

	public CustomLiquids() {
	}

	private CustomLiquids(List<CustomLiquid> saved) {
		for (CustomLiquid liquid : saved) {
			this.slots[liquid.slot()] = liquid;
		}
	}

	public static CustomLiquids of(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public static @Nullable CustomLiquid get(Level level, int slot) {
		if (slot < 0 || slot >= CustomLiquid.MAX_SLOTS) {
			return null;
		}
		if (level instanceof ServerLevel server) {
			return of(server.getServer()).slots[slot];
		}
		return client[slot];
	}

	public List<CustomLiquid> list() {
		List<CustomLiquid> out = new ArrayList<>();
		for (CustomLiquid liquid : this.slots) {
			if (liquid != null) {
				out.add(liquid);
			}
		}
		return out;
	}

	public @Nullable CustomLiquid at(int slot) {
		return slot >= 0 && slot < this.slots.length ? this.slots[slot] : null;
	}

	public int freeSlot() {
		for (int k = 0; k < this.slots.length; k++) {
			if (this.slots[k] == null) {
				return k;
			}
		}
		return -1;
	}

	public void put(CustomLiquid liquid) {
		this.slots[liquid.slot()] = liquid;
		this.setDirty();
	}

	public boolean remove(int slot) {
		if (this.at(slot) == null) {
			return false;
		}
		this.slots[slot] = null;
		this.setDirty();
		return true;
	}

	public static void sendTo(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, LiquidDefsPayload.TYPE)) {
			ServerPlayNetworking.send(player, new LiquidDefsPayload(of(player.level().getServer()).list()));
		}
	}

	public static void sendToAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sendTo(player);
		}
	}

	public static void setClient(List<CustomLiquid> liquids) {
		CustomLiquid[] next = new CustomLiquid[CustomLiquid.MAX_SLOTS];
		for (CustomLiquid liquid : liquids) {
			next[liquid.slot()] = liquid;
		}
		client = next;
		clientVersion++;
	}

	public static void clearClient() {
		setClient(List.of());
	}

	public static @Nullable CustomLiquid client(int slot) {
		return slot >= 0 && slot < CustomLiquid.MAX_SLOTS ? client[slot] : null;
	}

	public static List<CustomLiquid> clientList() {
		List<CustomLiquid> out = new ArrayList<>();
		for (CustomLiquid liquid : client) {
			if (liquid != null) {
				out.add(liquid);
			}
		}
		return out;
	}

	public static int clientVersion() {
		return clientVersion;
	}
}
