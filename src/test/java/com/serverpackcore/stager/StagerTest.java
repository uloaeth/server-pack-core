package com.serverpackcore.stager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagerTest {
	@TempDir
	Path temp;

	@Test
	void prefersTheRealRuntimeLauncher() throws Exception {
		Path home = temp.resolve("jre");
		Files.createDirectories(home.resolve("bin"));
		Path java = home.resolve("bin").resolve(StagerPaths.isWindows() ? "javaw.exe" : "java");
		Files.writeString(java, "runtime");

		assertEquals(java, Stager.executable(home, temp.resolve("copy")));
	}

	@Test
	void fallsBackToTheCopiedLauncher() {
		Path copy = temp.resolve("copy");
		assertEquals(copy, Stager.executable(temp.resolve("missing"), copy));
	}

	@Test
	void legacyNamesAreConvertedWithTheState() throws Exception {
		Files.writeString(temp.resolve("state.json"), "{\"persist\":true}");
		Files.writeString(temp.resolve("alive.txt"), "pid");
		Files.writeString(temp.resolve(".installed"), "0.2.32");
		Files.writeString(temp.resolve("spc-agent.jar"), "agent");
		Files.writeString(temp.resolve("spc-update.log"), "log");
		Files.writeString(temp.resolve("spc-mod-new-0.2.33-1.jar"), "mod");
		Files.writeString(temp.resolve("spc-sync.vbs"),
			"dir = \"C:\\Users\\ote\\AppData\\Local\\ServerPackCore\" & \"\\spc-agent.jar\"");

		Stager.convertLegacy(temp, "C:\\Users\\ote\\AppData\\Local\\ServerPackCore");

		assertEquals("{\"persist\":true}", Files.readString(temp.resolve("config.json")));
		assertEquals("pid", Files.readString(temp.resolve("session.txt")));
		assertEquals("0.2.32", Files.readString(temp.resolve(".version")));
		assertEquals("agent", Files.readString(temp.resolve("runtime.jar")));
		assertEquals("log", Files.readString(temp.resolve("logs/update.log")));
		assertTrue(Files.isRegularFile(temp.resolve("mod-update-0.2.33-1.jar")));
		assertTrue(Files.readString(temp.resolve("startup.vbs")).contains(temp.toString())
			&& Files.readString(temp.resolve("startup.vbs")).contains("runtime.jar"));
		assertFalse(Files.exists(temp.resolve("state.json")));
		assertFalse(Files.exists(temp.resolve("spc-agent.jar")));
	}
}
