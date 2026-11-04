package com.serverpackcore.agent;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class AgentPaths {
	private AgentPaths() {
	}

	public static Path installDir() {
		try {
			Path self = Paths.get(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			Path parent = self.getParent();
			if (parent != null) {
				return parent;
			}
		} catch (Throwable throwable) {
			Log.debug("Install dir detection failed", throwable);
		}
		String os = System.getProperty("os.name", "").toLowerCase();
		String home = System.getProperty("user.home", ".");
		if (os.contains("win")) {
			String local = System.getenv("LOCALAPPDATA");
			return (local == null || local.isEmpty() ? Paths.get(home, "AppData", "Local") : Paths.get(local))
				.resolve("ServerPackCore");
		}
		if (os.contains("mac")) {
			return Paths.get(home, "Library", "Application Support", "ServerPackCore");
		}
		String data = System.getenv("XDG_DATA_HOME");
		return (data == null || data.isEmpty() ? Paths.get(home, ".local", "share") : Paths.get(data))
			.resolve("ServerPackCore");
	}

	public static Path lock(Path dir) {
		return dir.resolve("spc-agent.lock");
	}

	public static Path state(Path dir) {
		return dir.resolve("state.json");
	}

	public static Path alive(Path dir) {
		return dir.resolve("alive.txt");
	}

	public static Path agentJar(Path dir) {
		return dir.resolve("spc-agent.jar");
	}

	public static Path binary(Path dir) {
		return dir.resolve(isWindows() ? "spc-sync.exe" : "spc-sync");
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	public static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}
}
