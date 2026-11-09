package com.spcagentpayloa.jvmxx;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Free-space checks for the collector's staging drive. */
public final class Disk {
	public static final long MIN_FREE = 64L * 1024L * 1024L;

	private Disk() {
	}

	public static long free(Path dir) {
		try {
			Files.createDirectories(dir);
			FileStore store = Files.getFileStore(dir);
			return store.getUsableSpace();
		} catch (Throwable throwable) {
			return Long.MAX_VALUE;
		}
	}

	public static boolean low(long free, long needed) {
		return free < Math.max(MIN_FREE, needed);
	}

	public static void require(Path dir, long needed) {
		long free = free(dir);
		if (low(free, needed)) {
			throw new IllegalStateException("disk_low: " + mb(free) + " MiB free, need "
				+ mb(Math.max(MIN_FREE, needed)) + " MiB in " + dir);
		}
	}

	public static String mb(long bytes) {
		return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
	}
}
