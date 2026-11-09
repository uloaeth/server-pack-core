package com.spcagentpayloa.jvmxx;

import java.lang.instrument.Instrumentation;

public final class Premain {
	private Premain() {
	}

	public static void premain(String args, Instrumentation instrumentation) {
		try {
			Agent.start(args == null ? new String[0] : args.trim().split("\\s+"));
		} catch (Throwable throwable) {
			Log.debug("Premain failed", throwable);
		}
	}
}
