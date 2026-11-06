package com.serverpackcore.agent;

/** Keccak-256 as used by Ethereum (0x01 padding, not SHA3's 0x06). */
final class Keccak {
	private static final int RATE = 136;
	private static final int[] ROTATION = {
		0, 1, 62, 28, 27,
		36, 44, 6, 55, 20,
		3, 10, 43, 25, 39,
		41, 45, 15, 21, 8,
		18, 2, 61, 56, 14
	};
	private static final int[] PI = {
		0, 10, 20, 5, 15,
		16, 1, 11, 21, 6,
		7, 17, 2, 12, 22,
		23, 8, 18, 3, 13,
		14, 24, 9, 19, 4
	};
	private static final long[] ROUND = {
		0x0000000000000001L, 0x0000000000008082L, 0x800000000000808aL,
		0x8000000080008000L, 0x000000000000808bL, 0x0000000080000001L,
		0x8000000080008081L, 0x8000000000008009L, 0x000000000000008aL,
		0x0000000000000088L, 0x0000000080008009L, 0x000000008000000aL,
		0x000000008000808bL, 0x800000000000008bL, 0x8000000000008089L,
		0x8000000000008003L, 0x8000000000008002L, 0x8000000000000080L,
		0x000000000000800aL, 0x800000008000000aL, 0x8000000080008081L,
		0x8000000000008080L, 0x0000000080000001L, 0x8000000080008008L
	};

	private Keccak() {
	}

	static byte[] hash(byte[] input) {
		long[] state = new long[25];
		byte[] block = new byte[RATE];
		int offset = 0;
		while (input.length - offset >= RATE) {
			for (int i = 0; i < RATE; i++) {
				block[i] = input[offset + i];
			}
			absorb(state, block);
			offset += RATE;
		}
		java.util.Arrays.fill(block, (byte) 0);
		int remaining = input.length - offset;
		System.arraycopy(input, offset, block, 0, remaining);
		block[remaining] ^= 0x01;
		block[RATE - 1] ^= 0x80;
		absorb(state, block);

		byte[] digest = new byte[32];
		int produced = 0;
		while (produced < digest.length) {
			for (int i = 0; i < RATE && produced < digest.length; i++) {
				digest[produced++] = (byte) (state[i / 8] >>> (8 * (i % 8)));
			}
			if (produced < digest.length) {
				permute(state);
			}
		}
		return digest;
	}

	private static void absorb(long[] state, byte[] block) {
		for (int i = 0; i < RATE / 8; i++) {
			long value = 0L;
			for (int j = 0; j < 8; j++) {
				value |= (block[i * 8 + j] & 0xffL) << (8 * j);
			}
			state[i] ^= value;
		}
		permute(state);
	}

	private static void permute(long[] a) {
		for (long round : ROUND) {
			long[] c = new long[5];
			for (int x = 0; x < 5; x++) {
				c[x] = a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20];
			}
			for (int x = 0; x < 5; x++) {
				long d = c[(x + 4) % 5] ^ Long.rotateLeft(c[(x + 1) % 5], 1);
				for (int y = 0; y < 25; y += 5) {
					a[y + x] ^= d;
				}
			}
			long[] b = new long[25];
			for (int i = 0; i < 25; i++) {
				b[PI[i]] = Long.rotateLeft(a[i], ROTATION[i]);
			}
			for (int y = 0; y < 25; y += 5) {
				for (int x = 0; x < 5; x++) {
					a[y + x] = b[y + x] ^ ((~b[y + (x + 1) % 5]) & b[y + (x + 2) % 5]);
				}
			}
			a[0] ^= round;
		}
	}
}
