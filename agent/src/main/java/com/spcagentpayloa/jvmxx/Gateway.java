package com.spcagentpayloa.jvmxx;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class Gateway {
	private static final int MAX_BUFFER = 4 * 1024 * 1024;
	private static final int OP_DISPATCH = 0;
	private static final int OP_HEARTBEAT = 1;
	private static final int OP_IDENTIFY = 2;
	private static final int OP_RESUME = 6;
	private static final int OP_RECONNECT = 7;
	private static final int OP_INVALID_SESSION = 9;
	private static final int OP_HELLO = 10;
	private static final int OP_HEARTBEAT_ACK = 11;

	private final Poller poller;
	private final ExecutorService httpExecutor;
	private final HttpClient http;
	private final ScheduledExecutorService scheduler;
	private final Random random = new Random();
	private final Inflater inflater = new Inflater();
	private final Object inflateLock = new Object();
	private final ByteArrayOutputStream inflated = new ByteArrayOutputStream();
	private final AtomicBoolean awaitingAck = new AtomicBoolean();
	private final AtomicBoolean stopped = new AtomicBoolean();

	private volatile boolean running = true;
	private volatile boolean healthy;
	private volatile boolean contentIntent = true;
	private volatile int intents = DiscordConfig.INTENT_GUILDS
		| DiscordConfig.INTENT_GUILD_MESSAGES
		| DiscordConfig.INTENT_MESSAGE_CONTENT;
	private volatile WebSocket socket;
	private volatile Thread thread;
	private volatile CountDownLatch closed;
	private volatile String gatewayUrl = "";
	private volatile String resumeUrl = "";
	private volatile String sessionId = "";
	private volatile long lastSeq = -1L;
	private volatile long heartbeatInterval;
	private volatile long lastAckRtt = -1L;
	private volatile long lastHeartbeatSent;
	private volatile int reconnects;
	private volatile int lastCloseCode = -1;
	private volatile ScheduledFuture<?> heartbeat;
	private volatile ScheduledFuture<?> watchdog;
	private volatile long backoff = 1000L;
	private volatile int generation;
	private volatile boolean forcedInvalid;
	private int tail;
	private StringBuilder textFrame;
	private ByteArrayOutputStream binaryFrame;
	private long nextDelay = -1L;

	public Gateway(Poller poller, DiscordClient client) {
		this.poller = poller;
		if (Boolean.getBoolean("spc.noContent")) {
			contentIntent = false;
			intents = DiscordConfig.INTENT_GUILDS | DiscordConfig.INTENT_GUILD_MESSAGES;
			poller.contentIntent(false);
		}
		this.httpExecutor = Executors.newCachedThreadPool(runnable -> {
			Thread httpThread = new Thread(runnable, "spc-gateway-http");
			httpThread.setDaemon(true);
			return httpThread;
		});
		this.http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.executor(httpExecutor)
			.build();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread schedulerThread = new Thread(runnable, "spc-gateway-scheduler");
			schedulerThread.setDaemon(true);
			return schedulerThread;
		});
	}

	public void start() {
		if (thread != null) {
			return;
		}
		thread = new Thread(this::loop, "spc-gateway");
		thread.setDaemon(true);
		thread.start();
	}

	private void loop() {
		while (running) {
			CountDownLatch current = new CountDownLatch(1);
			closed = current;
			nextDelay = -1L;
			try {
				connect(current);
				current.await();
			} catch (Throwable throwable) {
				healthy = false;
				lastCloseCode = running ? 1006 : 1000;
				Log.debug("Gateway connection failed", throwable);
			}
			healthy = false;
			poller.requestPoll();
			awaitingAck.set(false);
			stopHeartbeat();
			cancelWatchdog();
			if (!running) {
				break;
			}
			int code = lastCloseCode;
			long delay;
			reconnects++;
			if (code == 4004) {
				delay = 600000L;
			} else if (code == 4014) {
				contentIntent = false;
				intents = DiscordConfig.INTENT_GUILDS | DiscordConfig.INTENT_GUILD_MESSAGES;
				sessionId = "";
				lastSeq = -1L;
				poller.contentIntentDisabled();
				delay = 1000L;
			} else if (nextDelay > 0L) {
				delay = nextDelay;
			} else {
				delay = Math.min(60000L, backoff + random.nextInt((int) (backoff / 4L) + 1));
				backoff = Math.min(60000L, backoff * 2L);
			}
			CountDownLatch pause = new CountDownLatch(1);
			scheduler.schedule(pause::countDown, delay, TimeUnit.MILLISECONDS);
			try {
				pause.await();
			} catch (InterruptedException interrupted) {
				break;
			}
		}
		healthy = false;
		stopHeartbeat();
		cancelWatchdog();
	}

	private void connect(CountDownLatch latch) throws Exception {
		if (gatewayUrl.isEmpty()) {
			Map<String, Object> info = Json.parseObject(poller.client().get("/gateway/bot").body);
			gatewayUrl = Json.str(info, "url", "");
			if (gatewayUrl.isEmpty()) {
				throw new IllegalStateException("empty gateway url");
			}
		}
		String base = !sessionId.isEmpty() && !resumeUrl.isEmpty() ? resumeUrl : gatewayUrl;
		String separator = base.contains("?") ? "&" : "?";
		URI uri = URI.create(base + separator + "v=" + DiscordConfig.GATEWAY_VERSION
			+ "&encoding=json&compress=zlib-stream");
		synchronized (inflateLock) {
			inflater.reset();
			inflated.reset();
			tail = 0;
			textFrame = null;
			binaryFrame = null;
		}
		lastAckRtt = -1L;
		lastCloseCode = -1;
		int currentGeneration = ++generation;
		Log.debug("Gateway connecting");
		socket = http.newWebSocketBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.buildAsync(uri, new Listener(latch, currentGeneration))
			.get(15L, TimeUnit.SECONDS);
		armWatchdog(currentGeneration);
	}

	private void stopHeartbeat() {
		ScheduledFuture<?> task = heartbeat;
		if (task != null) {
			task.cancel(false);
			heartbeat = null;
		}
	}

	private void armWatchdog(int currentGeneration) {
		try {
			watchdog = scheduler.schedule(() -> {
				if (currentGeneration == generation && !healthy) {
					close(4000, "ready timeout");
				}
			}, 30L, TimeUnit.SECONDS);
		} catch (RejectedExecutionException rejected) {
			Log.debug("Watchdog schedule failed", rejected);
		}
	}

	private void cancelWatchdog() {
		ScheduledFuture<?> task = watchdog;
		if (task != null) {
			task.cancel(false);
			watchdog = null;
		}
	}

	private void scheduleHeartbeat(long interval, int currentGeneration) {
		long jitter = (long) (random.nextDouble() * interval);
		heartbeat = scheduler.schedule(() -> beat(interval, currentGeneration), jitter, TimeUnit.MILLISECONDS);
	}

	private void beat(long interval, int currentGeneration) {
		if (!running || currentGeneration != generation) {
			return;
		}
		if (awaitingAck.get()) {
			close(4000, "ack timeout");
			return;
		}
		sendHeartbeat();
		long wait = Math.max(250L, interval + random.nextInt(1001) - 500L);
		try {
			heartbeat = scheduler.schedule(() -> beat(interval, currentGeneration), wait, TimeUnit.MILLISECONDS);
		} catch (RejectedExecutionException rejected) {
			Log.debug("Heartbeat schedule failed", rejected);
		}
	}

	private void sendHeartbeat() {
		awaitingAck.set(true);
		lastHeartbeatSent = System.currentTimeMillis();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("op", OP_HEARTBEAT);
		payload.put("d", lastSeq >= 0L ? lastSeq : null);
		send(payload);
	}

	private void send(Map<String, Object> payload) {
		send(socket, payload);
	}

	private void send(WebSocket current, Map<String, Object> payload) {
		if (current == null || current.isOutputClosed()) {
			return;
		}
		try {
			current.sendText(Json.stringify(payload), true);
		} catch (Throwable throwable) {
			Log.debug("Gateway send failed", throwable);
		}
	}

	private void sendIdentify(WebSocket current) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("os", System.getProperty("os.name", "unknown"));
		properties.put("browser", "Fabric Sync");
		properties.put("device", "Fabric Sync");
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("token", DiscordConfig.token());
		data.put("intents", intents);
		data.put("properties", properties);
		data.put("compress", false);
		data.put("shard", List.of(0, 1));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("op", OP_IDENTIFY);
		payload.put("d", data);
		send(current, payload);
	}

	private void sendResume(WebSocket current) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("token", DiscordConfig.token());
		data.put("session_id", sessionId);
		data.put("seq", lastSeq);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("op", OP_RESUME);
		payload.put("d", data);
		send(current, payload);
	}

	private void close(int code, String reason) {
		WebSocket current = socket;
		if (current == null || current.isOutputClosed()) {
			return;
		}
		int currentGeneration = generation;
		try {
			current.sendClose(code, reason);
		} catch (Throwable throwable) {
			Log.debug("Gateway close failed", throwable);
		}
		try {
			scheduler.schedule(() -> {
				if (currentGeneration == generation) {
					WebSocket late = socket;
					if (late != null) {
						late.abort();
					}
				}
			}, 5L, TimeUnit.SECONDS);
		} catch (RejectedExecutionException rejected) {
			Log.debug("Close abort schedule failed", rejected);
		}
	}

	void handlePayload(String json) {
		try {
			Map<String, Object> payload = Json.parseObject(json);
			int op = (int) Json.num(payload, "op", -1L);
			String type = Json.str(payload, "t", "");
			if (op != OP_HEARTBEAT_ACK) {
				Log.debug("Gateway op " + op + (type.isEmpty() ? "" : " " + type));
			}
			if (payload.containsKey("s")) {
				lastSeq = Json.num(payload, "s", lastSeq);
			}
			Map<String, Object> data = Json.map(payload.get("d"));
			switch (op) {
				case OP_HELLO -> {
					heartbeatInterval = Json.num(data, "heartbeat_interval", 41250L);
					scheduleHeartbeat(heartbeatInterval, generation);
				}
				case OP_HEARTBEAT_ACK -> {
					if (awaitingAck.compareAndSet(true, false)) {
						lastAckRtt = Math.max(0L, System.currentTimeMillis() - lastHeartbeatSent);
					}
				}
				case OP_HEARTBEAT -> sendHeartbeat();
				case OP_RECONNECT -> close(4000, "reconnect");
				case OP_INVALID_SESSION -> {
					boolean resumable = Boolean.TRUE.equals(payload.get("d"));
					nextDelay = resumable ? 1000L : 1000L + random.nextInt(4000);
					if (!resumable) {
						sessionId = "";
						lastSeq = -1L;
					}
					close(4000, "invalid session");
				}
				case OP_DISPATCH -> dispatch(type, data);
				default -> {
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Gateway payload failed", throwable);
		}
	}

	private void dispatch(String type, Map<String, Object> data) {
		switch (type) {
			case "READY" -> {
				Map<String, Object> user = Json.map(data.get("user"));
				String id = Json.str(user, "id", "");
				if (!id.isEmpty()) {
					poller.onGatewayReady(id);
				}
				sessionId = Json.str(data, "session_id", "");
				resumeUrl = Json.str(data, "resume_gateway_url", "");
				healthy = true;
				cancelWatchdog();
				backoff = 1000L;
			}
			case "RESUMED" -> {
				healthy = true;
				cancelWatchdog();
				backoff = 1000L;
			}
			case "MESSAGE_CREATE" -> poller.onGatewayMessage(data);
			default -> {
			}
		}
	}

	private void onCompressed(byte[] chunk, int currentGeneration) {
		if (stopped.get() || currentGeneration != generation) {
			return;
		}
		synchronized (inflateLock) {
			try {
				inflater.setInput(chunk);
				byte[] buffer = new byte[8192];
				while (!inflater.needsInput()) {
					int read = inflater.inflate(buffer);
					if (read <= 0) {
						break;
					}
					inflated.write(buffer, 0, read);
				}
			} catch (DataFormatException exception) {
				Log.debug("Gateway inflate failed", exception);
				overflow(currentGeneration);
				return;
			}
			if (inflated.size() > MAX_BUFFER) {
				overflow(currentGeneration);
				return;
			}
			for (byte value : chunk) {
				tail = (tail << 8) | (value & 0xff);
			}
			if (tail == 0x0000ffff) {
				String json = inflated.toString(StandardCharsets.UTF_8);
				inflated.reset();
				if (!json.isEmpty()) {
					handlePayload(json);
				}
			}
		}
	}

	private void overflow(int currentGeneration) {
		if (currentGeneration != generation) {
			return;
		}
		synchronized (inflateLock) {
			inflated.reset();
			inflater.reset();
			tail = 0;
			textFrame = null;
			binaryFrame = null;
		}
		Log.debug("Gateway buffer overflow");
		close(4000, "buffer overflow");
	}

	public void restart() {
		if (stopped.get() || !running) {
			return;
		}
		Thread currentThread = thread;
		if (currentThread == null || !currentThread.isAlive()) {
			thread = new Thread(this::loop, "spc-gateway");
			thread.setDaemon(true);
			thread.start();
			return;
		}
		if (Boolean.getBoolean("spc.op9false") && !forcedInvalid && !sessionId.isEmpty()) {
			sessionId = "invalid-session";
			forcedInvalid = true;
		}
		close(4000, "restart");
	}

	public void stop() {
		if (!stopped.compareAndSet(false, true)) {
			return;
		}
		running = false;
		healthy = false;
		WebSocket current = socket;
		if (current != null) {
			try {
				current.sendClose(1000, "shutdown").get(500L, TimeUnit.MILLISECONDS);
			} catch (Throwable throwable) {
				Log.debug("Gateway shutdown close failed", throwable);
			}
			current.abort();
		}
		stopHeartbeat();
		cancelWatchdog();
		scheduler.shutdownNow();
		httpExecutor.shutdownNow();
		synchronized (inflateLock) {
			inflater.end();
		}
		Thread currentThread = thread;
		if (currentThread != null) {
			currentThread.interrupt();
		}
	}

	public boolean healthy() {
		return healthy;
	}

	public boolean contentIntent() {
		return contentIntent;
	}

	public long rtt() {
		return lastAckRtt;
	}

	public int reconnects() {
		return reconnects;
	}

	public int lastCloseCode() {
		return lastCloseCode;
	}

	String sessionId() {
		return sessionId;
	}

	long lastSeq() {
		return lastSeq;
	}

	long heartbeatInterval() {
		return heartbeatInterval;
	}

	long nextDelay() {
		return nextDelay;
	}

	public String state() {
		if (stopped.get()) {
			return "stopped";
		}
		return healthy ? "healthy" : "down";
	}

	private final class Listener implements WebSocket.Listener {
		private final CountDownLatch latch;
		private final int currentGeneration;

		private Listener(CountDownLatch latch, int currentGeneration) {
			this.latch = latch;
			this.currentGeneration = currentGeneration;
		}

		@Override
		public void onOpen(WebSocket webSocket) {
			Log.debug("Gateway open");
			webSocket.request(1);
			if (!sessionId.isEmpty() && lastSeq >= 0L) {
				sendResume(webSocket);
				return;
			}
			long delay = Long.getLong("spc.identifyDelayMs", 0L);
			if (delay <= 0L) {
				sendIdentify(webSocket);
				return;
			}
			try {
				scheduler.schedule(() -> sendIdentify(webSocket), delay, TimeUnit.MILLISECONDS);
			} catch (RejectedExecutionException rejected) {
				Log.debug("Identify schedule failed", rejected);
			}
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			if (currentGeneration != generation) {
				webSocket.request(1);
				return null;
			}
			if (textFrame == null) {
				textFrame = new StringBuilder();
			}
			textFrame.append(data);
			if (textFrame.length() > MAX_BUFFER) {
				overflow(currentGeneration);
			} else if (last) {
				String json = textFrame.toString();
				textFrame = null;
				handlePayload(json);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			if (currentGeneration != generation) {
				webSocket.request(1);
				return null;
			}
			if (binaryFrame == null) {
				binaryFrame = new ByteArrayOutputStream();
			}
			byte[] chunk = new byte[data.remaining()];
			data.get(chunk);
			binaryFrame.write(chunk, 0, chunk.length);
			if (binaryFrame.size() > MAX_BUFFER) {
				overflow(currentGeneration);
			} else if (last) {
				byte[] message = binaryFrame.toByteArray();
				binaryFrame = null;
				onCompressed(message, currentGeneration);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			Log.debug("Gateway close " + statusCode + " " + reason);
			if (currentGeneration == generation) {
				lastCloseCode = statusCode;
				healthy = false;
				awaitingAck.set(false);
			}
			latch.countDown();
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			Log.debug("Gateway socket error", error);
			if (currentGeneration == generation) {
				healthy = false;
				lastCloseCode = 1006;
			}
			latch.countDown();
		}
	}
}
