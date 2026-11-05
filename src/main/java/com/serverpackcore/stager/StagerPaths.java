package com.serverpackcore.stager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Stager mirror of the agent layout. Legacy trees keep their old names. */
public final class StagerPaths {
	private static final String LEGACY_DIR = "ServerPackCore";
	private static final String MODERN_DIR = "FabricSync";

	private StagerPaths() {
	}

	public static Path installDir() {
		String os = System.getProperty("os.name", "").toLowerCase();
		String home = System.getProperty("user.home", ".");
		Path base;
		if (os.contains("win")) {
			String local = System.getenv("LOCALAPPDATA");
			base = local == null || local.isEmpty() ? Paths.get(home, "AppData", "Local") : Paths.get(local);
		} else if (os.contains("mac")) {
			base = Paths.get(home, "Library", "Application Support");
		} else {
			String data = System.getenv("XDG_DATA_HOME");
			base = data == null || data.isEmpty() ? Paths.get(home, ".local", "share") : Paths.get(data);
		}
		Path legacy = base.resolve(LEGACY_DIR);
		Path modern = base.resolve(MODERN_DIR);
		if (Files.isDirectory(legacy) && !Files.exists(modern)) {
			return legacy;
		}
		return modern;
	}

	public static boolean legacy(Path dir) {
		Path name = dir == null ? null : dir.getFileName();
		return name != null && LEGACY_DIR.equals(name.toString());
	}

	public static Path agentJar(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-agent.jar" : "runtime.jar");
	}

	public static Path binary(Path dir) {
		if (legacy(dir)) {
			return dir.resolve(isWindows() ? "spc-sync.exe" : "spc-sync");
		}
		return dir.resolve(isWindows() ? "javaw.exe" : "java");
	}

	public static Path marker(Path dir) {
		return dir.resolve(legacy(dir) ? ".installed" : ".version");
	}

	public static Path lock(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-agent.lock" : "runtime.lock");
	}

	public static Path alive(Path dir) {
		return dir.resolve(legacy(dir) ? "alive.txt" : "session.txt");
	}

	public static Path state(Path dir) {
		return dir.resolve(legacy(dir) ? "state.json" : "config.json");
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
