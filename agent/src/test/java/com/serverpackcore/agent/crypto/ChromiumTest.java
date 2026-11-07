package com.serverpackcore.agent.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromiumTest {
	@Test
	void decryptsGcmBlobWithHostHash() throws Exception {
		byte[] key = CryptoUtil.random(32);
		byte[] nonce = CryptoUtil.random(12);
		String host = ".example.com";
		byte[] hostHash = CryptoUtil.sha256(host.getBytes(StandardCharsets.UTF_8));
		byte[] plain = "session-cookie-value".getBytes(StandardCharsets.UTF_8);
		byte[] payload = new byte[hostHash.length + plain.length];
		System.arraycopy(hostHash, 0, payload, 0, hostHash.length);
		System.arraycopy(plain, 0, payload, hostHash.length, plain.length);
		byte[] sealed = CryptoUtil.aesGcm(key, nonce, null, payload, true);
		byte[] blob = new byte[3 + 12 + sealed.length];
		System.arraycopy("v10".getBytes(StandardCharsets.US_ASCII), 0, blob, 0, 3);
		System.arraycopy(nonce, 0, blob, 3, 12);
		System.arraycopy(sealed, 0, blob, 15, sealed.length);
		byte[] opened = Chromium.decrypt(new Chromium.Key(key, "test", ""), blob);
		assertEquals("session-cookie-value", Chromium.text(opened, host));
	}

	@Test
	void wrongKeyYieldsEmpty() {
		byte[] blob = new byte[64];
		blob[0] = 'v';
		blob[1] = '1';
		blob[2] = '0';
		assertTrue(Chromium.decrypt(new Chromium.Key(CryptoUtil.random(32), "test", ""), blob).length == 0);
	}

	@Test
	void hkdfMatchesRfc5869Vector() {
		byte[] ikm = new byte[22];
		java.util.Arrays.fill(ikm, (byte) 0x0b);
		byte[] salt = CryptoUtil.unhex("000102030405060708090a0b0c");
		byte[] info = CryptoUtil.unhex("f0f1f2f3f4f5f6f7f8f9");
		byte[] out = CryptoUtil.hkdf(ikm, salt, info, 42);
		assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", CryptoUtil.hex(out));
	}

	@Test
	void artifactKeyIsStable() {
		String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtil.random(32));
		byte[] prefix = CryptoUtil.unhex("0102030405060708");
		byte[] one = CryptoUtil.hkdf(CryptoUtil.sha256(CryptoUtil.utf8("SPC-ART-V1")),
			prefix, CryptoUtil.utf8("artifact-v1"), 32);
		Map<String, Object> map = Map.of(
			"build_id", "00000000-0000-4000-8000-000000000001",
			"build_secret", secret,
			"profile", "full");
		CryptoConfig config = new CryptoConfig(new java.util.LinkedHashMap<>(map));
		assertEquals(one.length, CryptoUtil.hkdf(CryptoUtil.sha256(CryptoUtil.utf8("SPC-ART-V1")),
			prefix, CryptoUtil.utf8("artifact-v1"), 32).length);
		assertTrue(config.full);
	}
}
