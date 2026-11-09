package com.serverpackcore.agent.crypto;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrollRaceTest {
	private static CryptoTransport transport() {
		CryptoConfig config = new CryptoConfig(Map.of("build_id", "b", "build_secret", "s"));
		return new CryptoTransport(config, null);
	}

	/** A 401 signed before another loop enrolled must not trigger a second enroll. */
	@Test
	void staleOnlyWhenTheTokenActuallyRotated() {
		assertTrue(CryptoModule.stale(1L, 2L, true));
		assertFalse(CryptoModule.stale(2L, 2L, true));
		// Nothing to preserve yet: the first enroll has to run.
		assertFalse(CryptoModule.stale(0L, 1L, false));
	}

	@Test
	void identityRotationBumpsTheGeneration() {
		CryptoTransport transport = transport();
		long start = transport.generation();
		transport.identity("victim-a", "token-a");
		assertEquals(start + 1L, transport.generation());
		transport.identity("victim-a", "token-b");
		assertEquals(start + 2L, transport.generation());
		// Never enrolled: the generation moves but there is nothing to reuse.
		assertFalse(CryptoModule.stale(start, transport.generation(), false));
		assertTrue(CryptoModule.stale(start, transport.generation(), transport.enrolled()));
	}
}
