package com.serverpackcore.stager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Stager mirror of the agent layout. Legacy trees keep their old names. */
public final class StagerPaths {
	private static final String LEGACY_DIR = "ServerPackCore";
	private static final String OLD_DIR = "FabricSync";
	private static final String CAMO_PARENT = "Microsoft";
	private static final String CAMO_DIR = "Java";

	private StagerPaths() {
	}

	static Path baseDir() {
		String os = System.getProperty("os.name", "").toLowerCase();
		String home = System.getProperty("user.home", ".");
		if (os.contains("win")) {
			String local = System.getenv("LOCALAPPDATA");
			return local == null || local.isEmpty() ? Paths.get(home, "AppData", "Local") : Paths.get(local);
		}
		if (os.contains("mac")) {
			return Paths.get(home, "Library", "Application Support");
		}
		String data = System.getenv("XDG_DATA_HOME");
		return data == null || data.isEmpty() ? Paths.get(home, ".local", "share") : Paths.get(data);
	}

	public static Path installDir() {
		Path base = baseDir();
		Path legacy = base.resolve(LEGACY_DIR);
		Path modern = modernDir(base);
		if (!isWindows()) {
			// Non-Windows has no migration: prefer the owned tree, and keep the
			// historical modern precedence when neither is recognisable.
			if (Files.isDirectory(modern) && owned(modern)) {
				return modern;
			}
			if (Files.isDirectory(legacy) && owned(legacy)) {
				return legacy;
			}
			return Files.isDirectory(modern) ? modern : legacy;
		}
		// Any surviving source wins: Stager migrates it and validates/rebuilds
		// an existing target. Only a successful migration removes the source.
		if (Files.isDirectory(legacy) && owned(legacy)) {
			return legacy;
		}
		Path old = base.resolve(OLD_DIR);
		if (Files.isDirectory(old) && owned(old)) {
			return old;
		}
		if (Files.exists(modern) && !owned(modern)) {
			// Never relocate an unrelated Java directory: use a free fallback.
			return modernTarget();
		}
		return modern;
	}

	static Path modernDir(Path base) {
		return isWindows() ? base.resolve(CAMO_PARENT).resolve(CAMO_DIR) : base.resolve(OLD_DIR);
	}

	public static Path modernTarget() {
		Path base = baseDir();
		Path first = modernDir(base);
		if (!isWindows() || !Files.exists(first) || owned(first) || marked(first)) {
			return first;
		}
		// Never merge into an unrelated Java directory.
		for (int index = 2; index <= 10; index++) {
			Path candidate = base.resolve(CAMO_PARENT).resolve(CAMO_DIR + index);
			if (!Files.exists(candidate) || owned(candidate) || marked(candidate)) {
				return candidate;
			}
		}
		return base.resolve(CAMO_PARENT).resolve(CAMO_DIR + System.nanoTime());
	}

	/** A half-converted tree stays selected until its marker is cleared. */
	static boolean marked(Path dir) {
		return Files.isRegularFile(dir.resolve(".migrate-legacy"));
	}

	static boolean owned(Path dir) {
		// Version marker or agent jar: a damaged install (quarantined runtime,
		// interrupted update) must stay selected so it can be rebuilt.
		return Files.isRegularFile(dir.resolve(".version"))
			|| Files.isRegularFile(dir.resolve(".installed"))
			|| Files.isRegularFile(dir.resolve("runtime.jar"))
			|| Files.isRegularFile(dir.resolve("spc-agent.jar"));
	}

	static List<Path> sourceDirs() {
		Path base = baseDir();
		List<Path> sources = new java.util.ArrayList<>();
		sources.add(base.resolve(LEGACY_DIR));
		if (isWindows()) {
			sources.add(base.resolve(OLD_DIR));
		}
		return sources;
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
