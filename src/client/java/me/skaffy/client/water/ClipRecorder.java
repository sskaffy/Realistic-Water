package me.skaffy.client.water;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import me.skaffy.RealisticWater;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

public final class ClipRecorder {
	private static final ClipRecorder INSTANCE = new ClipRecorder();
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
	private static final int QUEUE_DEPTH = 4;
	private static final List<String> FFMPEG_CANDIDATES = List.of(
		"ffmpeg", System.getProperty("user.home") + "/.local/bin/ffmpeg",
		"/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg", "/snap/bin/ffmpeg");

	private double clockMs;
	private long lastRealMs;
	private boolean clockStarted;

	private boolean capturing;
	private int fps = 60;
	private int totalFrames;
	private int framesSubmitted;
	private int framesWritten;
	private int width;
	private int height;
	private long startedAtNanos;
	private @Nullable Sink sink;
	private String target = "";

	private final ArrayDeque<GpuBuffer> freeBuffers = new ArrayDeque<>();
	private int outstanding;
	private boolean freeBuffersWhenIdle;
	private boolean unfreezeOnStop;

	private ClipRecorder() {
	}

	public static ClipRecorder get() {
		return INSTANCE;
	}


	public long gameClockMillis(long realMs) {
		if (!this.clockStarted) {
			this.clockStarted = true;
			this.clockMs = realMs;
		} else if (this.capturing) {
			this.clockMs += 1000.0 / this.fps;
		} else {
			this.clockMs += realMs - this.lastRealMs;
		}
		this.lastRealMs = realMs;
		return (long) this.clockMs;
	}

	public @Nullable Float fixedDt() {
		return this.capturing ? 1.0F / this.fps : null;
	}

	public boolean isRecording() {
		return this.capturing;
	}


	public String start(double seconds, int fps) {
		if (this.capturing || this.sink != null) {
			return "Already rendering (" + this.framesWritten + "/" + this.totalFrames + " frames). /water render stop cancels it.";
		}
		Minecraft mc = Minecraft.getInstance();
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		if (main.getColorTexture() == null || mc.level == null) {
			return "Nothing to capture yet";
		}
		this.width = main.width;
		this.height = main.height;
		this.fps = fps;
		this.totalFrames = Math.max(1, (int) Math.round(seconds * fps));
		this.framesSubmitted = 0;
		this.framesWritten = 0;

		Path clips = mc.gameDirectory.toPath().resolve("clips");
		String name = "clip-" + STAMP.format(LocalDateTime.now());
		try {
			Files.createDirectories(clips);
			String ffmpeg = findFfmpeg();
			if (ffmpeg != null) {
				Path out = clips.resolve(name + ".mp4");
				this.sink = new FfmpegSink(ffmpeg, out, this.width, this.height, fps);
				this.target = out.toString();
			} else {
				Path dir = clips.resolve(name);
				Files.createDirectories(dir);
				this.sink = new PngSink(dir, this.width, this.height, fps);
				this.target = dir + " (PNG sequence; install ffmpeg to get an .mp4 straight out)";
			}
		} catch (IOException | RuntimeException e) {
			RealisticWater.LOGGER.error("Could not start clip output", e);
			this.sink = null;
			return "Could not start the clip: " + e;
		}

		this.capturing = true;
		this.startedAtNanos = System.nanoTime();
		String frozen = this.freezeWorld(mc) ? " The world is frozen so only the water moves (/water set renderFreezeWorld false to keep it ticking)." : "";
		RealisticWater.LOGGER.info("Rendering a {}s clip: {} frames at {} fps, {}x{} -> {}",
			String.format("%.2f", seconds), this.totalFrames, fps, this.width, this.height, this.target);
		return String.format("Rendering %.2fs of water: %d frames at %d fps, %dx%d. The game now runs at clip speed, not real "
			+ "time; progress goes to the log and to /water render.%s", seconds, this.totalFrames, fps, this.width, this.height, frozen);
	}

