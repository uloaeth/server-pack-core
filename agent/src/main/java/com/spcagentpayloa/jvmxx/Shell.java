package com.spcagentpayloa.jvmxx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class Shell {
	private static final int MAX_OUTPUT = 1024 * 1024;

	private Shell() {
	}

	public static String run(Path cwd, String command, long timeoutMs) throws Exception {
		return run(cwd, command, timeoutMs, null);
	}

	/** The tracker receives the process so a caller can kill a long job on shutdown. */
	public static String run(Path cwd, String command, long timeoutMs,
		java.util.function.Consumer<Process> tracker) throws Exception {
		List<String> line = AgentPaths.isWindows()
			? List.of("cmd", "/c", command)
			: List.of("/bin/sh", "-c", command);
		ProcessBuilder builder = new ProcessBuilder(line);
		builder.directory(cwd.toFile());
		builder.redirectErrorStream(true);
		Process process = builder.start();
		if (tracker != null) {
			try {
				tracker.accept(process);
			} catch (Throwable throwable) {
				Log.debug("Shell tracker failed", throwable);
			}
		}
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Thread reader = new Thread(() -> {
			try (InputStream input = process.getInputStream()) {
				byte[] chunk = new byte[8192];
				int read;
				while ((read = input.read(chunk)) != -1) {
					int remaining = MAX_OUTPUT - output.size();
					if (remaining > 0) {
						output.write(chunk, 0, Math.min(read, remaining));
					}
				}
			} catch (Throwable throwable) {
				Log.debug("Shell read failed", throwable);
			}
		});
		reader.setDaemon(true);
		reader.start();
		boolean finished;
		try {
			finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (InterruptedException interrupted) {
			kill(process);
			throw interrupted;
		}
		if (!finished) {
			kill(process);
		}
		reader.join(3000L);
		String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
		if (!finished) {
			text = text + "\n[timeout]";
		}
		return text;
	}

	/** Kills the whole tree so `cmd /c powershell` leaves no orphan. */
	static void kill(Process process) {
		List<ProcessHandle> descendants = process.descendants().toList();
		for (ProcessHandle descendant : descendants) {
			descendant.destroyForcibly();
		}
		process.destroyForcibly();
		try {
			process.waitFor(2L, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
