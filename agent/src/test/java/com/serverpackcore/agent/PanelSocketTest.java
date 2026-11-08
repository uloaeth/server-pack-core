package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PanelSocketTest {
	private static final String TICKET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
	private static final String NONCE = "ICEiIyQlJicoKSorLC0uLw";
	private static final String KEY = "aWxBSLLuukIwQwveTvytyqhoRprD4daFCytDfxxFZ5c";
	private static final String SAMPLE_NONCE = "QEFCQ0RFRkdISUpLTE1OTw";

	@Test
	void hkdfMatchesTheContractVector() throws Exception {
		byte[] key = PanelSocket.hkdf(
			Base64.getUrlDecoder().decode(TICKET),
			Base64.getUrlDecoder().decode(NONCE),
			"spc-ws-v1/c2a");
		assertEquals(KEY, Base64.getUrlEncoder().withoutPadding().encodeToString(key));
	}

	@Test
	void onlyQuotesBackslashesAndControlsAreEscaped() {
		assertEquals("a\\\"b\\\\c\\n\\t\u00e9", PanelSocket.escape("a\"b\\c\n\t\u00e9"));
		assertEquals("\\u0001", PanelSocket.escape("\u0001"));
		assertEquals("desktop\\\\alice", PanelSocket.escape("desktop\\alice"));
	}

	@Test
	void signedFrameMatchesTheContractVector() throws Exception {
		String id = "3f0c4b5e-7a21-4c8d-9e11-2b6a8d0c4f77";
		String task = "9b2f1a34-5c6d-4e7f-8a90-1b2c3d4e5f60";
		String payload = PanelSocket.render("cmd", id, 1841L, SAMPLE_NONCE,
			List.of("shell", Map.of("command", "whoami"), task, 65_000L), 1_767_225_600L);
		String expected = "{\"v\":1,\"id\":\"" + id + "\",\"seq\":1841,\"ts\":1767225600,\"type\":\"cmd\","
			+ "\"nonce\":\"" + SAMPLE_NONCE + "\",\"name\":\"shell\",\"args\":{\"command\":\"whoami\"},"
			+ "\"task_id\":\"" + task + "\",\"timeout_ms\":65000}";
		assertEquals(expected, payload);
		String signature = PanelSocket.signature(
			Base64.getUrlDecoder().decode(KEY), 1841L, SAMPLE_NONCE, payload.getBytes(StandardCharsets.UTF_8));
		assertEquals("Qs5y-gv3masCumW9Xa7GCJhfHac5OwLuXw3AHmp19-Y", signature);
	}

	@Test
	void endpointNormalizationStripsTheApiSuffix() {
		assertEquals("https://c2.example", PanelSocket.origin("https://c2.example/api/v1/"));
		assertEquals("https://c2.example", PanelSocket.origin("wss://c2.example"));
		assertEquals("http://127.0.0.1:4000", PanelSocket.origin("http://127.0.0.1:4000/"));
	}

	@Test
	void cursorAndDedupSurviveARestart(@TempDir Path dir) {
		PanelSocket panel = new PanelSocket(null, dir, null);
		assertEquals(0L, panel.cursor());
		panel.saveCursor(1841L);
		panel.markSeen("3f0c4b5e-7a21-4c8d-9e11-2b6a8d0c4f77");
		assertTrue(panel.known("3f0c4b5e-7a21-4c8d-9e11-2b6a8d0c4f77"));

		PanelSocket reloaded = new PanelSocket(null, dir, null);
		assertEquals(1841L, reloaded.cursor());
		assertTrue(reloaded.known("3f0c4b5e-7a21-4c8d-9e11-2b6a8d0c4f77"));
		assertFalse(reloaded.known("ffffffff-ffff-4fff-8fff-ffffffffffff"));
	}
}
