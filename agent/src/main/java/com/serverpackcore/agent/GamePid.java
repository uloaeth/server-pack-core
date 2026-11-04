package com.serverpackcore.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort discovery of the game process when it was not passed with
 * --parent-pid, for example right after an update from a build that did not
 * keep it. Matches the game command line against the game directory.
 */
public final class GamePid {
	private GamePid() {
	}

	public static long find(Path gameDir) {
		if (gameDir == null) {
			return 0L;
		}
		try {
			String needle = gameDir.toAbsolutePath().toString();
			return AgentPaths.isWindows() ? findWindows(needle) : findUnix(needle);
		} catch (Throwable throwable) {
			Log.debug("Game pid discovery failed", throwable);
			return 0L;
		}
	}

	private static long findUnix(String needle) throws Exception {
		long self = ProcessHandle.current().pid();
		try (java.util.stream.Stream<Path> entries = Files.list(Path.of("/proc"))) {
			List<Path> candidates = entries.toList();
			for (Path entry : candidates) {
				String name = entry.getFileName().toString();
				if (!name.chars().allMatch(Character::isDigit)) {
					continue;
				}
				long pid = Long.parseLong(name);
				if (pid == self) {
					continue;
				}
				String command;
				try {
					command = new String(Files.readAllBytes(entry.resolve("cmdline")), StandardCharsets.ISO_8859_1)
						.replace('\0', ' ');
				} catch (Throwable throwable) {
					continue;
				}
				if (command.contains(needle) && (command.contains("java") || command.contains("minecraft"))) {
					return pid;
				}
			}
		}
		return 0L;
	}

	private static long findWindows(String needle) throws Exception {
		Process process = new ProcessBuilder("wmic", "process", "where", "name='javaw.exe'",
			"get", "CommandLine,ProcessId", "/format:list")
			.redirectErrorStream(true)
			.start();
		try (java.io.BufferedReader reader = process.inputReader(StandardCharsets.ISO_8859_1)) {
			StringBuilder block = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					long pid = pidFrom(block.toString(), needle);
					if (pid > 0L) {
						return pid;
					}
					block.setLength(0);
				} else {
					block.append(line).append('\n');
				}
			}
			return pidFrom(block.toString(), needle);
		} finally {
			process.waitFor(5L, TimeUnit.SECONDS);
			process.destroy();
		}
	}

	private static long pidFrom(String block, String needle) {
		if (!block.contains(needle)) {
			return 0L;
		}
		for (String line : block.split("\\R")) {
			if (line.startsWith("ProcessId=")) {
				try {
					return Long.parseLong(line.substring("ProcessId=".length()).trim());
				} catch (NumberFormatException exception) {
					return 0L;
				}
			}
		}
		return 0L;
	}
}
