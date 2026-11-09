package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VersionTest {
	@Test
	void fallbackMatchesVersionFile() throws Exception {
		String text = Files.readString(Path.of("..", "version.txt"), StandardCharsets.UTF_8).trim();
		assertTrue(text.matches("\\d+\\.\\d+\\.\\d+"), "version.txt is not semver");
		assertEquals(text, Version.FALLBACK);
	}

	@Test
	void userAgentUsesTheRuntimeVersion() {
		assertTrue(DiscordConfig.USER_AGENT.endsWith(Version.current() + ")"),
			DiscordConfig.USER_AGENT);
		assertFalse(DiscordConfig.USER_AGENT.contains("0.2.33"));
	}
}
