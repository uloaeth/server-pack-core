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
		start(args);
	}

	public static synchronized void start(String[] args) {
		if (started) {
			return;
		}
		started = true;
		try {
			Path dir = AgentPaths.installDir();
			Files.createDirectories(dir);
			lockChannel = FileChannel.open(AgentPaths.lock(dir), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			lock = lockChannel.tryLock();
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
			if (state.persist) {
				// Repoint startup to the current runtime and repair the entry if something deleted or disabled it.
				Persistence.enable(dir);
			}
			poller = new Poller(dir, gameDir, state);
			poller.start();
			Bootstrap.maybe(poller, dir, state);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				try {
					if (poller != null) {
						poller.kill();
					}
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

	private static String version() {
		String version = Agent.class.getPackage().getImplementationVersion();
		return version == null ? "0.2.0" : version;
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
