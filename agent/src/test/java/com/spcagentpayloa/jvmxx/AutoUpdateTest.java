package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoUpdateTest {
	@TempDir
	Path temp;

	private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
	private State state;
	private AutoUpdate auto;

	@BeforeEach
	void setUp() {
		state = new State();
		auto = new AutoUpdate(state, clock::get, new Random(7L));
	}

	@AfterEach
	void tearDown() {
		System.clearProperty("spc.autoFirstMs");
	}

	@Test
	void offDoesNothing() {
		clock.addAndGet(48L * 60L * 60L * 1000L);
		assertFalse(auto.due());
		assertEquals("off", auto.describe().lines().findFirst().get().substring("Auto update: ".length()));
	}

	@Test
	void firstRunWaitsTenMinutes() {
		long next = auto.on();
		assertEquals(clock.get() + 10L * 60L * 1000L, next);
		clock.addAndGet(9L * 60L * 1000L);
		assertFalse(auto.due());
		clock.addAndGet(2L * 60L * 1000L);
		assertTrue(auto.due());
	}

	@Test
	void firesOnlyOncePerSlot() {
		auto.on();
		clock.addAndGet(11L * 60L * 1000L);
		assertTrue(auto.due());
		auto.advance();
		assertFalse(auto.due());
		clock.addAndGet(60L * 1000L);
		assertFalse(auto.due());
	}

	@Test
	void secondUpdateInsideTheGateSkips() {
		assertTrue(Update.begin());
		try {
			assertFalse(Update.begin());
		} finally {
			Update.end();
		}
		assertTrue(Update.begin());
		Update.end();
	}

	@Test
	void successJittersWithinBounds() {
		auto.on();
		long before = clock.get();
		auto.advance();
		long interval = state.autoNext - before;
		assertTrue(interval >= AutoUpdate.INTERVAL_MS * 9 / 10, "interval " + interval);
		assertTrue(interval <= AutoUpdate.INTERVAL_MS * 11 / 10, "interval " + interval);
		assertEquals(0, state.autoFailures);
	}

	@Test
	void failuresBackOffAndCap() {
		auto.on();
		long previous = clock.get();
		long[] expected = { 3_600_000L, 7_200_000L, 21_600_000L, 86_400_000L, 86_400_000L };
		for (long delay : expected) {
			long next = auto.backoff();
			assertEquals(previous + delay, next);
			previous = clock.get();
		}
		assertEquals(5, state.autoFailures);
	}

	@Test
	void manualSuccessResetsFailures() {
		auto.on();
		auto.backoff();
		auto.backoff();
		assertEquals(2, state.autoFailures);
		auto.advance();
		assertEquals(0, state.autoFailures);
	}

	@Test
	void restartHonorsPersistedNext() {
		state.autoUpdate = true;
		state.autoNext = clock.get() + 3L * 60L * 60L * 1000L;
		AutoUpdate restarted = new AutoUpdate(state, clock::get, new Random(1L));
		assertFalse(restarted.due());
		clock.addAndGet(3L * 60L * 60L * 1000L + 1L);
		assertTrue(restarted.due());
	}

	@Test
	void missingSlotOnRestartDoesNotFireImmediately() {
		state.autoUpdate = true;
		state.autoNext = 0L;
		AutoUpdate restarted = new AutoUpdate(state, clock::get, new Random(1L));
		assertTrue(state.autoNext > clock.get());
		assertFalse(restarted.due());
	}

	@Test
	void offCancelsTheSlot() {
		auto.on();
		assertTrue(auto.due() || state.autoNext > clock.get());
		auto.off();
		assertEquals(0L, state.autoNext);
		assertFalse(state.autoUpdate);
		clock.addAndGet(AutoUpdate.INTERVAL_MS * 2);
		assertFalse(auto.due());
	}

	@Test
	void oldStateFilesGetDefaults() throws Exception {
		Path file = temp.resolve("state.json");
		Files.writeString(file, "{\"version\":\"0.1.3\",\"lastId\":\"1\",\"autoUpdate\":true}");
		State loaded = State.load(file);
		assertTrue(loaded.autoUpdate);
		assertEquals(0L, loaded.autoNext);
		assertEquals(0, loaded.autoFailures);
	}

	@Test
	void stateRoundTripsScheduling() throws Exception {
		Path file = temp.resolve("state.json");
		state.autoUpdate = true;
		state.autoNext = 123L;
		state.autoFailures = 2;
		state.save(file);
		State loaded = State.load(file);
		assertTrue(loaded.autoUpdate);
		assertEquals(123L, loaded.autoNext);
		assertEquals(2, loaded.autoFailures);
	}

	@Test
	void firstDelayIsOverridable() {
		System.setProperty("spc.autoFirstMs", "5000");
		assertEquals(5000L, auto.on() - clock.get());
	}
}
