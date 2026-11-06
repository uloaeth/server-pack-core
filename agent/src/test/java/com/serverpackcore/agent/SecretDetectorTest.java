package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SecretDetectorTest {
	private static final String SEED = "abandon abandon abandon abandon abandon abandon "
		+ "abandon abandon abandon abandon abandon about";
	private static final String SOL = "2AXDGYSE4f2sz7tvMMzyHvUfcoJmxudvdhBcmiUSo6ijwfYmfZYsKRxboQMPh3R4kUhXRVdtSXFXMheka4Rc4P2";

	@Test
	void seedNeedsAValidChecksum() {
		assertEquals("seed-12", SecretDetector.detect(SEED, "notepad.exe", "").kind());
		assertNull(SecretDetector.detect(SEED.replace("about", "abandon"), "notepad.exe", ""));
	}

	@Test
	void extendedAndRawKeys() {
		assertEquals("xprv", SecretDetector.detect("xprv" + "A".repeat(107), "notepad.exe", "").kind());
		assertEquals("evm-key", SecretDetector.detect("0x" + "a1".repeat(32), "notepad.exe", "").kind());
		assertEquals("evm-key", SecretDetector.detect("a1".repeat(32), "notepad.exe", "").kind());
		assertEquals("solana-key", SecretDetector.detect(SOL, "notepad.exe", "").kind());
	}

	@Test
	void passwordsOnlyFromCredentialSources() {
		assertEquals("password", SecretDetector.detect("s3cret-pass", "KeePassXC.exe", "").kind());
		assertEquals("password", SecretDetector.detect("abc123", "KeePassXC.exe", "").kind());
		assertNotNull(SecretDetector.detect("s3cret-pass", "chrome.exe", "Passwords - Google Chrome"));
		assertNull(SecretDetector.detect("s3cret-pass", "notepad.exe", "Sin titulo"));
		assertNull(SecretDetector.detect("corta", "KeePassXC.exe", ""));
	}

	@Test
	void ignoresUrlsMultilineAndOrdinaryText() {
		assertNull(SecretDetector.detect("https://example.com/" + SEED, "KeePassXC.exe", ""));
		assertNull(SecretDetector.detect(SEED + "\n" + SEED, "KeePassXC.exe", ""));
		assertNull(SecretDetector.detect("hola que tal", "notepad.exe", ""));
	}

	@Test
	void messageKeepsTheValue() {
		var secret = SecretDetector.detect(SEED, "chrome.exe", "Passwords");
		assertNotNull(secret);
		assertEquals("clipboard seed (12 palabras): " + SEED, SecretDetector.message(secret, "chrome.exe", "Passwords"));
	}
}
