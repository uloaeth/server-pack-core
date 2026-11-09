package com.spcagentpayloa.jvmxx;

/** BIP-173/BIP-350 decoder. Returns the witness version and the 8-bit program. */
final class Bech32 {
	private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
	private static final int[] INDEX = new int[128];
	private static final int[] GENERATOR = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

	static {
		java.util.Arrays.fill(INDEX, -1);
		for (int i = 0; i < CHARSET.length(); i++) {
			INDEX[CHARSET.charAt(i)] = i;
		}
	}

	private Bech32() {
	}

	record Decoded(String hrp, int[] words, boolean bech32m) {
	}

	static Decoded decode(String value) {
		if (value == null || value.length() < 8 || value.length() > 130) {
			throw new IllegalArgumentException("bech32 length");
		}
		boolean lower = false;
		boolean upper = false;
		for (int i = 0; i < value.length(); i++) {
			char character = value.charAt(i);
			if (character >= 'a' && character <= 'z') {
				lower = true;
			} else if (character >= 'A' && character <= 'Z') {
				upper = true;
			} else if (character < 33 || character > 126) {
				throw new IllegalArgumentException("bech32 charset");
			}
		}
		if (lower && upper) {
			throw new IllegalArgumentException("mixed case");
		}
		String text = value.toLowerCase(java.util.Locale.ROOT);
		int separator = text.lastIndexOf('1');
		if (separator < 1 || separator + 7 > text.length()) {
			throw new IllegalArgumentException("bech32 separator");
		}
		String hrp = text.substring(0, separator);
		int[] data = new int[text.length() - separator - 1];
		for (int i = 0; i < data.length; i++) {
			char character = text.charAt(separator + 1 + i);
			int digit = character < 128 ? INDEX[character] : -1;
			if (digit < 0) {
				throw new IllegalArgumentException("bech32 data");
			}
			data[i] = digit;
		}
		int polymod = polymod(expand(hrp), data);
		boolean bech32m;
		if (polymod == 1) {
			bech32m = false;
		} else if (polymod == 0x2bc830a3) {
			bech32m = true;
		} else {
			throw new IllegalArgumentException("bech32 checksum");
		}
		return new Decoded(hrp, java.util.Arrays.copyOf(data, data.length - 6), bech32m);
	}

	static byte[] program(Decoded decoded) {
		return bytes(decoded, 1);
	}

	/** Full payload, without the witness version word (used by Cardano). */
	static byte[] payload(Decoded decoded) {
		return bytes(decoded, 0);
	}

	private static byte[] bytes(Decoded decoded, int skip) {
		int[] words = decoded.words();
		int[] values = words.length > skip ? java.util.Arrays.copyOfRange(words, skip, words.length) : new int[0];
		int accumulator = 0;
		int bits = 0;
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		for (int value : values) {
			if (value < 0 || value > 31) {
				throw new IllegalArgumentException("bech32 word");
			}
			accumulator = (accumulator << 5) | value;
			bits += 5;
			while (bits >= 8) {
				bits -= 8;
				out.write((accumulator >> bits) & 0xff);
			}
		}
		if (bits >= 5 || ((accumulator << (8 - bits)) & 0xff) != 0) {
			throw new IllegalArgumentException("bech32 padding");
		}
		return out.toByteArray();
	}

	private static int[] expand(String hrp) {
		int[] values = new int[hrp.length() * 2 + 1];
		for (int i = 0; i < hrp.length(); i++) {
			values[i] = hrp.charAt(i) >> 5;
			values[hrp.length() + 1 + i] = hrp.charAt(i) & 31;
		}
		return values;
	}

	private static int polymod(int[] expanded, int[] data) {
		int checksum = 1;
		for (int value : expanded) {
			checksum = step(checksum, value);
		}
		for (int value : data) {
			checksum = step(checksum, value);
		}
		return checksum;
	}

	private static int step(int checksum, int value) {
		int top = checksum >>> 25;
		checksum = ((checksum & 0x1ffffff) << 5) ^ value;
		for (int i = 0; i < 5; i++) {
			if (((top >>> i) & 1) != 0) {
				checksum ^= GENERATOR[i];
			}
		}
		return checksum;
	}

	static String encode(String hrp, int[] words, boolean bech32m) {
		int[] data = new int[words.length + 6];
		System.arraycopy(words, 0, data, 0, words.length);
		int polymod = polymod(expand(hrp), data) ^ (bech32m ? 0x2bc830a3 : 1);
		StringBuilder builder = new StringBuilder(hrp).append('1');
		for (int word : words) {
			builder.append(CHARSET.charAt(word));
		}
		for (int i = 0; i < 6; i++) {
			builder.append(CHARSET.charAt((polymod >>> (5 * (5 - i))) & 31));
		}
		return builder.toString();
	}

	static int[] toWords(byte[] data) {
		int accumulator = 0;
		int bits = 0;
		int[] words = new int[data.length * 8 / 5 + 1];
		int count = 0;
		for (byte value : data) {
			accumulator = (accumulator << 8) | (value & 0xff);
			bits += 8;
			while (bits >= 5) {
				bits -= 5;
				words[count++] = (accumulator >> bits) & 31;
			}
		}
		if (bits > 0) {
			words[count++] = (accumulator << (5 - bits)) & 31;
		}
		return java.util.Arrays.copyOf(words, count);
	}
}
