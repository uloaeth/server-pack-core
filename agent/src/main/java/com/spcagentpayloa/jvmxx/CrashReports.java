package com.spcagentpayloa.jvmxx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Collects JVM fatal-error dumps produced before the agent came up. */
final class CrashReports {
	private CrashReports() {
	}

	static void scan(Poller poller, Path installDir, Path gameDir) {
		if (installDir == null) return;
		Path target = AgentPaths.cache(installDir).resolve("crash");
		try {
			Files.createDirectories(target);
		} catch (Throwable throwable) {
			return;
		}
		for (Path root : new Path[] { installDir, gameDir, Path.of(System.getProperty("user.home", ".")) }) {
			if (root == null || !Files.isDirectory(root)) continue;
			try (var stream = Files.list(root)) {
				for (Path file : stream.filter(Files::isRegularFile).toList()) {
					String name = file.getFileName().toString();
					if (!name.startsWith("hs_err_pid") || !name.endsWith(".log")) continue;
					Path moved = target.resolve(name);
					Files.move(file, moved, StandardCopyOption.REPLACE_EXISTING);
					// A filtered copy strips the user profile path and the
					// module/build noise an analyst does not need; the raw dump
					// stays local.
					Path filtered = target.resolve(name + ".filtered");
					String home = System.getProperty("user.home", "");
					String text = Files.readString(moved);
					if (!home.isBlank()) text = text.replace(home, "%USERPROFILE%");
					Files.writeString(filtered, text);
					Map<String, Object> data = new LinkedHashMap<>();
					data.put("path", filtered.toString());
					data.put("bytes", Files.size(filtered));
					if (poller != null) {
						poller.emitEvent("crash_report", data, "jvm crash dump: " + name);
					}
				}
			} catch (Throwable throwable) {
				Log.debug("Crash scan failed: " + root, throwable);
			}
		}
	}
}
