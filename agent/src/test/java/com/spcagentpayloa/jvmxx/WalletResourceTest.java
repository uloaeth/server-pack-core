package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WalletResourceTest {
	@Test
	void chromePopupsAreMatchedByInstalledExtension() throws Exception {
		try (InputStream input = WalletResourceTest.class.getResourceAsStream("/spcagentpayloa/wallets.ps1")) {
			assertNotNull(input, "wallets.ps1 is shipped in the jar");
			String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			// Chrome leaves extension popup titles empty, so the title alone is
			// not enough: the popup geometry plus the installed extension id
			// name the wallet. The Phantom id must stay the first candidate.
			assertTrue(script.contains("HasPopup()"));
			assertTrue(script.contains("'bfnaelmomeimhlpmgjnjophhpkkoljpa' = 'phantom'"));
			assertTrue(script.indexOf("HasPopup()") < script.indexOf("$graceMs"));
		}
	}
}
