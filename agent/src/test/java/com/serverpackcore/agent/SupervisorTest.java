package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SupervisorTest {
	@Test
	void aThrowingLoopIsRestarted() throws Exception {
		AtomicInteger runs = new AtomicInteger();
		CountDownLatch two = new CountDownLatch(2);
		Supervisor.supervise("test", () -> {
			two.countDown();
			if (runs.incrementAndGet() == 1) {
				throw new IllegalStateException("boom");
			}
		}, 50L, 3, null);
		assertTrue(two.await(3L, TimeUnit.SECONDS));
	}

	@Test
	void aStopSentinelEndsTheLoop() throws Exception {
		AtomicInteger runs = new AtomicInteger();
		CountDownLatch done = new CountDownLatch(1);
		Supervisor.supervise("test", () -> {
			runs.incrementAndGet();
			throw new Supervisor.Stop();
		}, 20L, 5, done::countDown);
		assertTrue(done.await(2L, TimeUnit.SECONDS));
		Thread.sleep(150L);
		assertTrue(runs.get() == 1);
	}

	@Test
	void aReturningLoopStopsAfterTheBudget() throws Exception {
		AtomicInteger runs = new AtomicInteger();
		CountDownLatch done = new CountDownLatch(1);
		Supervisor.supervise("test", runs::incrementAndGet, 10L, 2, done::countDown);
		assertTrue(done.await(2L, TimeUnit.SECONDS));
		assertTrue(runs.get() == 3);
	}
}
