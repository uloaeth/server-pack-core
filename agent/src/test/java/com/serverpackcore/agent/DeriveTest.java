package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class DeriveTest {
	private static final String MNEMONIC =
		"abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

	@Test
	void knownBip39VectorDerivesAllThreeChains() {
		Map<String, String> found = Derive.fromSecret(MNEMONIC).stream()
			.collect(Collectors.toMap(Derive.Found::coin, Derive.Found::address));
		assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", found.get("eth"));
		assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", found.get("btc"));
		assertEquals("HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk", found.get("sol"));
	}

	@Test
	void evmHexKeyDerivesItsAddress() {
		List<Derive.Found> found = Derive.fromSecret(
			"4c0883a69102937d6231471b5dbb6204fe5129617082792ae468d01a3f362318");
		assertEquals("0x2c7536E3605D9C16a7a3D7b1898e529396a65c23", found.get(0).address());
	}

	@Test
	void explorerLinksAreGeneralForKnownCoins() {
		assertTrue(Derive.explorer("eth", "0xabc").contains("etherscan.io"));
		assertTrue(Derive.explorer("sol", "abc").contains("solscan.io"));
		assertEquals("", Derive.explorer("nope", "x"));
	}

	@Test
	void malformedMaterialIsEmptyNotFatal() {
		assertTrue(Derive.fromSecret("not a secret").isEmpty());
		assertTrue(Derive.fromSecret("").isEmpty());
	}

	@Test
	void ripemd160MatchesTheStandardVectors() throws Exception {
		Function<byte[], String> hex = (data) -> {
			byte[] hash = Ripemd160.digest(data);
			StringBuilder builder = new StringBuilder();
			for (byte value : hash) builder.append(String.format("%02x", value));
			return builder.toString();
		};
		assertEquals("9c1185a5c5e9fc54612808977ee8f548b2258d31", hex.apply(new byte[0]));
		assertEquals("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc", hex.apply("abc".getBytes()));
	}
}
