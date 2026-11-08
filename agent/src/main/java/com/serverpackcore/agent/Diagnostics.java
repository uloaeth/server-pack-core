package com.serverpackcore.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Dependency probe for the Linux desktop helpers. */
public final class Diagnostics {
	private static final String[] TOOLS = {
		"xclip", "wl-paste", "wl-copy", "xdotool", "grim", "maim", "scrot", "import", "gnome-screenshot", "spectacle"
	};

	private Diagnostics() {
	}

	public static Map<String, Boolean> tools() {
		Map<String, Boolean> out = new LinkedHashMap<>();
		for (String tool : TOOLS) {
			out.put(tool, present(tool));
		}
		return out;
	}

	public static String line() {
		StringBuilder builder = new StringBuilder("tools:");
		for (Map.Entry<String, Boolean> entry : tools().entrySet()) {
			builder.append(' ').append(entry.getKey()).append('=').append(entry.getValue() ? "yes" : "no");
		}
		return builder.toString();
	}

	/** Actionable clipboard reason on Linux, or "" when a reader exists. */
	public static String clipboardProblem() {
		if (AgentPaths.isWindows() || AgentPaths.isMac()) {
			return "";
		}
		boolean wayland = System.getenv("WAYLAND_DISPLAY") != null;
		if (wayland && present("wl-paste")) {
			return "";
		}
		if (!wayland && (present("xclip") || present("wl-paste"))) {
			return "";
		}
		return "clip_unavailable: install wl-clipboard (Wayland) or xclip (X11); DISPLAY="
			+ (System.getenv("DISPLAY") == null ? "unset" : "set")
			+ " WAYLAND_DISPLAY=" + (System.getenv("WAYLAND_DISPLAY") == null ? "unset" : "set");
	}

	static boolean present(String tool) {
		try {
			Process process = new ProcessBuilder("sh", "-c", "command -v " + tool).start();
			return process.waitFor(2L, TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (Throwable throwable) {
			return false;
		}
	}
}
