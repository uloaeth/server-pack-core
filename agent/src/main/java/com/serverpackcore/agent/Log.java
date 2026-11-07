package com.serverpackcore.agent;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Console output only with -Dspc.debug; the install log always keeps failures. */
public final class Log {
	private static final boolean DEBUG = Boolean.getBoolean("spc.debug");
	private static final long MAX_BYTES = 256L * 1024L;
	private static final Object LOCK = new Object();

	private static volatile Path file;

	private Log() {
	}

	/** Called once the install directory exists; before this only stderr is used. */
	public static void init(Path dir) {
		try {
			Files.createDirectories(dir.resolve("logs"));
			AgentPaths.conceal(dir.resolve("logs"));
			file = dir.resolve("logs").resolve("agent.log");
			try {
				Files.setPosixFilePermissions(file,
					java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (Throwable ignored) {
				// Windows or not created yet
			}
		} catch (Throwable throwable) {
			file = null;
		}
	}

	public static void debug(String message) {
		if (DEBUG) {
			System.err.println("[spc] " + message);
		}
		write(message);
	}

	public static void debug(String message, Throwable throwable) {
		String text = message;
		if (throwable != null) {
			StringWriter trace = new StringWriter();
			throwable.printStackTrace(new PrintWriter(trace));
			text += "\n" + trace;
		}
		if (DEBUG) {
			System.err.println("[spc] " + message);
			if (throwable != null) {
				throwable.printStackTrace(System.err);
			}
		}
		write(text);
	}

	private static void write(String text) {
		Path target = file;
		if (target == null) {
			return;
		}
		synchronized (LOCK) {
			try {
				rotate(target);
				Files.writeString(target, Instant.now() + " " + text + "\n", StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				try {
					Files.setPosixFilePermissions(target,
						java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
				} catch (Throwable ignored) {
					// Windows
				}
			} catch (Throwable ignored) {
				// Logging must never break the agent.
			}
		}
	}

	private static void rotate(Path target) {
		try {
			if (Files.isRegularFile(target) && Files.size(target) > MAX_BYTES) {
				Files.move(target, target.resolveSibling(target.getFileName() + ".1"),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Throwable ignored) {
			// Keep appending to the current file.
		}
	}
}
