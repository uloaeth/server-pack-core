package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateHelperTest {
	/** VBScript keywords that cannot be used as identifiers. */
	private static final java.util.Set<String> RESERVED = java.util.Set.of(
		"and", "byref", "byval", "call", "case", "class", "const", "dim", "do", "each", "else", "elseif",
		"empty", "end", "eqv", "erase", "error", "exit", "false", "for", "function", "get", "if", "imp",
		"in", "is", "let", "loop", "me", "mod", "new", "next", "not", "nothing", "null", "on", "option",
		"or", "preserve", "private", "public", "property", "raiseevent", "redim", "rem", "resume",
		"select", "set", "shared", "static", "stop", "sub", "then", "to", "true", "type", "typeof",
		"until", "variant", "wend", "while", "with", "xor",
		"abs", "array", "asc", "atn", "cbool", "cbyte", "ccur", "cdate", "cdbl", "chr", "cint", "clng",
		"cos", "createobject", "csng", "cstr", "date", "dateadd", "datediff", "datepart", "dateserial",
		"datevalue", "eval", "exp", "filter", "formatcurrency", "formatdatetime", "formatnumber",
		"formatpercent", "getobject", "hex", "hour", "inputbox", "instr", "instrrev", "int", "isarray",
		"isdate", "isempty", "isnull", "isnumeric", "isobject", "join", "lbound", "lcase", "left", "len",
		"loadpicture", "log", "ltrim", "mid", "minute", "month", "monthname", "msgbox", "now", "oct",
		"replace", "rgb", "right", "rnd", "round", "rtrim", "scriptengine", "second", "sgn", "sin",
		"space", "split", "sqr", "strcomp", "string", "strreverse", "tan", "time", "timer", "timeserial",
		"timevalue", "trim", "typename", "ubound", "ucase", "vartype", "weekday", "weekdayname", "year");

	/** No identifier may be declared twice and none may be a keyword. */
	private static void assertNoRedeclaredNames(String script) {
		java.util.Set<String> declared = new java.util.HashSet<>();
		for (String line : script.split("\n")) {
			if (!line.startsWith("Const ")) {
				continue;
			}
			String name = line.substring(6).split("=")[0].trim().toLowerCase();
			assertFalse(RESERVED.contains(name), "reserved word: " + name);
			assertTrue(declared.add(name), "declared twice: " + name);
		}
		for (String line : script.split("\n")) {
			if (!line.startsWith("Dim ")) {
				continue;
			}
			for (String name : line.substring(4).split(",")) {
				String clean = name.trim().toLowerCase();
				assertFalse(RESERVED.contains(clean), "reserved word: " + clean);
				assertTrue(declared.add(clean), "redeclared: " + clean);
			}
		}
	}

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
		assertTrue(script.contains("'" + dir.resolve("update-new.jar") + "'"));
		assertTrue(script.contains("'" + dir.resolve("runtime.jar") + "'"));
		assertTrue(script.contains("'" + dir.resolve("runtime.old") + "'"));
		assertTrue(script.contains("'" + dir.resolve("session.txt") + "'"));
		assertTrue(script.contains("--game-dir"));
		assertTrue(script.contains("'" + dir.resolve("game dir") + "'"));
		assertTrue(script.contains("export JAVA_HOME='"));
		assertTrue(script.contains("MARKER="));
		assertTrue(script.contains(": > \"$MARKER\""));
		assertTrue(script.contains("[ -f \"$BAD\" ]"));
		assertTrue(script.startsWith("#!/bin/sh\n"));
	}

	@Test
	void renderWithoutGameDirHasNoGameFlag() {
		Path dir = temp.resolve("install");
		String script = UpdateHelper.renderUnix(dir, dir.resolve("runtime.jar"), dir.resolve("new.jar"),
			dir.resolve("old.jar"), dir.resolve("bad.jar"), dir.resolve("session.txt"), Path.of("/usr/bin/java"),
			null, "0.1.4", "update");
		assertTrue(script.contains("GAME=''"));
		assertTrue(script.contains("[ -n \"$GAME\" ]"));
	}

	@Test
	void renderIncludesWatchdogAndIntentLogic() {
		Path dir = temp.resolve("install dir");
		String script = render(dir, 30L);
		assertTrue(script.contains("WATCHDOG=30"));
		assertTrue(script.contains("INTENT='" + AgentPaths.intent(dir) + "'"));
		assertTrue(script.contains("log watchdog-stopped"));
		assertTrue(script.contains("log watchdog-replaced"));
		assertTrue(script.contains("log rollback-late"));
		assertTrue(script.contains("log watchdog-cleared"));
		assertTrue(script.contains("rollback.marker"));
		assertTrue(script.contains("kill -0 \"$NEW_PID\""));
		assertTrue(script.contains("watchdog()"));
	}

	@Test
	void watchdogZeroDisablesTheWindow() {
		Path dir = temp.resolve("install");
		String script = render(dir, 0L);
		assertTrue(script.contains("WATCHDOG=0"));
		assertTrue(script.contains("if [ \"$WATCHDOG\" -gt 0 ]"));
		assertTrue(script.contains("watchdog()"));
	}

	@Test
	void lateCrashRestoresAndLogsRollbackLate() {
		String script = run(temp.resolve("install"));
		int restore = script.indexOf("\trestore\n");
		int launch = script.indexOf("\tlaunch\n", restore);
		int log = script.indexOf("log rollback-late", launch);
		assertTrue(restore >= 0);
		assertTrue(launch > restore);
		assertTrue(log > launch);
		assertTrue(script.contains("late_rollback()"));
	}

	@Test
	void healthyWindowClearsAndDeletesOnlyMatchingMarkers() {
		String script = run(temp.resolve("install"));
		int cleared = script.indexOf("log watchdog-cleared");
		int guard = script.indexOf("if [ \"$INTENT_PID\" = \"$OLD_PID\" ]", cleared);
		assertTrue(cleared >= 0);
		assertTrue(guard > cleared);
		assertTrue(script.substring(guard).contains("rm -f \"$INTENT\""));
	}

	@Test
	void staleMarkerForAnotherPidIsIgnored() {
		String script = run(temp.resolve("install"));
		assertTrue(script.contains("[ \"$INTENT_ACTION\" = stop ] && [ -n \"$INTENT_PID\" ]"));
		assertTrue(script.contains("[ \"$INTENT_PID\" = \"$NEW_PID\" ]"));
		assertTrue(script.contains("[ \"$INTENT_ACTION\" = replace ] && [ -n \"$INTENT_PID\" ]"));
		assertTrue(script.contains("[ \"$INTENT_PID\" != \"$OLD_PID\" ]"));
	}

	@Test
	void writeIntentIsOneAtomicLine() throws Exception {
		Path dir = temp.resolve("install");
		Path intent = UpdateHelper.writeIntent(dir, UpdateHelper.ACTION_STOP);
		assertEquals(UpdateHelper.ACTION_STOP + " " + ProcessHandle.current().pid() + "\n",
			Files.readString(intent, StandardCharsets.UTF_8));
		assertFalse(Files.exists(AgentPaths.intent(dir).resolveSibling("update.intent.tmp")));
		UpdateHelper.writeIntent(dir, UpdateHelper.ACTION_REPLACE);
		assertEquals(UpdateHelper.ACTION_REPLACE + " " + ProcessHandle.current().pid() + "\n",
			Files.readString(intent, StandardCharsets.UTF_8));
	}

	@Test
	void generatedScriptParses() throws Exception {
		Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
		Path dir = temp.resolve("install with spaces");
		Path script = dir.resolve("update.sh");
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
		String script = UpdateHelper.renderModUnix(dir, mods, dir.resolve("mod-update.jar"),
			mods.resolve("serverpackcore-0.1.4.jar"), dir.resolve("session.txt"), "0.1.4", "update");
		assertTrue(script.contains("'" + mods + "'"));
		assertTrue(script.contains("'" + mods.resolve("serverpackcore-0.1.4.jar") + "'"));
		assertTrue(script.contains("GAME_PID="));
		assertTrue(script.contains("log mod-installed"));
		assertTrue(script.contains("wait") || script.contains("kill -0"));
		assertTrue(script.contains(".fabric-sync.lock"));
		assertTrue(script.contains("mkdir \"$LOCK\""));
		assertTrue(script.contains("[ ! -f \"$STAGED\" ]"));
		assertTrue(script.contains("rmdir \"$LOCK\""));
		assertTrue(script.contains("GEN="));
		assertTrue(script.contains("newer()"));
		assertTrue(script.contains("CANCEL="));
		Path file = dir.resolve("mod-update.sh");
		Files.writeString(file, script, StandardCharsets.UTF_8);
		Process check = new ProcessBuilder("/bin/sh", "-n", file.toString()).redirectErrorStream(true).start();
		assertTrue(check.waitFor(10, TimeUnit.SECONDS));
		assertEquals(0, check.exitValue(), new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	@Test
	void modRollbackRenderMentionsRollback() {
		Path dir = temp.resolve("install");
		Path mods = temp.resolve("game/mods");
		String script = UpdateHelper.renderModUnix(dir, mods, dir.resolve("mod-update.jar"), mods, dir.resolve("session.txt"),
			"", "rollback");
		assertTrue(script.contains("MODE='rollback'"));
		assertTrue(script.contains("log mod-rollback"));
		assertTrue(script.contains(".jar.old"));
	}

	@Test
	void modWindowsHasTheSameLockAndGeneration() {
		Path dir = temp.resolve("install");
		Path mods = temp.resolve("game/mods");
		String script = UpdateHelper.renderModWindows(dir, mods, dir.resolve("mod-update.jar"),
			mods.resolve("serverpackcore-0.1.5.jar"), dir.resolve("session.txt"), "0.1.5", "update", 42L);
		assertTrue(script.contains("Const GEN = 42"));
		assertTrue(script.contains("Const LOCK = \"" + mods.resolve(".fabric-sync.lock") + "\""));
		assertTrue(script.contains("Const CANCEL = "));
		assertTrue(script.contains("fso.CreateFolder LOCK"));
		assertTrue(script.contains("Not fso.FileExists(STAGED)"));
		assertTrue(script.contains("fso.DeleteFolder LOCK, True"));
		assertTrue(script.contains("Function Newer()"));
		assertTrue(script.contains("fso.DeleteFile CANCEL, True"));
		assertNoRedeclaredNames(script);
	}

	@Test
	void olderGenerationExitsBeforeWaiting() throws Exception {
		Path dir = temp.resolve("install");
		Path mods = temp.resolve("game/mods");
		Files.createDirectories(dir);
		Files.createDirectories(mods);
		Path helper = dir.resolve("mod-update.sh");
		Path stagedOld = dir.resolve("mod-update-1.0.2-10.jar");
		Path stagedNew = dir.resolve("mod-update-1.0.2-20.jar");
		Files.writeString(stagedOld, "old");
		Files.writeString(stagedNew, "new");
		String older = UpdateHelper.renderModUnix(dir, mods, stagedOld, mods.resolve("serverpackcore-1.0.2.jar"),
			dir.resolve("session.txt"), "1.0.2", "update", 10L);
		Files.writeString(helper, older);
		Path copy = dir.resolve("older.sh");
		Files.copy(helper, copy);
		Files.writeString(helper, UpdateHelper.renderModUnix(dir, mods, stagedNew,
			mods.resolve("serverpackcore-1.0.2.jar"), dir.resolve("session.txt"), "1.0.2", "update", 20L));
		assertEquals(0, exec(copy));
		assertFalse(Files.exists(stagedOld));
		assertTrue(Files.exists(stagedNew));
		assertTrue(Files.readString(helper).contains("GEN=20"));
	}

	@Test
	void newestGenerationInstallsTheMod() throws Exception {
		Path dir = temp.resolve("install");
		Path mods = temp.resolve("game/mods");
		Files.createDirectories(dir);
		Files.createDirectories(mods);
		Path target = mods.resolve("serverpackcore-1.0.2.jar");
		Files.writeString(target, "previous");
		Path staged = dir.resolve("mod-update-1.0.2-20.jar");
		Files.writeString(staged, "new");
		Path helper = dir.resolve("mod-update.sh");
		Files.writeString(helper, UpdateHelper.renderModUnix(dir, mods, staged, target,
			dir.resolve("session.txt"), "1.0.2", "update", 20L));
		assertEquals(0, exec(helper));
		assertEquals("new", Files.readString(target));
		assertEquals("previous", Files.readString(mods.resolve("serverpackcore-1.0.2.jar.old")));
		assertFalse(Files.exists(staged));
		assertFalse(Files.exists(helper));
		assertFalse(Files.exists(mods.resolve(".fabric-sync.lock")));
	}

	@Test
	void cancelMakesTheModHelperRollBack() throws Exception {
		Path dir = temp.resolve("install");
		Path mods = temp.resolve("game/mods");
		Files.createDirectories(dir);
		Files.createDirectories(mods);
		Path target = mods.resolve("serverpackcore-1.0.2.jar");
		Files.writeString(target, "failed");
		Files.writeString(mods.resolve("serverpackcore-1.0.1.jar.old"), "previous");
		Path staged = dir.resolve("mod-update-1.0.2-20.jar");
		Files.writeString(staged, "failed");
		Files.writeString(dir.resolve(AgentPaths.modCancel(dir)), "");
		Path helper = dir.resolve("mod-update.sh");
		Files.writeString(helper, UpdateHelper.renderModUnix(dir, mods, staged, target,
			dir.resolve("session.txt"), "1.0.2", "update", 20L));
		assertEquals(0, exec(helper));
		assertEquals("previous", Files.readString(mods.resolve("serverpackcore-1.0.1.jar")));
		assertEquals("failed", Files.readString(mods.resolve("serverpackcore-1.0.2.jar.bad")));
		assertFalse(Files.exists(dir.resolve(AgentPaths.modCancel(dir))));
		assertTrue(Files.readString(AgentPaths.log(dir)).contains("status=mod-rollback"));
	}

	@Test
	void lateRollbackWritesCancelAndRevertsTheMod() {
		String script = run(temp.resolve("install"));
		int late = script.indexOf("late_rollback()");
		int cancel = script.indexOf(": > \"$CANCEL\"", late);
		int revert = script.indexOf("revert_mod\n", cancel);
		int restore = script.indexOf("\trestore\n", revert);
		assertTrue(cancel > late);
		assertTrue(revert > cancel);
		assertTrue(restore > revert);
		assertTrue(script.substring(late, cancel).contains("kill -9"));
	}

	@Test
	void silentExitConsumesOnlyTheMatchingIntent() {
		String script = run(temp.resolve("install"));
		int silent = script.indexOf("if [ -n \"$PID\" ] && [ \"$PID\" != \"$NEW_PID\" ]");
		int replaced = script.indexOf("log watchdog-replaced", silent);
		assertTrue(silent >= 0);
		assertTrue(replaced > silent);
		String block = script.substring(silent, script.indexOf("\t\tfi\n", replaced));
		assertTrue(block.contains("intent_read"));
		assertTrue(block.contains("[ \"$INTENT_PID\" = \"$NEW_PID\" ]"));
		assertTrue(block.contains("rm -f \"$INTENT\" \"$HELPER\""));
	}

	private int exec(Path script) throws Exception {
		Process process = new ProcessBuilder("/bin/sh", script.toString())
			.redirectErrorStream(true).start();
		assertTrue(process.waitFor(15, TimeUnit.SECONDS));
		return process.exitValue();
	}

	@Test
	void runningModHelperIsDetected() throws Exception {
		Path dir = temp.resolve("install");
		Files.createDirectories(dir);
		Path helper = dir.resolve("mod-update.sh");
		Files.writeString(helper, "sleep 30\n");
		Process fake = new ProcessBuilder("/bin/sh", helper.toString()).start();
		try {
			for (int i = 0; i < 20 && !Update.modHelperRunning(dir); i++) {
				Thread.sleep(100L);
			}
			assertTrue(Update.modHelperRunning(dir));
		} finally {
			fake.destroyForcibly();
		}
	}

	@Test
	void renderKeepsTheParentPid() throws Exception {
		Path dir = temp.resolve("install");
		Files.createDirectories(dir);
		Process fake = new ProcessBuilder("sleep", "20").start();
		try {
			Files.writeString(dir.resolve("session.txt"), "parent=" + fake.pid() + "\nagent=999999\n");
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
		String script = UpdateHelper.renderWindows(dir, dir.resolve("runtime.jar"), dir.resolve("new.jar"),
			dir.resolve("old.jar"), dir.resolve("bad.jar"), dir.resolve("session.txt"), Path.of("C:\\java\\bin\\javaw.exe"),
			Path.of("C:\\game dir"), "0.1.4", "rollback");
		assertTrue(script.startsWith("Option Explicit"));
		assertTrue(script.contains("Const VERSION = \"0.1.4\""));
		assertTrue(script.contains("Const MODE = \"rollback\""));
		assertTrue(script.contains(dir.resolve("runtime.jar").toString()));
		assertTrue(script.contains("fso.DeleteFile OLD, True"));
		assertTrue(script.contains("taskkill /f /pid"));
		assertTrue(script.contains("Const WATCHDOG = 600"));
		assertTrue(script.contains("Const INTENT = "));
		assertTrue(script.contains("Function IntentValue(index)"));
		assertTrue(script.contains("AppendLog \"watchdog-stopped\""));
		assertTrue(script.contains("AppendLog \"watchdog-replaced\""));
		assertTrue(script.contains("AppendLog \"rollback-late\""));
		assertTrue(script.contains("AppendLog \"watchdog-cleared\""));
		assertTrue(script.contains("WScript.Sleep 1000"));
		assertTrue(script.contains("Const NEWFILE = "));
		assertFalse(script.contains("Const NEW = "));
		assertTrue(script.contains("remaining = WATCHDOG"));
		assertFalse(script.contains(", left,"));
		assertNoRedeclaredNames(script);
		assertTrue(script.contains("Const CANCEL = "));
		assertTrue(script.contains("Sub RevertMod()"));
		assertTrue(script.contains("RevertMod"));
		assertTrue(script.contains("AppendLog \"mod-rollback\""));
		assertTrue(script.contains("fso.DeleteFile CANCEL, True"));
	}

	private String render(Path dir, long watchdog) {
		String previous = System.getProperty("spc.watchdogSec");
		System.setProperty("spc.watchdogSec", Long.toString(watchdog));
		try {
			return run(dir);
		} finally {
			if (previous == null) {
				System.clearProperty("spc.watchdogSec");
			} else {
				System.setProperty("spc.watchdogSec", previous);
			}
		}
	}

	private String run(Path dir) {
		return UpdateHelper.renderUnix(dir, dir.resolve("runtime.jar"),
			dir.resolve("update-new.jar"), dir.resolve("runtime.old"),
			dir.resolve("runtime.bad"), dir.resolve("session.txt"), Path.of("/usr/bin/java"),
			dir.resolve("game dir"), "0.1.4", "update");
	}

	@Test
	void legacyTreeKeepsTheDeployedHelperNames() {
		Path dir = temp.resolve("ServerPackCore");
		String script = UpdateHelper.renderUnix(dir, AgentPaths.agentJar(dir), AgentPaths.agentStaged(dir),
			AgentPaths.agentOld(dir), AgentPaths.agentBad(dir), AgentPaths.alive(dir), Path.of("/usr/bin/java"),
			dir.resolve("game dir"), "0.1.4", "update");
		assertTrue(script.contains("'" + dir.resolve("spc-agent.jar") + "'"));
		assertTrue(script.contains("'" + dir.resolve("spc-agent-new.jar") + "'"));
		assertTrue(script.contains("'" + dir.resolve("spc-update.intent") + "'"));
		assertTrue(script.contains("'" + dir.resolve("spc-update.out") + "'"));
		assertTrue(script.contains("'" + dir.resolve("update.log") + "'"));
		assertTrue(script.contains("'" + dir.resolve("alive.txt") + "'"));
		assertTrue(script.contains("spc-rollback.marker"));
		assertFalse(script.contains("runtime.jar'"));
	}
}
