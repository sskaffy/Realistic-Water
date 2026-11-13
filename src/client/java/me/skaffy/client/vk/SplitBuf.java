package me.skaffy.client.vk;

import org.lwjgl.vulkan.VkCommandBuffer;

public final class SplitBuf implements AutoCloseable {
	public static final int WHOLE = Integer.MAX_VALUE;

	public final VkBuf lo;
	public final VkBuf hi;
	public final int split;
	public final long count;
	public final int elementBytes;

	private SplitBuf(VkBuf lo, VkBuf hi, int split, long count, int elementBytes) {
		this.lo = lo;
		this.hi = hi;
		this.split = split;
		this.count = count;
		this.elementBytes = elementBytes;
	}

	public static SplitBuf device(long count, int elementBytes) {
		long perPart = perPart(elementBytes);
		if (count > Integer.MAX_VALUE) {
			throw new VkOutOfMemory("an array of " + count + " elements is more than a shader can index", true);
		}
		if (count <= perPart) {
			VkBuf lo = VkBuf.device(Math.max(count, 1L) * elementBytes, 0);
			try {
				return new SplitBuf(lo, VkBuf.device(16L, 0), WHOLE, count, elementBytes);
			} catch (RuntimeException e) {
				lo.close();
				throw e;
			}
		}
		if (count - perPart > perPart) {
			throw new VkOutOfMemory("an array of " + count * elementBytes / (1024L * 1024L) + " MiB is more than twice what this graphics driver allows in one"
				+ " buffer (" + Vk.maxBufferBytes() / (1024L * 1024L) + " MiB)", true);
		}
		VkBuf lo = VkBuf.device(perPart * elementBytes, 0);
		try {
			return new SplitBuf(lo, VkBuf.device((count - perPart) * elementBytes, 0), (int) perPart, count, elementBytes);
		} catch (RuntimeException e) {
			lo.close();
			throw e;
		}
	}

	public static int perPart(int elementBytes) {
		return (int) Math.min((Vk.maxBufferBytes() / elementBytes) & ~63L, Integer.MAX_VALUE & ~63);
	}

	public void copyPrefixTo(VkCommandBuffer cb, SplitBuf dst, long elements) {
		if (dst.elementBytes != this.elementBytes || elements > this.count || elements > dst.count) {
			throw new IllegalArgumentException("split array copy out of range");
		}
		long per = perPart(this.elementBytes);
		long first = Math.min(elements, per);
		if (first > 0) {
			this.lo.copyTo(cb, dst.lo, 0L, 0L, first * this.elementBytes);
		}
		if (elements > per) {
			this.hi.copyTo(cb, dst.hi, 0L, 0L, (elements - per) * this.elementBytes);
		}
	}

	public boolean isSplit() {
		return this.split != WHOLE;
	}

	public Desc part0() {
		return Desc.ssbo(this.lo);
	}

	public Desc part1() {
		return Desc.ssbo(this.hi);
	}

	public void fill(VkCommandBuffer cb, int value) {
		this.lo.fill(cb, value);
		this.hi.fill(cb, value);
	}

	public void copyTo(VkCommandBuffer cb, SplitBuf dst) {
		if (dst.count != this.count || dst.split != this.split || dst.elementBytes != this.elementBytes) {
			throw new IllegalArgumentException("split arrays of different shapes");
		}
		this.lo.copyTo(cb, dst.lo, 0L, 0L, this.lo.size);
		if (this.isSplit()) {
			this.hi.copyTo(cb, dst.hi, 0L, 0L, this.hi.size);
		}
	}

	@Override
	public void close() {
		this.lo.close();
		this.hi.close();
	}
}
