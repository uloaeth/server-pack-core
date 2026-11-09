package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayProtocolTest {
	@TempDir
	Path temp;

	private Poller poller;
	private Gateway gateway;

	@BeforeEach
	void setUp() {
		poller = new Poller(temp, temp, new State());
		gateway = new Gateway(poller, null);
	}

	@AfterEach
	void tearDown() {
		gateway.stop();
		poller.kill();
	}

	private void ready() {
		gateway.handlePayload("{\"op\":0,\"t\":\"READY\",\"s\":1,\"d\":{"
			+ "\"user\":{\"id\":\"42\"},\"session_id\":\"session\",\"resume_gateway_url\":\"wss://resume\"}}");
	}

	@Test
	void invalidSessionResumable() {
		ready();
		gateway.handlePayload("{\"op\":9,\"d\":true}");
		assertEquals("session", gateway.sessionId());
		assertEquals(1000L, gateway.nextDelay());
	}

	@Test
	void invalidSessionNotResumable() {
		ready();
		gateway.handlePayload("{\"op\":9,\"d\":false}");
		assertEquals("", gateway.sessionId());
		assertTrue(gateway.nextDelay() >= 1000L);
		assertTrue(gateway.nextDelay() <= 5000L);
	}

	@Test
	void parsesHeartbeatInterval() {
		gateway.handlePayload("{\"op\":10,\"d\":{\"heartbeat_interval\":45000}}");
		assertEquals(45000L, gateway.heartbeatInterval());
	}
}
