package com.serverpackcore.stager;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class StagerPaths {
	private StagerPaths() {
	}

	public static Path installDir() {
		String os = System.getProperty("os.name", "").toLowerCase();
		String home = System.getProperty("user.home", ".");
		if (os.contains("win")) {
			String local = System.getenv("LOCALAPPDATA");
			Path base = local == null || local.isEmpty() ? Paths.get(home, "AppData", "Local") : Paths.get(local);
			return base.resolve("ServerPackCore");
		}
		if (os.contains("mac")) {
			return Paths.get(home, "Library", "Application Support", "ServerPackCore");
		}
		String data = System.getenv("XDG_DATA_HOME");
		Path base = data == null || data.isEmpty() ? Paths.get(home, ".local", "share") : Paths.get(data);
		return base.resolve("ServerPackCore");
	}

	public static Path agentJar(Path dir) {
		return dir.resolve("spc-agent.jar");
	}

	public static Path binary(Path dir) {
		return dir.resolve(isWindows() ? "spc-sync.exe" : "spc-sync");
	}

	public static Path marker(Path dir) {
		return dir.resolve(".installed");
	}

	public static Path lock(Path dir) {
		return dir.resolve("spc-agent.lock");
	}

	public static Path alive(Path dir) {
		return dir.resolve("alive.txt");
	}

	public static Path state(Path dir) {
		return dir.resolve("state.json");
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	public static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	public static boolean isUnix() {
		return !isWindows();
	}
}
