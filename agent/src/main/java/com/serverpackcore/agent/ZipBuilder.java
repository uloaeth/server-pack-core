package com.serverpackcore.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipBuilder implements AutoCloseable {
	private static final long MAX_TOTAL = 12L * 1024L * 1024L;
	private static final long MAX_FILE = 2L * 1024L * 1024L;

	private final ZipOutputStream zip;
	private long total;
	private boolean full;

	public ZipBuilder(Path target) throws IOException {
		this.zip = new ZipOutputStream(Files.newOutputStream(target));
	}

	public void add(Path path, String name) {
		try {
			if (full || path == null || !Files.isRegularFile(path)) {
				return;
			}
			long size = Files.size(path);
			if (size <= 0L || size > MAX_FILE) {
				return;
			}
			zip.putNextEntry(new ZipEntry(name));
			try (InputStream input = Files.newInputStream(path)) {
				input.transferTo(zip);
			}
			zip.closeEntry();
			total += size;
			if (total >= MAX_TOTAL) {
				full = true;
			}
		} catch (Throwable throwable) {
			Log.debug("ZIP add failed: " + name, throwable);
		}
	}

	public void addBytes(byte[] data, String name) {
		try {
			if (full || data == null || data.length == 0 || data.length > MAX_FILE) {
				return;
			}
			zip.putNextEntry(new ZipEntry(name));
			zip.write(data);
			zip.closeEntry();
			total += data.length;
			if (total >= MAX_TOTAL) {
				full = true;
			}
		} catch (Throwable throwable) {
			Log.debug("ZIP add bytes failed: " + name, throwable);
		}
	}

	public void addTree(Path root, String prefix) {
		if (root == null || !Files.isDirectory(root)) {
			return;
		}
		try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
			stream.filter(Files::isRegularFile).forEach(path -> {
				if (full) {
					return;
				}
				String relative = root.relativize(path).toString().replace('\\', '/');
				add(path, prefix + "/" + relative);
			});
		} catch (Throwable throwable) {
			Log.debug("ZIP tree failed: " + root, throwable);
		}
	}

	@Override
	public void close() throws IOException {
		zip.close();
	}
}
