package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskAndScreenshotTest {
	@TempDir
	Path temp;

	@Test
	void lowSpaceDetectionUsesTheMinimum() {
		assertTrue(Disk.low(1L, 0L));
		assertFalse(Disk.low(Disk.MIN_FREE, 0L));
		assertTrue(Disk.low(10L * 1024L * 1024L, 200L * 1024L * 1024L));
	}

	@Test
	void requirePassesOnARealTempDir() throws Exception {
		Disk.require(temp, 1L);
		assertTrue(Disk.free(temp) > 0L);
	}

	@Test
	void screenshotEitherReturnsPngOrAFullyExplainedError() throws Exception {
		long began = System.currentTimeMillis();
		try {
			Path file = Screenshot.save(temp.resolve("shots"));
			byte[] data = Files.readAllBytes(file);
			assertTrue(data.length > 8);
			assertTrue((data[0] & 0xff) == 0x89 && data[1] == 'P');
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().startsWith("screenshot unavailable:"));
			assertTrue(expected.getMessage().contains("awt") || GraphicsEnvironment.isHeadless());
		}
		// No retry loop: a single attempt is bounded by the tool timeouts. The
		// bound is generous because a loaded machine can starve external tools.
		assertTrue(System.currentTimeMillis() - began < 180_000L);
	}

	@Test
	void requireReportsDiskLowReadably() {
		assertThrows(IllegalStateException.class, () -> Disk.require(temp, Long.MAX_VALUE));
	}
}
