package me.skaffy.block;

import net.minecraft.util.StringRepresentable;

public enum SnowOrigin implements StringRepresentable {
	PLACED("placed"),
	LAYER("layer"),
	BLOCK("block");

	private final String name;

	SnowOrigin(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return this.name;
	}
}
