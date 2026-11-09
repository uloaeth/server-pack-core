package com.spcagentpayloa.jvmxx.crypto;

import com.spcagentpayloa.jvmxx.Log;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Local rolling log sealed with a key derived from the build secret. The file
 * is a sequence of base64(nonce|GCM) lines so it can be appended and tailed
 * without rewriting; it is never sent raw, only packed when explicitly asked.
 */
final class CryptoLog {
	private static final int MAX = 256 * 1024;
	private static final SecureRandom RANDOM = new SecureRandom();
	private final Path file;
	private final SecretKeySpec key;

	CryptoLog(Path file, byte[] secret) throws Exception {
		this.file = file;
		this.key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
			.digest(concat("SPC-LOG-V1".getBytes(StandardCharsets.UTF_8), secret)), "AES");
		Files.createDirectories(file.getParent());
	}

	void write(String message) {
		try {
			byte[] plain = (Instant.now() + " " + message).getBytes(StandardCharsets.UTF_8);
			byte[] nonce = new byte[12];
			RANDOM.nextBytes(nonce);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
			byte[] sealed = cipher.doFinal(plain);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			out.write(nonce);
			out.write(sealed);
			Files.writeString(file, Base64.getEncoder().encodeToString(out.toByteArray()) + "\n",
				StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			if (Files.size(file) > MAX) rotate();
		} catch (Throwable throwable) {
			Log.debug("Crypto log write failed", throwable);
		}
	}

	void debug(String message) {
		Log.debug("[crypto] " + message);
		write(message);
	}

	void debug(String message, Throwable throwable) {
		Log.debug("[crypto] " + message, throwable);
		write(message + (throwable == null ? "" : ": " + throwable.getClass().getSimpleName() + " " + throwable.getMessage()));
	}

	private void rotate() {
		try {
			Path old = file.resolveSibling(file.getFileName() + ".1");
			Files.deleteIfExists(old);
			Files.move(file, old);
		} catch (Throwable ignored) {
			// next write recreates it
		}
	}

	static byte[] concat(byte[] a, byte[] b) {
		byte[] out = new byte[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
