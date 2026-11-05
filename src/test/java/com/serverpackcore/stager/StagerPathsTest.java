package com.serverpackcore.stager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
