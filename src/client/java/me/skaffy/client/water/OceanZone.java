package me.skaffy.client.water;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

final class OceanZone {
	int x0;
	int z0;
	int x1;
	int z1;
	int floorY;
	int topY;
	final int seaY;
	final LongOpenHashSet filled = new LongOpenHashSet();
	boolean solidsStale = true;
	float quietSeconds;

	OceanZone(int seaY, int floorY, int topY) {
		this.seaY = seaY;
		this.floorY = floorY;
		this.topY = topY;
	}

	boolean walls(int x, int y, int z) {
		return y < this.floorY || x < this.x0 || x >= this.x1 || z < this.z0 || z >= this.z1;
	}

	boolean contains(int x, int z) {
		return x >= this.x0 && x < this.x1 && z >= this.z0 && z < this.z1;
	}

	boolean containsPoint(double x, double y, double z) {
		return x >= this.x0 && x < this.x1 && z >= this.z0 && z < this.z1 && y >= this.floorY && y < this.topY + 1;
	}

	int[] cellBox(int res) {
		int m = WALL_CELLS;
		return new int[]{this.x0 * res - m, this.floorY * res - m, this.z0 * res - m, this.x1 * res + m, (this.topY + 1) * res + m, this.z1 * res + m};
	}

	static final int WALL_CELLS = 3;

	int[] blockBox() {
		return new int[]{this.x0, this.floorY, this.z0, this.x1, this.topY + 1, this.z1};
	}
}
