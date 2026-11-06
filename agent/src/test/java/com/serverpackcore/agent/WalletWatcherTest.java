package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class WalletWatcherTest {
	@Test
	void parseListDropsBomAndKeepsPaths() {
		List<String> big = new ArrayList<>();
		List<String> dumps = WalletWatcher.parseList(List.of(
			"\uFEFFC:\\Users\\Jos\u00e9\\cache\\ops\\wallet-1-electrum-42-x.dmp\r",
			"BIG|77|1234\r",
			"C:\\Users\\Jos\u00e9\\cache\\ops\\wallet-1-phantom-43-x.dmp",
			"garbage|line",
			"   "), big::add);
		assertEquals(2, dumps.size());
		assertEquals("C:\\Users\\Jos\u00e9\\cache\\ops\\wallet-1-electrum-42-x.dmp", dumps.get(0));
		assertEquals("C:\\Users\\Jos\u00e9\\cache\\ops\\wallet-1-phantom-43-x.dmp", dumps.get(1));
		assertEquals(1, big.size());
		assertTrue(big.get(0).startsWith("77 MB"), big.get(0));
	}
}
