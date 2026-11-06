package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * The OPSEC helpers only run on Windows, so these checks pin the invariants that
 * keep an agent restart from orphaning helpers or corrupting non-ASCII paths.
 */
class OpsScriptsTest {
	private static String script(String name) throws IOException {
		try (InputStream input = OpsScriptsTest.class.getResourceAsStream("/serverpackcore/" + name)) {
			if (input == null) {
				throw new IOException("missing " + name);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void walletWatcherStopsWithTheAgent() throws IOException {
		String watcher = script("wallets.ps1");
		assertTrue(watcher.contains("$ParentPid"), "watcher must receive the agent pid");
		assertTrue(watcher.contains("Get-Process -Id $ParentPid"), "watcher must check the agent");
		assertTrue(watcher.contains("exit 0"), "watcher must exit when the agent dies");
	}

	@Test
	void walletWatcherHoldsFocusChanges() throws IOException {
		String watcher = script("wallets.ps1");
		assertTrue(watcher.contains("$graceMs"), "a title change needs a grace window");
		assertTrue(watcher.contains("TickCount64"), "the grace uses a monotonic clock");
		assertTrue(watcher.contains("$lostReported"), "a held PID must still report a real exit");
	}

	@Test
	void dumpListIsBomFreeUtf8() throws IOException {
		String dump = script("memdump.ps1");
		assertTrue(dump.contains("[IO.File]::AppendAllText"), "list must not use ANSI Add-Content");
		assertTrue(dump.contains("UTF8Encoding"), "list must be written as UTF-8");
		assertFalse(dump.contains("Add-Content -LiteralPath $OutList"));
		assertTrue(dump.contains("ParentProcessId"), "extension popups must resolve their browser");
	}

	@Test
	void keyHookDoesNotCallUia() throws IOException {
		String keys = script("keys.ps1");
		assertTrue(keys.contains("Set-Context $context $false"),
			"the hook callback must not run UI Automation");
	}

	@Test
	void clipboardWaitsForThePreviousHelper() throws IOException {
		String clipboard = script("clipboard.ps1");
		assertTrue(clipboard.contains("WaitOne(3000)"), "a restart must not race the mutex");
	}
}
