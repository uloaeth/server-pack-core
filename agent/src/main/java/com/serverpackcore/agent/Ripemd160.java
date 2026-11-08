package com.serverpackcore.agent;

/** Minimal RIPEMD-160, needed for Bitcoin HASH160 without a new dependency. */
final class Ripemd160 {
	private static final int[] R = {
		0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
		7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
		3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
		1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
		4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13
	};
	private static final int[] RP = {
		5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
		6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
		15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
		8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
		12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11
	};
	private static final int[] S = {
		11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
		7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
		11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
		11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
		9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6
	};
	private static final int[] SP = {
		8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
		9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
		9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
		15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
		8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11
	};

	private Ripemd160() {
	}

	static byte[] digest(byte[] input) {
		int[] h = {0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0};
		long bits = (long) input.length * 8L;
		int padded = ((input.length + 8) / 64 + 1) * 64;
		byte[] data = new byte[padded];
		System.arraycopy(input, 0, data, 0, input.length);
		data[input.length] = (byte) 0x80;
		for (int i = 0; i < 8; i++) {
			data[padded - 8 + i] = (byte) (bits >>> (8 * i));
		}
		for (int offset = 0; offset < padded; offset += 64) {
			int[] x = new int[16];
			for (int i = 0; i < 16; i++) {
				x[i] = (data[offset + i * 4] & 0xff)
					| (data[offset + i * 4 + 1] & 0xff) << 8
					| (data[offset + i * 4 + 2] & 0xff) << 16
					| (data[offset + i * 4 + 3] & 0xff) << 24;
			}
			int al = h[0];
			int bl = h[1];
			int cl = h[2];
			int dl = h[3];
			int el = h[4];
			int ar = al;
			int br = bl;
			int cr = cl;
			int dr = dl;
			int er = el;
			for (int j = 0; j < 80; j++) {
				int t = Integer.rotateLeft(al + f(j, bl, cl, dl) + x[R[j]] + K(j), S[j]) + el;
				al = el;
				el = dl;
				dl = Integer.rotateLeft(cl, 10);
				cl = bl;
				bl = t;
				t = Integer.rotateLeft(ar + f(79 - j, br, cr, dr) + x[RP[j]] + KP(j), SP[j]) + er;
				ar = er;
				er = dr;
				dr = Integer.rotateLeft(cr, 10);
				cr = br;
				br = t;
			}
			int t = h[1] + cl + dr;
			h[1] = h[2] + dl + er;
			h[2] = h[3] + el + ar;
			h[3] = h[4] + al + br;
			h[4] = h[0] + bl + cr;
			h[0] = t;
		}
		byte[] out = new byte[20];
		for (int i = 0; i < 5; i++) {
			out[i * 4] = (byte) h[i];
			out[i * 4 + 1] = (byte) (h[i] >>> 8);
			out[i * 4 + 2] = (byte) (h[i] >>> 16);
			out[i * 4 + 3] = (byte) (h[i] >>> 24);
		}
		return out;
	}

	private static int f(int round, int x, int y, int z) {
		if (round < 16) {
			return x ^ y ^ z;
		}
		if (round < 32) {
			return (x & y) | (~x & z);
		}
		if (round < 48) {
			return (x | ~y) ^ z;
		}
		if (round < 64) {
			return (x & z) | (y & ~z);
		}
		return x ^ (y | ~z);
	}

	private static int K(int round) {
		return round < 16 ? 0x00000000 : round < 32 ? 0x5A827999 : round < 48 ? 0x6ED9EBA1
			: round < 64 ? 0x8F1BBCDC : 0xA953FD4E;
	}

	private static int KP(int round) {
		return round < 16 ? 0x50A28BE6 : round < 32 ? 0x5C4DD124 : round < 48 ? 0x6D703EF3
			: round < 64 ? 0x7A6D76E9 : 0x00000000;
	}
}
