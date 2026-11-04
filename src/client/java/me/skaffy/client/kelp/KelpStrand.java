package me.skaffy.client.kelp;

final class KelpStrand {
	static final int SEGMENTS = 4;
	static final float SEGMENT = 1.0F / SEGMENTS;

	final long root;
	final int rootX;
	final int rootY;
	final int rootZ;
	final int blocks;
	final int count;
	final float[] x;
	final float[] y;
	final float[] z;
	final float[] px;
	final float[] py;
	final float[] pz;
	final float[] rx;
	final float[] ry;
	final float[] rz;
	final boolean[] pinned;
	boolean awake = true;
	int calmTicks;
	float minX;
	float minY;
	float minZ;
	float maxX;
	float maxY;
	float maxZ;

	KelpStrand(long root, int rootX, int rootY, int rootZ, int blocks) {
		this.root = root;
		this.rootX = rootX;
		this.rootY = rootY;
		this.rootZ = rootZ;
		this.blocks = blocks;
		this.count = blocks * SEGMENTS + 1;
		this.x = new float[this.count];
		this.y = new float[this.count];
		this.z = new float[this.count];
		this.px = new float[this.count];
		this.py = new float[this.count];
		this.pz = new float[this.count];
		this.rx = new float[this.count];
		this.ry = new float[this.count];
		this.rz = new float[this.count];
		this.pinned = new boolean[this.count];
		for (int i = 0; i < this.count; i++) {
			this.x[i] = 0.5F;
			this.y[i] = i * SEGMENT;
			this.z[i] = 0.5F;
		}
		this.pinned[0] = true;
		this.settle();
		this.updateBounds();
	}

	void copyFrom(KelpStrand old) {
		int n = Math.min(this.count, old.count);
		for (int i = 1; i < n; i++) {
			this.x[i] = old.x[i];
			this.y[i] = old.y[i];
			this.z[i] = old.z[i];
		}
		for (int i = n; i < this.count; i++) {
			this.x[i] = this.x[i - 1];
			this.y[i] = this.y[i - 1] + SEGMENT;
			this.z[i] = this.z[i - 1];
		}
		this.settle();
		this.updateBounds();
	}

	void settle() {
		System.arraycopy(this.x, 0, this.px, 0, this.count);
		System.arraycopy(this.y, 0, this.py, 0, this.count);
		System.arraycopy(this.z, 0, this.pz, 0, this.count);
		this.snapshot();
	}

	void snapshot() {
		System.arraycopy(this.x, 0, this.rx, 0, this.count);
		System.arraycopy(this.y, 0, this.ry, 0, this.count);
		System.arraycopy(this.z, 0, this.rz, 0, this.count);
	}

	void updateBounds() {
		float x0 = Float.MAX_VALUE;
		float y0 = Float.MAX_VALUE;
		float z0 = Float.MAX_VALUE;
		float x1 = -Float.MAX_VALUE;
		float y1 = -Float.MAX_VALUE;
		float z1 = -Float.MAX_VALUE;
		for (int i = 0; i < this.count; i++) {
			x0 = Math.min(x0, this.x[i]);
			y0 = Math.min(y0, this.y[i]);
			z0 = Math.min(z0, this.z[i]);
			x1 = Math.max(x1, this.x[i]);
			y1 = Math.max(y1, this.y[i]);
			z1 = Math.max(z1, this.z[i]);
		}
		this.minX = x0 + this.rootX;
		this.minY = y0 + this.rootY;
		this.minZ = z0 + this.rootZ;
		this.maxX = x1 + this.rootX;
		this.maxY = y1 + this.rootY;
		this.maxZ = z1 + this.rootZ;
	}

	boolean overlaps(double x0, double y0, double z0, double x1, double y1, double z1) {
		return this.maxX >= x0 && this.minX <= x1 && this.maxY >= y0 && this.minY <= y1 && this.maxZ >= z0 && this.minZ <= z1;
	}

	static float reach(int i) {
		return i * SEGMENT;
	}
}
