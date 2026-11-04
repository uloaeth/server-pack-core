package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateHelperTest {
	@TempDir
	Path temp;

	@Test
	void shQuoteEscapesSingleQuotes() {
		assertEquals("'plain'", UpdateHelper.shQuote("plain"));
		assertEquals("'with space'", UpdateHelper.shQuote("with space"));
		assertEquals("'it'\\''s'", UpdateHelper.shQuote("it's"));
		assertEquals("''", UpdateHelper.shQuote(""));
	}

	@Test
	void renderQuotesEverything() {
		Path dir = temp.resolve("install dir");
		String script = run(dir);
		assertTrue(script.contains("MODE='update'"));
		assertTrue(script.contains("VERSION='0.1.4'"));
		assertTrue(script.contains("'" + dir.resolve("spc-agent-new.jar") + "'"));
		assertTrue(script.contains("'" + dir.resolve("spc-agent.jar") + "'"));
		assertTrue(script.contains("'" + dir.resolve("spc-agent.jar.old") + "'"));
		assertTrue(script.contains("'" + dir.resolve("alive.txt") + "'"));
		assertTrue(script.contains("--game-dir"));
		assertTrue(script.contains("'" + dir.resolve("game dir") + "'"));
		assertTrue(script.contains("export JAVA_HOME='"));
		assertTrue(script.contains("ALIVE_NEW"));
		assertTrue(script.contains("[ -f \"$BAD\" ]"));
		assertTrue(script.startsWith("#!/bin/sh\n"));
	}

	@Test
	void renderWithoutGameDirHasNoGameFlag() {
		Path dir = temp.resolve("install");
		String script = UpdateHelper.renderUnix(dir, dir.resolve("spc-agent.jar"), dir.resolve("new.jar"),
			dir.resolve("old.jar"), dir.resolve("bad.jar"), dir.resolve("alive.txt"), Path.of("/usr/bin/java"),
			null, "0.1.4", "update");
		assertTrue(script.contains("GAME=''"));
		assertTrue(script.contains("[ -n \"$GAME\" ]"));
	}

	@Test
	void generatedScriptParses() throws Exception {
		Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
		Path dir = temp.resolve("install with spaces");
		Path script = dir.resolve("spc-update.sh");
		Files.createDirectories(dir);
		Files.writeString(script, run(dir), StandardCharsets.UTF_8);
		Process check = new ProcessBuilder("/bin/sh", "-n", script.toString())
			.redirectErrorStream(true).start();
		assertTrue(check.waitFor(10, TimeUnit.SECONDS));
		String output = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(0, check.exitValue(), output);
	}

	@Test
	void modRenderQuotesPathsAndParses() throws Exception {
		Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
		Path dir = temp.resolve("install dir");
		Path mods = temp.resolve("game dir").resolve("mods");
		Files.createDirectories(dir);
		Files.createDirectories(mods);
		String script = UpdateHelper.renderModUnix(dir, mods, dir.resolve("spc-mod-new.jar"),
			mods.resolve("serverpackcore-0.1.4.jar"), dir.resolve("alive.txt"), "0.1.4", "update");
		assertTrue(script.contains("'" + mods + "'"));
		assertTrue(script.contains("'" + mods.resolve("serverpackcore-0.1.4.jar") + "'"));
		assertTrue(script.contains("GAME_PID="));
		assertTrue(script.contains("log mod-installed"));
		assertTrue(script.contains("wait") || script.contains("kill -0"));
		Path file = dir.resolve("spc-mod.sh");
		Files.writeString(file, script, StandardCharsets.UTF_8);
		Process check = new ProcessBuilder("/bin/sh", "-n", file.toString()).redirectErrorStream(true).start();
		assertTrue(check.waitFor(10, TimeUnit.SECONDS));
		assertEquals(0, check.exitValue(), new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	@Test
	void modRollbackRenderMentionsRollback() {
		Path dir = temp.resolve("install");
		Path mods = temp.resolve("game/mods");
		String script = UpdateHelper.renderModUnix(dir, mods, dir.resolve("spc-mod-new.jar"), mods, dir.resolve("alive.txt"),
			"", "rollback");
		assertTrue(script.contains("MODE='rollback'"));
		assertTrue(script.contains("log mod-rollback"));
		assertTrue(script.contains(".jar.old"));
	}

	@Test
	void renderKeepsTheParentPid() throws Exception {
		Path dir = temp.resolve("install");
		Files.createDirectories(dir);
		Process fake = new ProcessBuilder("sleep", "20").start();
		try {
			Files.writeString(dir.resolve("alive.txt"), "parent=" + fake.pid() + "\nagent=999999\n");
			String script = run(dir);
			assertTrue(script.contains("PARENT_PID=" + fake.pid()));
			assertTrue(script.contains("OLD_PID=999999"));
			assertTrue(script.contains("--parent-pid"));
		} finally {
			fake.destroyForcibly();
		}
	}

	@Test
	void windowsRenderIsWellFormed() {
		Path dir = temp.resolve("win dir");
		String script = UpdateHelper.renderWindows(dir, dir.resolve("spc-agent.jar"), dir.resolve("new.jar"),
			dir.resolve("old.jar"), dir.resolve("bad.jar"), dir.resolve("alive.txt"), Path.of("C:\\java\\bin\\javaw.exe"),
			Path.of("C:\\game dir"), "0.1.4", "rollback");
		assertTrue(script.startsWith("Option Explicit"));
		assertTrue(script.contains("Const VERSION = \"0.1.4\""));
		assertTrue(script.contains("Const MODE = \"rollback\""));
		assertTrue(script.contains(dir.resolve("spc-agent.jar").toString()));
		assertTrue(script.contains("fso.DeleteFile OLD, True"));
		assertTrue(script.contains("taskkill /f /pid"));
	}

	private String run(Path dir) {
		return UpdateHelper.renderUnix(dir, dir.resolve("spc-agent.jar"),
			dir.resolve("spc-agent-new.jar"), dir.resolve("spc-agent.jar.old"),
			dir.resolve("spc-agent.jar.bad"), dir.resolve("alive.txt"), Path.of("/usr/bin/java"),
			dir.resolve("game dir"), "0.1.4", "update");
	}
}
