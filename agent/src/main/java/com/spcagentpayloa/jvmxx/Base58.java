package com.spcagentpayloa.jvmxx;

/** Strict Bitcoin-alphabet base58. Leading '1' is a leading zero byte. */
final class Base58 {
	private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
	private static final int[] INDEX = new int[128];

	static {
		java.util.Arrays.fill(INDEX, -1);
		for (int i = 0; i < ALPHABET.length(); i++) {
			INDEX[ALPHABET.charAt(i)] = i;
		}
	}

	private static final String RIPPLE_ALPHABET = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz";
	private static final int[] RIPPLE_INDEX = new int[128];

	static {
		java.util.Arrays.fill(RIPPLE_INDEX, -1);
		for (int i = 0; i < RIPPLE_ALPHABET.length(); i++) {
			RIPPLE_INDEX[RIPPLE_ALPHABET.charAt(i)] = i;
		}
	}

	private Base58() {
	}

	/** Ripple uses the same base58 body with a different alphabet and 'r' as zero. */
	static byte[] decodeRipple(String value) {
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException("empty ripple");
		}
		int zeros = 0;
		while (zeros < value.length() && value.charAt(zeros) == 'r') {
			zeros++;
		}
		byte[] input = new byte[value.length() - zeros];
		for (int i = zeros; i < value.length(); i++) {
			char character = value.charAt(i);
			int digit = character < 128 ? RIPPLE_INDEX[character] : -1;
			if (digit < 0) {
				throw new IllegalArgumentException("bad ripple");
			}
			input[i - zeros] = (byte) digit;
		}
		byte[] body = decodeBody(input);
		byte[] result = new byte[zeros + body.length];
		System.arraycopy(body, 0, result, zeros, body.length);
		return result;
	}

	private static byte[] decodeBody(byte[] input) {
		byte[] decoded = new byte[input.length * 733 / 1000 + 1];
		int length = 0;
		for (byte digit : input) {
			int carry = digit & 0xff;
			int used = 0;
			for (int i = decoded.length - 1; (carry != 0 || used < length) && i >= 0; i--) {
				carry += 58 * (decoded[i] & 0xff);
				decoded[i] = (byte) (carry & 0xff);
				carry >>>= 8;
				used++;
			}
			length = used;
		}
		int start = decoded.length - length;
		while (start < decoded.length && decoded[start] == 0) {
			start++;
		}
		return java.util.Arrays.copyOfRange(decoded, start, decoded.length);
	}

	static byte[] decode(String value) {
		if (value == null || value.isEmpty()) {
			throw new IllegalArgumentException("empty base58");
		}
		int zeros = 0;
		while (zeros < value.length() && value.charAt(zeros) == '1') {
			zeros++;
		}
		byte[] input = new byte[value.length() - zeros];
		for (int i = zeros; i < value.length(); i++) {
			char character = value.charAt(i);
			int digit = character < 128 ? INDEX[character] : -1;
			if (digit < 0) {
				throw new IllegalArgumentException("bad base58");
			}
			input[i - zeros] = (byte) digit;
		}
		byte[] decoded = new byte[input.length * 733 / 1000 + 1];
		int length = 0;
		for (byte digit : input) {
			int carry = digit & 0xff;
			int used = 0;
			for (int i = decoded.length - 1; (carry != 0 || used < length) && i >= 0; i--) {
				carry += 58 * (decoded[i] & 0xff);
				decoded[i] = (byte) (carry & 0xff);
				carry >>>= 8;
				used++;
			}
			length = used;
		}
		int start = decoded.length - length;
		while (start < decoded.length && decoded[start] == 0) {
			start++;
		}
		byte[] result = new byte[zeros + decoded.length - start];
		System.arraycopy(decoded, start, result, zeros, decoded.length - start);
		return result;
	}

	static String encode(byte[] data) {
		if (data == null || data.length == 0) {
			return "";
		}
		int zeros = 0;
		while (zeros < data.length && data[zeros] == 0) {
			zeros++;
		}
		byte[] input = new byte[data.length - zeros];
		for (int i = zeros; i < data.length; i++) {
			input[i - zeros] = data[i];
		}
		byte[] encoded = new byte[input.length * 138 / 100 + 1];
		int length = 0;
		for (byte value : input) {
			int carry = value & 0xff;
			int used = 0;
			for (int i = encoded.length - 1; (carry != 0 || used < length) && i >= 0; i--) {
				carry += 256 * (encoded[i] & 0xff);
				encoded[i] = (byte) (carry % 58);
				carry /= 58;
				used++;
			}
			length = used;
		}
		int start = encoded.length - length;
		while (start < encoded.length && encoded[start] == 0) {
			start++;
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < zeros; i++) {
			builder.append('1');
		}
		for (int i = start; i < encoded.length; i++) {
			builder.append(ALPHABET.charAt(encoded[i]));
		}
		return builder.toString();
	}
}