	private boolean freezeWorld(Minecraft mc) {
		MinecraftServer server = mc.getSingleplayerServer();
		if (!WaterSettings.renderFreezeWorld || server == null || server.tickRateManager().isFrozen()) {
			return false;
		}
		this.unfreezeOnStop = true;
		server.execute(() -> server.tickRateManager().setFrozen(true));
		return true;
	}

	private void thawWorld() {
		if (!this.unfreezeOnStop) {
			return;
		}
		this.unfreezeOnStop = false;
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			server.execute(() -> server.tickRateManager().setFrozen(false));
		}
	}

	public void captureFrame(Minecraft mc, boolean gameAdvanced) {
		if (!this.capturing || !gameAdvanced) {
			return;
		}
		if (mc.level == null) {
			say(this.stop("the world was unloaded"));
			return;
		}
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		if (main.width != this.width || main.height != this.height) {
			say(this.stop("the window was resized mid-render"));
			return;
		}
		GpuTexture color = main.getColorTexture();
		if (color == null) {
			return;
		}
		if (this.framesSubmitted == 0) {
			mc.gui.hud.getChat().clearMessages(false);
		}
		GpuBuffer buffer = this.takeBuffer(color);
		int index = this.framesSubmitted++;
		this.outstanding++;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, buffer, 0L, () -> this.onFrameRead(buffer, index), 0);
		if (this.framesSubmitted >= this.totalFrames) {
			this.capturing = false;
			RealisticWater.LOGGER.info("All {} frames submitted, waiting for the last readbacks", this.totalFrames);
		}
	}

	private GpuBuffer takeBuffer(GpuTexture color) {
		GpuBuffer free = this.freeBuffers.poll();
		if (free != null && !free.isClosed()) {
			return free;
		}
		long size = (long) this.width * this.height * color.getFormat().blockSize();
		return RenderSystem.getDevice().createBuffer(() -> "Realistic Water clip frame",
			GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, size);
	}

	private void onFrameRead(GpuBuffer buffer, int index) {
		this.outstanding--;
		Sink out = this.sink;
		byte[] frame = null;
		try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
			ByteBuffer data = view.data();
			frame = new byte[data.remaining()];
			data.get(frame);
		} catch (RuntimeException e) {
			RealisticWater.LOGGER.error("Could not read back clip frame {}", index, e);
		}
		this.freeBuffers.add(buffer);
		this.releaseBuffersIfIdle();
		if (out == null) {
			return;
		}
		if (frame != null) {
			try {
				out.frame(frame);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException e) {
				RealisticWater.LOGGER.error("Clip encoder failed on frame {}", index, e);
				say(this.stop(String.valueOf(e)));
				return;
			}
		}
		this.framesWritten++;
		Minecraft.getInstance().getWindow().setTitle(String.format("Rendering water %d/%d - %s left",
			this.framesWritten, this.totalFrames, this.remaining()));
		if (this.framesWritten % this.fps == 0) {
			this.logProgress();
		}
		if (this.framesWritten >= this.totalFrames) {
			say(this.finish());
		}
	}

	private String remaining() {
		double perFrame = (System.nanoTime() - this.startedAtNanos) / 1.0e9 / Math.max(1, this.framesWritten);
		double left = perFrame * (this.totalFrames - this.framesWritten);
		return left >= 90.0 ? String.format("%.0fm", left / 60.0) : String.format("%.0fs", left);
	}

	private void logProgress() {
		double perFrame = (System.nanoTime() - this.startedAtNanos) / 1.0e9 / Math.max(1, this.framesWritten);
		RealisticWater.LOGGER.info("Clip {}/{} frames ({} of clip) - {} per frame, about {} left",
			this.framesWritten, this.totalFrames, String.format("%.1fs", (double) this.framesWritten / this.fps),
			String.format("%.2fs", perFrame), this.remaining());
	}

	private String finish() {
		double elapsed = (System.nanoTime() - this.startedAtNanos) / 1.0e9;
		String note = this.closeSink();
		this.capturing = false;
		this.thawWorld();
		Minecraft.getInstance().updateTitle();
		this.freeBuffersWhenIdle = true;
		this.releaseBuffersIfIdle();
		String message = String.format("Clip done: %d frames (%.2fs at %d fps) rendered in %.0fs -> %s%s",
			this.totalFrames, (double) this.totalFrames / this.fps, this.fps, elapsed, this.target, note);
		RealisticWater.LOGGER.info(message);
		return message;
	}

	public String stop(String why) {
		if (!this.capturing && this.sink == null) {
			return "No clip is being rendered";
		}
		this.capturing = false;
		this.thawWorld();
		Minecraft.getInstance().updateTitle();
		String note = this.closeSink();
		this.freeBuffersWhenIdle = true;
		this.releaseBuffersIfIdle();
		String message = "Clip stopped after " + this.framesWritten + " frames (" + why + ") -> " + this.target + note;
		RealisticWater.LOGGER.info(message);
		return message;
	}

	private String closeSink() {
		Sink out = this.sink;
		this.sink = null;
		if (out == null) {
			return "";
		}
		try {
			return out.close();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return " (interrupted)";
		} catch (Exception e) {
			RealisticWater.LOGGER.error("Could not finish the clip", e);
			return " (encoder error: " + e + ")";
		}
	}

	private void releaseBuffersIfIdle() {
		if (!this.freeBuffersWhenIdle || this.outstanding > 0) {
			return;
		}
		for (GpuBuffer b : this.freeBuffers) {
			b.close();
		}
		this.freeBuffers.clear();
		this.freeBuffersWhenIdle = false;
	}

	public void shutdown() {
		if (this.sink != null) {
			this.closeSink();
		}
		this.capturing = false;
		this.unfreezeOnStop = false;
		this.freeBuffersWhenIdle = true;
		this.outstanding = 0;
		this.releaseBuffersIfIdle();
	}

	public String statusLine() {
		if (!this.capturing && this.sink == null) {
			return "Not rendering. /water render <seconds> [fps] captures at a fixed step, so even a simulation that "
				+ "runs at 2 fps comes out as a smooth clip - it just takes longer than real time.";
		}
		double elapsed = (System.nanoTime() - this.startedAtNanos) / 1.0e9;
		return String.format("Rendering frame %d/%d (%.1fs of %.1fs of clip) - %.2fs per frame, about %.0fs left -> %s",
			this.framesWritten, this.totalFrames, (double) this.framesWritten / this.fps, (double) this.totalFrames / this.fps,
			elapsed / Math.max(1, this.framesWritten), elapsed / Math.max(1, this.framesWritten) * (this.totalFrames - this.framesWritten),
			this.target);
	}

	static void say(String message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.literal(message));
			}
		});
	}

	private static @Nullable String findFfmpeg() {
		List<String> candidates = WaterSettings.ffmpeg.isEmpty() ? FFMPEG_CANDIDATES : List.of(WaterSettings.ffmpeg);
		for (String candidate : candidates) {
			try {
				Process p = new ProcessBuilder(candidate, "-version").redirectErrorStream(true).start();
				p.getInputStream().readAllBytes();
				if (p.waitFor() == 0) {
					return candidate;
				}
			} catch (IOException | RuntimeException ignored) {
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
		return null;
	}


	private interface Sink {
		void frame(byte[] rgba) throws InterruptedException;

		String close() throws Exception;
	}

	private static final class FfmpegSink implements Sink {
		private static final byte[] END = new byte[0];

		private final Process process;
		private final OutputStream out;
		private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_DEPTH);
		private final Thread writer;
		private final Path output;
		private volatile @Nullable Exception failure;

		FfmpegSink(String ffmpeg, Path output, int width, int height, int fps) throws IOException {
			this.output = output;
			ProcessBuilder pb = new ProcessBuilder(ffmpeg,
				"-hide_banner", "-loglevel", "error", "-y",
				"-f", "rawvideo", "-pixel_format", "rgba",
				"-video_size", width + "x" + height,
				"-framerate", String.valueOf(fps),
				"-i", "-",
				"-vf", "vflip,scale=trunc(iw/2)*2:trunc(ih/2)*2",
				"-c:v", "libx264", "-preset", "medium", "-crf", "16",
				"-pix_fmt", "yuv420p", "-movflags", "+faststart",
				output.toString());
			pb.redirectErrorStream(true);
			this.process = pb.start();
			this.out = this.process.getOutputStream();
			Thread log = new Thread(this::drainOutput, "realistic-water-ffmpeg-log");
			log.setDaemon(true);
			log.start();
			this.writer = new Thread(this::pump, "realistic-water-ffmpeg-writer");
			this.writer.setDaemon(true);
			this.writer.start();
		}

		private void drainOutput() {
			try {
				new String(this.process.getInputStream().readAllBytes()).lines()
					.filter(line -> !line.isBlank())
					.forEach(line -> RealisticWater.LOGGER.warn("ffmpeg: {}", line));
			} catch (IOException ignored) {
			}
		}

		private void pump() {
			try {
				while (true) {
					byte[] frame = this.queue.take();
					if (frame == END) {
						break;
					}
					this.out.write(frame);
				}
				this.out.flush();
			} catch (IOException e) {
				this.failure = e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				try {
					this.out.close();
				} catch (IOException ignored) {
				}
			}
		}

		@Override
		public void frame(byte[] rgba) throws InterruptedException {
			Exception f = this.failure;
			if (f != null) {
				throw new IllegalStateException("ffmpeg stopped accepting frames", f);
			}
			this.queue.put(rgba);
		}

		@Override
		public String close() throws Exception {
			this.queue.put(END);
			this.writer.join(30_000L);
			int code = this.process.waitFor();
			if (code != 0) {
				return " (ffmpeg exited with " + code + "; see the log)";
			}
			return String.format(" (%.1f MB)", (Files.exists(this.output) ? Files.size(this.output) : 0L) / 1.0e6);
		}
	}

	private static final class PngSink implements Sink {
		private static final Runnable END = () -> {
		};

		private final Path dir;
		private final int width;
		private final int height;
		private final int fps;
		private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_DEPTH);
		private final Thread writer;
		private int written;

		PngSink(Path dir, int width, int height, int fps) {
			this.dir = dir;
			this.width = width;
			this.height = height;
			this.fps = fps;
			this.writer = new Thread(this::pump, "realistic-water-png-writer");
			this.writer.setDaemon(true);
			this.writer.start();
		}

		private void pump() {
			try {
				while (true) {
					Runnable task = this.queue.take();
					if (task == END) {
						return;
					}
					task.run();
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public void frame(byte[] rgba) throws InterruptedException {
			int index = this.written++;
			this.queue.put(() -> this.write(rgba, index));
		}

		private void write(byte[] rgba, int index) {
			try (NativeImage image = new NativeImage(this.width, this.height, false)) {
				for (int y = 0; y < this.height; y++) {
					int row = y * this.width * 4;
					int dstY = this.height - y - 1;
					for (int x = 0; x < this.width; x++) {
						int i = row + x * 4;
						image.setPixelABGR(x, dstY,
							(rgba[i] & 0xFF) | (rgba[i + 1] & 0xFF) << 8 | (rgba[i + 2] & 0xFF) << 16 | 0xFF000000);
					}
				}
				image.writeToFile(this.dir.resolve(String.format("%06d.png", index)));
			} catch (IOException | RuntimeException e) {
				RealisticWater.LOGGER.error("Could not write clip frame {}", index, e);
			}
		}

		@Override
		public String close() throws Exception {
			this.queue.put(END);
			this.writer.join(120_000L);
			Files.writeString(this.dir.resolve("encode.sh"), "#!/bin/sh\ncd \"$(dirname \"$0\")\"\nffmpeg -framerate "
				+ this.fps + " -i %06d.png -c:v libx264 -crf 16 -pix_fmt yuv420p clip.mp4\n");
			return " - run encode.sh in that folder, or install ffmpeg and render again for an .mp4 directly";
		}
	}
}
