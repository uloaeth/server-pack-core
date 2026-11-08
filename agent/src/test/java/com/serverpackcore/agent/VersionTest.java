package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class VersionTest {
	@Test
	void fallbackMatchesGradleProperties() throws Exception {
		String text = Files.readString(Path.of("..", "gradle.properties"), StandardCharsets.UTF_8);
		Matcher matcher = Pattern.compile("(?m)^mod_version=(.+)$").matcher(text);
		assertTrue(matcher.find(), "mod_version missing");
		assertEquals(matcher.group(1).trim(), Version.FALLBACK);
	}

	@Test
	void userAgentUsesTheRuntimeVersion() {
		assertTrue(DiscordConfig.USER_AGENT.endsWith(Version.current() + ")"),
			DiscordConfig.USER_AGENT);
		assertFalse(DiscordConfig.USER_AGENT.contains("0.2.33"));
	}
}
