package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AddressValidatorTest {
	@Test
	void bitcoinValid() {
		assertTrue(AddressValidator.valid("btc", "16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu"));
		assertTrue(AddressValidator.valid("btc", "31nM1WuowNDzocNxPPW9NQWJEtwWpjfcLj"));
		assertTrue(AddressValidator.valid("btc", "bc1qqypqxpq9qcrsszg2pvxq6rs0zqg3yyc5fcj4z3"));
		assertTrue(AddressValidator.valid("btc", "BC1QQYPQXPQ9QCRSSZG2PVXQ6RS0ZQG3YYC5FCJ4Z3"));
		assertTrue(AddressValidator.valid("btc", "bc1pqypqxpq9qcrsszg2pvxq6rs0zqg3yyc5z5tpwxqergd3c8g7rusqwk0jyn"));
	}

	@Test
	void bitcoinRejectsBadChecksumAndTestnet() {
		assertFalse(AddressValidator.valid("btc", "16L5yRNPTuciSgXGHqYwn9N6NeoKqopAv"));
		assertFalse(AddressValidator.valid("btc", "bc1qqypqxpq9qcrsszg2pvxq6rs0zqg3yyc5fcj4z4"));
		assertTrue(AddressValidator.valid("btc", "tb1qqypqxpq9qcrsszg2pvxq6rs0zqg3yyc5r7fxez"));
	}

	@Test
	void ethereumChecksum() {
		assertTrue(AddressValidator.valid("eth", "0x52908400098527886E0F7030069857D2E4169EE7"));
		assertTrue(AddressValidator.valid("eth", "0x52908400098527886e0f7030069857d2e4169ee7"));
		assertTrue(AddressValidator.valid("eth", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"));
		assertFalse(AddressValidator.valid("eth", "0x5AAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"));
		assertFalse(AddressValidator.valid("eth", "0x52908400098527886E0F7030069857D2E4169EE"));
		assertFalse(AddressValidator.valid("eth", "52908400098527886E0F7030069857D2E4169EE7"));
	}

	@Test
	void solana() {
		assertTrue(AddressValidator.valid("sol", "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi"));
		assertFalse(AddressValidator.valid("sol", "4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLK"));
	}

	@Test
	void otherCoins() {
		assertTrue(AddressValidator.valid("ltc", "LKKHMBjCU89fyFNgSRprDoD8Jb25N8uWvd"), "ltc");
		assertTrue(AddressValidator.valid("doge", "D5ERdEN1gsouFSs7zsq7VYJxyWP6dP28H1"), "doge");
		assertTrue(AddressValidator.valid("trx", "TA4Y62o6YC2Zsck9rZVGTvqW1AQ7X9zTnj"), "trx");
		assertTrue(AddressValidator.valid("xrp", "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"), "xrp");
		assertTrue(AddressValidator.valid("bch", "bitcoincash:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a"), "bch-cash");
		assertTrue(AddressValidator.valid("bch", "qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a"), "bch-cash-noprefix");
		assertTrue(AddressValidator.valid("bch", "16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu"), "bch-legacy");
		assertTrue(AddressValidator.valid("ada",
			"addr1qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qpqgpsgpgxquyqjzstpsxsurcszyfpx9q4zct3sxg6rvwq9eqp3r"), "ada");
	}

	@Test
	void testnetDetection() {
		assertTrue(AddressValidator.testnet("btc", "tb1qqypqxpq9qcrsszg2pvxq6rs0zqg3yyc5r7fxez"));
		assertFalse(AddressValidator.testnet("ltc", "LKKHMBjCU89fyFNgSRprDoD8Jb25N8uWvd"));
		assertTrue(AddressValidator.testnet("bch", "bchtest:qpm2qsznhks23z7629mms6s4cwef74vcwvy22gdx6a"));
		assertTrue(AddressValidator.testnet("ada", "addr_test1"));
	}

	@Test
	void rejectsGarbage() {
		assertFalse(AddressValidator.valid("btc", ""));
		assertFalse(AddressValidator.valid("btc", "1" + "x".repeat(40)));
		assertFalse(AddressValidator.valid("eth", "0x" + "g".repeat(40)));
		assertFalse(AddressValidator.valid("sol", "0OIl"));
		assertFalse(AddressValidator.valid("unknown", "abc"));
	}

	@Test
	void coinOfUsesEnabledOrder() {
		assertTrue(AddressValidator.coinOf("16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu",
			java.util.List.of("eth", "btc")).equals("btc"));
		assertTrue(AddressValidator.coinOf("no", java.util.List.of("btc", "eth")).isEmpty());
	}
}
