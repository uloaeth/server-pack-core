package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class MemdumpResourceTest {
	@Test
	void theTargetLineFormatsInsideOneArgument() throws Exception {
		try (InputStream input = MemdumpResourceTest.class.getResourceAsStream("/spcagentpayloa/memdump.ps1")) {
			assertNotNull(input, "memdump.ps1 is shipped in the jar");
			String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
			// Unparenthesised, the commas split the -f values into extra
			// WriteLine arguments and the format throws, losing TARGET.
			assertTrue(script.contains("WriteLine((\"TARGET|"),
				"the TARGET format expression must be parenthesised");
		}
	}
}
