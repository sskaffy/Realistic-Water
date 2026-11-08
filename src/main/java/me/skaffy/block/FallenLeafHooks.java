package me.skaffy.block;

import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

public final class FallenLeafHooks {
	public static volatile @Nullable Consumer<LeafPileBlockEntity> client;

	private FallenLeafHooks() {
	}
}
