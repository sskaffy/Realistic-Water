package me.skaffy.ocean;

import net.minecraft.util.StringRepresentable;

public enum OceanOrigin implements StringRepresentable {
	NONE("none"),
	AIR("air"),
	WATER("water");

	private final String name;

	OceanOrigin(String name) {
		this.name = name;
	}

	@Override
	public String getSerializedName() {
		return this.name;
	}
}
