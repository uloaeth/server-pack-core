package com.serverpackcore.stager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagerPathsTest {
	@TempDir
	Path temp;

	@Test
	void modernTreeUsesTheNewNames() {
		Path dir = temp.resolve("FabricSync");
		assertFalse(StagerPaths.legacy(dir));
		assertEquals("runtime.jar", StagerPaths.agentJar(dir).getFileName().toString());
		assertEquals("runtime.lock", StagerPaths.lock(dir).getFileName().toString());
		assertEquals(".version", StagerPaths.marker(dir).getFileName().toString());
		assertEquals("session.txt", StagerPaths.alive(dir).getFileName().toString());
		assertEquals("config.json", StagerPaths.state(dir).getFileName().toString());
		assertEquals("crypto-config.json", StagerPaths.cryptoConfig(dir).getFileName().toString());
	}

	@Test
	void modernTreeBlendsIntoLocalAppDataOnWindows() {
		Path base = temp.resolve("Local");
		Path expected = StagerPaths.isWindows()
			? base.resolve("Microsoft").resolve("Java")
			: base.resolve("FabricSync");
		assertEquals(expected, StagerPaths.modernDir(base));
	}

	@Test
	void versionMarkerProvesOwnershipEvenWhenDamaged() throws Exception {
		assertFalse(StagerPaths.owned(temp));
		Files.writeString(temp.resolve(".version"), "0.2.33");
		assertTrue(StagerPaths.owned(temp), "a quarantined runtime must stay owned");
	}

	@Test
	void legacyTreeKeepsTheOldNames() {
		Path dir = temp.resolve("ServerPackCore");
		assertTrue(StagerPaths.legacy(dir));
		assertEquals("spc-agent.jar", StagerPaths.agentJar(dir).getFileName().toString());
		assertEquals("spc-agent.lock", StagerPaths.lock(dir).getFileName().toString());
		assertEquals(".installed", StagerPaths.marker(dir).getFileName().toString());
		assertEquals("alive.txt", StagerPaths.alive(dir).getFileName().toString());
		assertEquals("state.json", StagerPaths.state(dir).getFileName().toString());
	}
}
