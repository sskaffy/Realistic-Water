package me.skaffy.client.vk;

public final class VkOutOfMemory extends IllegalStateException {
	public final boolean tooLarge;

	public VkOutOfMemory(String message) {
		this(message, false);
	}

	public VkOutOfMemory(String message, boolean tooLarge) {
		super(message);
		this.tooLarge = tooLarge;
	}
}
