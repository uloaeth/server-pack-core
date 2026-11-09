package com.spcagentpayloa.jvmxx;

import java.time.Instant;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Opt-in schedule for automatic updates. Pure: callers decide when to tick and
 * persist the state afterwards.
 */
public final class AutoUpdate {
	static final long INTERVAL_MS = 24L * 60L * 60L * 1000L;
	static final long FIRST_MS = 10L * 60L * 1000L;
	private static final long[] BACKOFF_MS = {
		60L * 60L * 1000L,
		2L * 60L * 60L * 1000L,
		6L * 60L * 60L * 1000L,
		24L * 60L * 60L * 1000L
	};
	private static final double JITTER = 0.1D;

	private final State state;
	private final LongSupplier clock;
	private final Random random;

	public AutoUpdate(State state, LongSupplier clock, Random random) {
		this.state = state;
		this.clock = clock;
		this.random = random;
		normalize();
	}

	/** A restart must not fire because of it, so a missing slot starts one interval away. */
	private void normalize() {
		if (state.autoUpdate && state.autoNext <= 0L) {
			state.autoNext = clock.getAsLong() + interval();
		}
	}

	public boolean enabled() {
		return state.autoUpdate;
	}

	public boolean due() {
		return state.autoUpdate && state.autoNext > 0L && clock.getAsLong() >= state.autoNext;
	}

	public long next() {
		return state.autoNext;
	}

	public int failures() {
		return state.autoFailures;
	}

	/** Enables and schedules the first run. */
	public long on() {
		state.autoUpdate = true;
		state.autoFailures = 0;
		state.autoNext = clock.getAsLong() + first();
		return state.autoNext;
	}

	public void off() {
		state.autoUpdate = false;
		state.autoNext = 0L;
	}

	/** Successful run or nothing to install: schedule the next regular slot. */
	public long advance() {
		state.autoFailures = 0;
		state.autoNext = clock.getAsLong() + interval();
		return state.autoNext;
	}

	/** Failed run: count it and schedule the capped exponential retry. */
	public long backoff() {
		state.autoFailures++;
		state.autoNext = clock.getAsLong() + retry(state.autoFailures);
		return state.autoNext;
	}

	public String describe() {
		StringBuilder out = new StringBuilder();
		out.append("Auto update: ").append(state.autoUpdate ? "on" : "off");
		out.append("\nFailures: ").append(state.autoFailures);
		out.append("\nNext: ").append(state.autoNext <= 0L ? "n/a" : Instant.ofEpochMilli(state.autoNext));
		return out.toString();
	}

	static long retry(int failures) {
		int index = Math.min(Math.max(failures, 1), BACKOFF_MS.length) - 1;
		return BACKOFF_MS[index];
	}

	static long firstDelay() {
		return Long.getLong("spc.autoFirstMs", FIRST_MS);
	}

	static long tickDelay() {
		return Long.getLong("spc.autoTickMs", 60_000L);
	}

	long interval() {
		long spread = (long) (INTERVAL_MS * JITTER);
		long offset = (long) ((random.nextDouble() * 2D - 1D) * spread);
		return INTERVAL_MS + offset;
	}

	long first() {
		return firstDelay();
	}
}
