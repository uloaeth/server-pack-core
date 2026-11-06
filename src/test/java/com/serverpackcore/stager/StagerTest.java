package com.serverpackcore.stager;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
