package com.serverpackcore.agent.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.serverpackcore.agent.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A clean install with only the stager-extracted config still starts. */
class CryptoConfigTest {
	@TempDir
	Path temp;

	@Test
	void loadsTheExtractedFileBesideTheAgent() throws Exception {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("build_id", "00000000-0000-4000-8000-000000000001");
		map.put("build_secret", Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtil.random(32)));
		map.put("c2_base", "https://c2.example/api/v1");
		map.put("profile", "lite");
		Files.writeString(temp.resolve("crypto-config.json"), Json.stringify(map));

		CryptoConfig config = CryptoConfig.load(temp);
		assertNotNull(config);
		assertEquals("lite", config.full ? "full" : "lite");
		assertEquals(64L * 1024L * 1024L, config.maxArtifact);
	}
}
