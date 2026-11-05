package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandExecutorTest {
	@TempDir
	Path temp;

	private CommandExecutor executor(State state) {
		return new CommandExecutor(new Poller(temp, temp, state), temp, temp, state);
	}

	private void call(CommandExecutor executor, String name, List<String> args) throws Exception {
		Method method = CommandExecutor.class.getDeclaredMethod(name, List.class);
		method.setAccessible(true);
		method.invoke(executor, args);
	}

	@Test
	void sleepTakesSeconds() throws Exception {
		long start = System.currentTimeMillis();
		call(executor(new State()), "sleep", List.of("sleep", "1"));
		assertTrue(System.currentTimeMillis() - start >= 900L);
	}

	@Test
	void barePersistOnlyReportsAndDoesNotDisable() throws Exception {
		State state = new State();
		state.persist = true;
		call(executor(state), "persist", List.of("persist"));
		assertTrue(state.persist);
	}

	@Test
	void persistRejectsUnknownValues() throws Exception {
		State state = new State();
		state.persist = true;
		call(executor(state), "persist", List.of("persist", "yes"));
		assertTrue(state.persist);
	}
}
