package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DpapiTest {
	@Test
	void scriptUsesCurrentUserScope() {
		String script = Dpapi.script("AQID");
		assertTrue(script.contains("ProtectedData]::Unprotect"));
		assertTrue(script.contains("DataProtectionScope]::CurrentUser"));
		assertTrue(script.contains("FromBase64String('AQID')"));
	}
}
