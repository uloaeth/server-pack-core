package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class PanelSocketTest {
	@Test
	void resultBodyIsInsertedLiterally() {
		byte[] body = "{\"status\":\"error\",\"error\":\"busy\",\"result\":{},\"artifacts\":[]}"
			.getBytes(StandardCharsets.UTF_8);
		String frame = new String(PanelSocket.renderResultBytes("id", 7, "nonce", "reply", body),
			StandardCharsets.UTF_8);
		assertTrue(frame.startsWith("{\"v\":1,\"id\":\"id\",\"seq\":7,"));
		assertTrue(frame.endsWith("\"reply_to\":\"reply\",\"body\":" + new String(body, StandardCharsets.UTF_8) + "}"));
		// The body braces must survive untouched: exactly one object after body.
		assertEquals(1, frame.split("\"body\":\\{\"status\"", -1).length - 1);
	}

	@Test
	void theQueryIsNotSigned() {
		assertEquals("/api/v1/tasks", PanelSocket.signedPath("/api/v1/tasks?limit=32"));
		assertEquals("/api/v1/tasks", PanelSocket.signedPath("/api/v1/tasks?limit=32&after=9"));
		assertEquals("/api/v1/tasks", PanelSocket.signedPath("/api/v1/tasks?"));
	}

	@Test
	void plainPathsPassUnchanged() {
		assertEquals("/api/v1/ws-ticket", PanelSocket.signedPath("/api/v1/ws-ticket"));
		assertEquals("/api/v1/result", PanelSocket.signedPath("/api/v1/result"));
	}
}
