package me.skaffy.client.water;

public enum Material {
	WATER,
	SAND;

	float shaderId() {
		return this == SAND ? 1.0F : 0.0F;
	}
}
