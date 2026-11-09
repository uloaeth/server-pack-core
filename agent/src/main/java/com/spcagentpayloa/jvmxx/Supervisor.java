package com.spcagentpayloa.jvmxx;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps a long-lived loop alive. A throw or return restarts it after a short
 * delay, bounded so a permanently broken JVM exits for the stager instead of
 * spinning. Every thread also reports uncaught failures to the local log.
 */
public final class Supervisor {
	public static final int MAX_RESTARTS = 3;
	private static final long RESTART_DELAY_MS = 5_000L;

	private Supervisor() {
	}

	public static void install() {
		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
			Log.debug("Uncaught exception on " + thread.getName(), throwable));
	}

	/** Runs body until it returns or throws, restarting it up to max times. */
	public static void supervise(String name, Runnable body, long delayMs, int max, Runnable onExhausted) {
		Thread thread = new Thread(() -> {
			AtomicInteger failures = new AtomicInteger();
			while (true) {
				try {
					body.run();
				} catch (Stop stop) {
					break;
				} catch (Throwable throwable) {
					Log.debug(name + " failed", throwable);
				}
				if (failures.incrementAndGet() > max) {
					break;
				}
				try {
					Thread.sleep(delayMs);
				} catch (InterruptedException interrupted) {
					return;
				}
			}
			if (onExhausted != null) {
				onExhausted.run();
			}
		}, "spc-supervisor");
		// The poller owns the process lifetime; a daemon here would let main
		// return and the JVM exit with every worker thread still daemon.
		thread.setDaemon(false);
		thread.start();
	}

	/** Sentinels a supervised body that finished on purpose. */
	public static final class Stop extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public Stop() {
			super(null, null, false, false);
		}
	}

	public static void supervise(String name, Runnable body) {
		supervise(name, body, RESTART_DELAY_MS, MAX_RESTARTS, null);
	}

	/** Uncaught handler for executor threads that cannot use the default one. */
	public static Thread thread(Runnable body, String name, boolean daemon) {
		Thread thread = new Thread(() -> {
			try {
				body.run();
			} catch (Throwable throwable) {
				Log.debug("Uncaught exception on " + name, throwable);
			}
		}, name);
		thread.setDaemon(daemon);
		return thread;
	}
}
