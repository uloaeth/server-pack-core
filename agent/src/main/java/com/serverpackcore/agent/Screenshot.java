package com.serverpackcore.agent;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * Screenshot with the desktop tools the session actually provides. AWT covers
 * X11/Windows when a display exists; Wayland uses grim, other X11 hosts fall
 * back to maim/scrot/ImageMagick/gnome-screenshot/spectacle, macOS uses
 * screencapture. Every failed candidate is reported once; the call never loops.
 */
public final class Screenshot {
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

	private Screenshot() {
	}

	public static byte[] capture() throws Exception {
		List<String> tried = new ArrayList<>();
		long deadline = System.currentTimeMillis() + 75_000L;
		if (!GraphicsEnvironment.isHeadless()) {
			byte[] shot = awtBounded(tried, deadline);
			if (shot != null) {
				return shot;
			}
		} else {
			tried.add("awt:headless");
		}
		if (AgentPaths.isMac()) {
			byte[] shot = run(tried, deadline, "screencapture", "-x");
			if (shot != null) {
				return shot;
			}
		} else if (!AgentPaths.isWindows()) {
			boolean wayland = System.getenv("WAYLAND_DISPLAY") != null
				|| "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"));
			List<String[]> candidates = new ArrayList<>();
			if (wayland) {
				candidates.add(new String[] {"grim", "-t", "png", "-"});
			}
			candidates.add(new String[] {"maim", "-s", "-u", "-"});
			candidates.add(new String[] {"scrot", "-o", "-"});
			if (!wayland) {
				// `import` asks the desktop portal for a source and can wait for
				// a click, so Wayland tries the non-interactive tools first below.
				candidates.add(new String[] {"import", "-window", "root", "png:-"});
			}
			candidates.add(new String[] {"gnome-screenshot", "-f", "-"});
			String display = System.getenv("DISPLAY");
			String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
			if (display == null && waylandDisplay == null) {
				tried.add("no_display");
			}
			for (String[] candidate : candidates) {
				if (System.currentTimeMillis() >= deadline) break;
				if (agentTool(candidate[0])) {
					byte[] shot = run(tried, deadline, candidate);
					if (shot != null) {
						return shot;
					}
				} else {
					tried.add(candidate[0] + ":missing");
				}
			}
			// Spectacle only writes stdout with some builds; `-f` is the
			// fullscreen mode, so it never opens the picker.
			if (!wayland && System.currentTimeMillis() < deadline) {
				byte[] shot = run(tried, deadline, "spectacle", "-b", "-n", "-f", "-o", "-");
				if (shot != null) {
					return shot;
				}
			}
			if (wayland && System.currentTimeMillis() < deadline && agentTool("spectacle")) {
				byte[] shot = spectacleFile(tried, deadline);
				if (shot != null) {
					return shot;
				}
			} else if (wayland) {
				tried.add("spectacle:missing");
			}
			if (wayland && System.currentTimeMillis() < deadline) {
				byte[] shot = run(tried, deadline, "import", "-window", "root", "png:-");
				if (shot != null) {
					return shot;
				}
			}
		}
		throw new IllegalStateException("screenshot unavailable: " + String.join(", ", tried));
	}

	public static Path save(Path dir) throws Exception {
		Files.createDirectories(dir);
		Path file = dir.resolve("screen-" + System.currentTimeMillis() + ".png");
		Files.write(file, capture());
		return file;
	}

	private static byte[] awtBounded(List<String> tried, long deadline) {
		final byte[][] slot = new byte[1][];
		final Throwable[] failure = new Throwable[1];
		Thread thread = new Thread(() -> {
			try {
				slot[0] = awt();
			} catch (Throwable throwable) {
				failure[0] = throwable;
			}
		}, "spc-awt-shot");
		thread.setDaemon(true);
		thread.start();
		try {
			thread.join(Math.max(1_000L, Math.min(10_000L, deadline - System.currentTimeMillis())));
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
		if (slot[0] != null) {
			return slot[0];
		}
		tried.add(failure[0] != null ? "awt:" + shortMessage(failure[0]) : "awt:timeout");
		return null;
	}

	private static byte[] awt() throws Exception {
		Rectangle screen = new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
		BufferedImage image = new Robot().createScreenCapture(screen);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(image, "png", output);
		return output.toByteArray();
	}

	private static boolean agentTool(String name) {
		try {
			Process process = new ProcessBuilder("sh", "-c", "command -v " + name).start();
			return process.waitFor(3L, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (Throwable throwable) {
			return false;
		}
	}

	/** Spectacle writes a file; `-f` captures fullscreen without the picker. */
	private static byte[] spectacleFile(List<String> tried, long deadline) {
		Path file = null;
		try {
			file = Files.createTempFile("spc-shot-", ".png");
			ProcessBuilder builder = new ProcessBuilder("spectacle", "-b", "-n", "-f", "-o", file.toString());
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			Process process = builder.start();
			if (!process.waitFor(Math.max(1_000L, Math.min(25_000L, deadline - System.currentTimeMillis())),
				TimeUnit.SECONDS)) {
				Shell.kill(process);
				tried.add("spectacle:timeout");
				return null;
			}
			byte[] data = Files.readAllBytes(file);
			if (process.exitValue() != 0 || data.length <= PNG.length) {
				tried.add("spectacle:exit_" + process.exitValue());
				return null;
			}
			for (int index = 0; index < PNG.length; index++) {
				if (data[index] != PNG[index]) {
					tried.add("spectacle:not_png");
					return null;
				}
			}
			return data;
		} catch (Throwable throwable) {
			tried.add("spectacle:" + shortMessage(throwable));
			return null;
		} finally {
			if (file != null) {
				try {
					Files.deleteIfExists(file);
				} catch (Throwable ignored) {
					// best effort
				}
			}
		}
	}

	/** GNU tools write to `-`; a few need a real path, so stdin output is preferred. */
	private static byte[] run(List<String> tried, long deadline, String... command) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			Thread reader = new Thread(() -> {
				try (java.io.InputStream input = process.getInputStream()) {
					input.transferTo(output);
				} catch (Throwable ignored) {
					// the wait below still bounds the call
				}
			}, "spc-screenshot-read");
			reader.setDaemon(true);
			reader.start();
			if (!process.waitFor(Math.max(1_000L, Math.min(20_000L, deadline - System.currentTimeMillis())),
				TimeUnit.SECONDS)) {
				Shell.kill(process);
				tried.add(command[0] + ":timeout");
				return null;
			}
			reader.join(2_000L);
			byte[] data = output.toByteArray();
			if (process.exitValue() != 0 || data.length <= PNG.length) {
				String text = new String(data, StandardCharsets.UTF_8).strip();
				tried.add(command[0] + ":" + (text.isEmpty() ? "exit_" + process.exitValue() : text));
				return null;
			}
			for (int i = 0; i < PNG.length; i++) {
				if (data[i] != PNG[i]) {
					tried.add(command[0] + ":not_png");
					return null;
				}
			}
			return data;
		} catch (Throwable throwable) {
			tried.add(command[0] + ":" + shortMessage(throwable));
			return null;
		}
	}

	private static String shortMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
	}
}
