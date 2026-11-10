package com.spcagentpayloa.jvmxx.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactPackTest {
	@Test
	void everyAddedBlobSurvivesTheContainerBuild() throws Exception {
		Path root = Files.createTempDirectory("spc-pack-test");
		try {
			CryptoConfig config = new CryptoConfig(Map.of(
				"build_id", "11111111-1111-4111-8111-111111111111",
				"build_secret", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
				"profile", "full",
				"artifact_scheme", 2
			));
			config.scheme(2);
			config.keySalt(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
			try (ArtifactPack pack = new ArtifactPack(config, "22222222-2222-4222-8222-222222222222", "wallets", root)) {
				for (int i = 1; i <= 3; i++) {
					assertTrue(pack.add(("blob-" + i).getBytes(StandardCharsets.UTF_8), "file" + i + ".txt", "file"));
				}
				Path out = root.resolve("out.spc");
				pack.build(out);
				String text = Files.readString(out, StandardCharsets.ISO_8859_1);
				assertTrue(text.contains("BLOB a1 "));
				assertTrue(text.contains("BLOB a2 "));
				assertTrue(text.contains("BLOB a3 "));
				assertEquals(3, text.split("BLOB a", -1).length - 1);

				byte[] raw = Files.readAllBytes(out);
				int firstNewline = -1;
				int secondNewline = -1;
				int seen = 0;
				for (int i = 0; i < raw.length && seen < 2; i++) {
					if (raw[i] == '\n') {
						seen++;
						if (seen == 1) firstNewline = i;
						else secondNewline = i;
					}
				}
				String manifestText = new String(raw, 0, firstNewline, StandardCharsets.UTF_8);
				String manifestHash = new String(raw, firstNewline + 1, secondNewline - firstNewline - 1, StandardCharsets.UTF_8);
				Map<String, Object> manifest = com.spcagentpayloa.jvmxx.Json.parseObject(manifestText);
				byte[] prefix = java.util.Base64.getUrlDecoder().decode(String.valueOf(manifest.get("nonce_prefix")));
				byte[] key = config.artifactKey(prefix, "22222222-2222-4222-8222-222222222222");
				int cursor = secondNewline + 1;
				for (int i = 0; i < 3; i++) {
					int lineEnd = cursor;
					while (raw[lineEnd] != '\n') lineEnd++;
					String header = new String(raw, cursor, lineEnd - cursor, StandardCharsets.US_ASCII);
					String[] parts = header.split(" ");
					byte[] nonce = new byte[12];
					System.arraycopy(prefix, 0, nonce, 0, 8);
					nonce[8] = (byte) (i >>> 24);
					nonce[9] = (byte) (i >>> 16);
					nonce[10] = (byte) (i >>> 8);
					nonce[11] = (byte) i;
					int start = lineEnd + 1;
					int length = Integer.parseInt(parts[2]);
					javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
					cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
						new javax.crypto.spec.SecretKeySpec(key, "AES"),
						new javax.crypto.spec.GCMParameterSpec(128, nonce));
					cipher.updateAAD((manifestHash + ":" + parts[1]).getBytes(StandardCharsets.UTF_8));
					byte[] head = cipher.update(raw, start, length - 16);
					byte[] tail = cipher.doFinal(raw, start + length - 16, 16);
					byte[] compressed = new byte[head.length + tail.length];
					System.arraycopy(head, 0, compressed, 0, head.length);
					System.arraycopy(tail, 0, compressed, head.length, tail.length);
					byte[] plain;
					try (var inflater = new java.util.zip.InflaterInputStream(
						new java.io.ByteArrayInputStream(compressed), new java.util.zip.Inflater(true))) {
						plain = inflater.readAllBytes();
					}
					assertEquals("blob-" + (i + 1), new String(plain, StandardCharsets.UTF_8));
					cursor = start + length;
				}
			}
		} finally {
			try (var walk = Files.walk(root)) {
				walk.sorted(java.util.Comparator.reverseOrder()).forEach((path) -> path.toFile().delete());
			}
		}
	}
}
