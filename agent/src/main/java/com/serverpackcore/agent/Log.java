package com.serverpackcore.agent;

public final class Log {
	private static final boolean DEBUG = Boolean.getBoolean("spc.debug");

	private Log() {
	}

	public static void debug(String message) {
		if (DEBUG) {
			System.err.println("[spc] " + message);
		}
	}

	public static void debug(String message, Throwable throwable) {
		if (DEBUG) {
			System.err.println("[spc] " + message);
			throwable.printStackTrace(System.err);
		}
	}
}
