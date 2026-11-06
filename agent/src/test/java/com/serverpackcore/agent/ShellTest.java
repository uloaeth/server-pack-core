package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellTest {
	@TempDir
	Path temp;

	@Test
	void timeoutKillsDescendants() throws Exception {
		long start = System.currentTimeMillis();
		String output = Shell.run(temp, "sleep 30 & echo $!; wait", 500L);
		long elapsed = System.currentTimeMillis() - start;
		assertTrue(output.contains("[timeout]"));
		assertTrue(elapsed < 5000L);
		String pidText = output.lines().findFirst().orElse("0").trim();
		long pid = Long.parseLong(pidText);
		boolean alive = true;
		for (int i = 0; i < 40 && alive; i++) {
			Optional<ProcessHandle> handle = ProcessHandle.of(pid);
			alive = handle.isPresent() && handle.get().isAlive();
			if (alive) {
				Thread.sleep(50L);
			}
		}
		assertFalse(alive);
	}

	@Test
	void outputOverOneMBTruncated() throws Exception {
		String output = Shell.run(temp, "head -c 2000000 /dev/zero | tr '\\0' a", 20000L);
		assertTrue(output.length() <= 1024 * 1024);
		assertTrue(output.length() > 900 * 1024);
	}

	@Test
	void cwdRespected() throws Exception {
		Files.writeString(temp.resolve("marker.txt"), "x");
		assertTrue(Shell.run(temp, "ls", 5000L).contains("marker.txt"));
	}

	@Test
	void trackerReceivesTheProcess() throws Exception {
		java.util.concurrent.atomic.AtomicLong seen = new java.util.concurrent.atomic.AtomicLong();
		String output = Shell.run(temp, "echo tracked", 5000L,
			process -> seen.set(process.pid()));
		assertTrue(seen.get() > 0L);
		assertTrue(output.contains("tracked"));
	}
}
