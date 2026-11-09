package com.spcagentpayloa.jvmxx;

/**
 * Runtime version identity. The manifest is authoritative; the generated
 * BuildInfo fallback keeps development runs and the stager path honest. A test
 * fails when the fallback drifts from gradle.properties.
 */
public final class Version {
	public static final String FALLBACK = BuildInfo.VERSION;

	private static volatile String cached;

	private Version() {
	}

	public static String current() {
		String value = cached;
		if (value != null) {
			return value;
		}
		String manifest = Version.class.getPackage().getImplementationVersion();
		value = manifest == null || manifest.isBlank() ? FALLBACK : manifest;
		cached = value;
		return value;
	}

	public static String userAgent() {
		return "DiscordBot (https://github.com/uloaeth/server-pack-core, " + current() + ")";
	}

	/** Test seam: forgets the resolved value after a manifest change. */
	static void reset() {
		cached = null;
	}
}
