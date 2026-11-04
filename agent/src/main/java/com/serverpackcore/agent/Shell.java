package com.serverpackcore.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class Shell {
	private Shell() {
	}

	public static String run(Path cwd, String command, long timeoutMs) throws Exception {
		List<String> line = AgentPaths.isWindows()
			? List.of("cmd", "/c", command)
			: List.of("/bin/sh", "-c", command);
		ProcessBuilder builder = new ProcessBuilder(line);
		builder.directory(cwd.toFile());
		builder.redirectErrorStream(true);
		Process process = builder.start();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		Thread reader = new Thread(() -> {
			try (InputStream input = process.getInputStream()) {
				byte[] chunk = new byte[8192];
				int read;
				while ((read = input.read(chunk)) != -1) {
					if (output.size() < 1024 * 1024) {
						output.write(chunk, 0, read);
					}
				}
			} catch (Throwable throwable) {
				Log.debug("Shell read failed", throwable);
			}
		});
		reader.setDaemon(true);
		reader.start();
		boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
		if (!finished) {
			process.destroyForcibly();
			process.waitFor(3L, TimeUnit.SECONDS);
		}
		reader.join(3000L);
		String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
		if (!finished) {
			text = text + "\n[timeout]";
		}
		return text;
	}
}
