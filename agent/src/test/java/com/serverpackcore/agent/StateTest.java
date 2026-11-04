package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

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
