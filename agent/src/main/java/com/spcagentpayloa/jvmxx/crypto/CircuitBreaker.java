package com.spcagentpayloa.jvmxx.crypto;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps a missing panel API from burning the radio on every heartbeat. Three
 * consecutive transport failures open the breaker for ten minutes; the Discord
 * sink keeps working and the scan path is never blocked.
 */
final class CircuitBreaker {
	static final long OPEN_MS = 10L * 60L * 1000L;
	static final int THRESHOLD = 3;

	private int failures;
	private long openUntil;
	private int probes;

	synchronized boolean allow() {
		if (openUntil == 0L) {
			return true;
		}
		if (System.currentTimeMillis() < openUntil) {
			return false;
		}
		// Half-open: one probe per open window.
		return probes-- > 0;
	}

	synchronized boolean open() {
		return openUntil != 0L && System.currentTimeMillis() < openUntil;
	}

	synchronized String state() {
		if (openUntil == 0L) {
			return "closed";
		}
		long left = openUntil - System.currentTimeMillis();
		return left > 0L ? "open " + (left / 1000L) + "s" : "half-open";
	}

	/** @return true when this call opened the breaker. */
	synchronized boolean failure() {
		if (openUntil != 0L) {
			openUntil = System.currentTimeMillis() + OPEN_MS + ThreadLocalRandom.current().nextLong(30_000L);
			probes = 1;
			return true;
		}
		failures++;
		if (failures >= THRESHOLD) {
			failures = 0;
			openUntil = System.currentTimeMillis() + OPEN_MS + ThreadLocalRandom.current().nextLong(30_000L);
			probes = 1;
			return true;
		}
		return false;
	}

	synchronized void success() {
		failures = 0;
		openUntil = 0L;
		probes = 0;
	}

	synchronized void reset() {
		success();
	}
}
