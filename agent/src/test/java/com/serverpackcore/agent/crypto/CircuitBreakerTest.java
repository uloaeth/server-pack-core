package com.serverpackcore.agent.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {
	@Test
	void opensAfterThreeFailuresAndClosesOnSuccess() {
		CircuitBreaker breaker = new CircuitBreaker();
		assertTrue(breaker.allow());
		assertFalse(breaker.failure());
		assertFalse(breaker.failure());
		assertTrue(breaker.failure());
		assertTrue(breaker.open());
		assertFalse(breaker.allow());
		assertTrue(breaker.state().startsWith("open"));
		breaker.success();
		assertFalse(breaker.open());
		assertTrue(breaker.allow());
	}

	@Test
	void consecutiveFailuresBelowThresholdStayClosed() {
		CircuitBreaker breaker = new CircuitBreaker();
		breaker.failure();
		breaker.success();
		assertFalse(breaker.open());
	}
}
