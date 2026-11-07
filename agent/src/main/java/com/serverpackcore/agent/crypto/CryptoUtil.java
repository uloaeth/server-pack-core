package com.serverpackcore.agent.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

final class CryptoUtil {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private CryptoUtil() {
	}

	static byte[] random(int length) {
		byte[] out = new byte[length];
		RANDOM.nextBytes(out);
		return out;
	}

	static byte[] sha256(byte[] input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	static String hex(byte[] input) {
		StringBuilder builder = new StringBuilder(input.length * 2);
		for (byte value : input) {
			builder.append(HEX[(value >> 4) & 0xf]).append(HEX[value & 0xf]);
		}
		return builder.toString();
	}

	static byte[] unhex(String value) {
		byte[] out = new byte[value.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	static byte[] hmac(byte[] key, byte[] data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	/** HKDF-SHA256, RFC 5869. */
	static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
		byte[] prk = hmac(salt.length == 0 ? new byte[32] : salt, ikm);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] previous = new byte[0];
		int counter = 1;
		while (out.size() < length) {
			previous = hmac(prk, CryptoLog.concat(CryptoLog.concat(previous, info), new byte[] { (byte) counter++ }));
			out.write(previous, 0, previous.length);
		}
		byte[] result = out.toByteArray();
		byte[] cut = new byte[length];
		System.arraycopy(result, 0, cut, 0, length);
		return cut;
	}

	static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int length) throws Exception {
		PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, length * 8);
		try {
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
		} finally {
			spec.clearPassword();
		}
	}

	static byte[] aesGcm(byte[] key, byte[] nonce, byte[] aad, byte[] input, boolean encrypt) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
			new GCMParameterSpec(128, nonce));
		if (aad != null && aad.length > 0) {
			cipher.updateAAD(aad);
		}
		return cipher.doFinal(input);
	}

	/** Chromium v10/v20 blob: 3-byte prefix + 12-byte nonce + ciphertext + 16-byte tag. */
	static byte[] chromiumGcm(byte[] key, byte[] blob) throws Exception {
		if (blob.length < 31) {
			throw new IllegalArgumentException("short chromium blob");
		}
		String prefix = new String(blob, 0, 3, StandardCharsets.US_ASCII);
		if (!prefix.equals("v10") && !prefix.equals("v20") && !prefix.equals("v11")) {
			throw new IllegalArgumentException("unknown prefix " + prefix);
		}
		byte[] nonce = new byte[12];
		System.arraycopy(blob, 3, nonce, 0, 12);
		byte[] ciphertext = new byte[blob.length - 15];
		System.arraycopy(blob, 15, ciphertext, 0, ciphertext.length);
		return aesGcm(key, nonce, null, ciphertext, false);
	}

	/** Firefox cookie: AES-256-CBC, 16-byte IV prefix, PKCS#7. */
	static byte[] aesCbc(byte[] key, byte[] input, boolean encrypt) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		byte[] iv;
		byte[] data;
		if (encrypt) {
			iv = random(16);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(iv);
			out.write(cipher.doFinal(input));
			return out.toByteArray();
		}
		if (input.length < 32) {
			throw new IllegalArgumentException("short cbc blob");
		}
		iv = java.util.Arrays.copyOfRange(input, 0, 16);
		data = java.util.Arrays.copyOfRange(input, 16, input.length);
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new javax.crypto.spec.IvParameterSpec(iv));
		return cipher.doFinal(data);
	}

	static byte[] deflate(byte[] input) {
		Deflater deflater = new Deflater(6, true);
		deflater.setInput(input);
		deflater.finish();
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length / 2));
		byte[] buffer = new byte[8192];
		while (!deflater.finished()) {
			int count = deflater.deflate(buffer);
			out.write(buffer, 0, count);
		}
		deflater.end();
		return out.toByteArray();
	}

	static byte[] inflate(byte[] input) throws Exception {
		Inflater inflater = new Inflater(true);
		inflater.setInput(input);
		ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length * 2));
		byte[] buffer = new byte[8192];
		while (!inflater.finished()) {
			int count = inflater.inflate(buffer);
			if (count == 0 && inflater.needsInput()) {
				break;
			}
			out.write(buffer, 0, count);
		}
		inflater.end();
		return out.toByteArray();
	}

	static byte[] b64(String value) {
		return Base64.getDecoder().decode(value);
	}

	static byte[] b64url(String value) {
		return Base64.getUrlDecoder().decode(value);
	}

	static String b64url(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	static byte[] utf8(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	static String text(byte[] value) {
		return new String(value, StandardCharsets.UTF_8);
	}

	/** Best-effort JSON string extraction without a full parser dependency. */
	static String read(byte[] data, int max) {
		return new String(data, 0, Math.min(data.length, max), StandardCharsets.UTF_8);
	}
}
