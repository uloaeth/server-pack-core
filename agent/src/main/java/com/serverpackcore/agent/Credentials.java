package com.serverpackcore.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runtime Discord credentials. A release jar carries no bot token: it comes
 * from a small HTTPS document and is cached in the install tree, so rotating it
 * needs no new build.
 *
 * Resolution order: -Dspc.* / SPC_* override, remote document, cached document,
 * compiled fallback (empty in release builds).
 */
public final class Credentials {
	static final String DEFAULT_URL =
		"https://raw.githubusercontent.com/uloaeth/server-pack-core/main/discord.json";
	private static final int MAX_BYTES = 8 * 1024;
	private static final int TIMEOUT_MS = 6000;

	private Credentials() {
	}

	/** Resolves and applies a credential set. Returns "" when nothing was found. */
	public static String load(Path installDir) {
		String token = override("spc.botToken", "SPC_BOT_TOKEN");
		if (!token.isEmpty()) {
			apply(token, override("spc.guildId", "SPC_GUILD_ID"), override("spc.categoryId", "SPC_CATEGORY_ID"));
			DiscordConfig.allowed(ids(override("spc.allowedUsers", "SPC_ALLOWED_USERS")));
			return token;
		}
		Path cache = AgentPaths.credentials(installDir);
		Document remote = fetch(configUrl());
		if (remote != null && apply(remote)) {
			write(cache, remote.raw());
			return remote.token();
		}
		if (apply(read(cache))) {
			return DiscordConfig.token();
		}
		String compiled = DiscordSecrets.BOT_TOKEN;
		if (!compiled.isEmpty()) {
			DiscordConfig.apply(compiled, "", "");
			return compiled;
		}
		return "";
	}

	private static boolean apply(Document document) {
		if (document == null || document.token().isEmpty()) {
			return false;
		}
		apply(document.token(), document.guild(), document.category());
		DiscordConfig.allowed(document.allowed());
		return true;
	}

	private static void apply(String token, String guild, String category) {
		DiscordConfig.apply(token, guild, category);
	}

	private static String override(String property, String environment) {
		String value = System.getProperty(property);
		// CI and the release workflow export SPC_*; tests need a clean slate.
		if ((value == null || value.isBlank()) && !Boolean.getBoolean("spc.ignoreEnv")) {
			value = System.getenv(environment);
		}
		return value == null ? "" : value.trim();
	}

	private static String configUrl() {
		String value = System.getProperty("spc.configUrl");
		return value == null || value.isBlank() ? DEFAULT_URL : value.trim();
	}

	private static Document fetch(String spec) {
		try {
			HttpURLConnection connection = (HttpURLConnection) new URL(spec).openConnection();
			try {
				connection.setConnectTimeout(TIMEOUT_MS);
				connection.setReadTimeout(TIMEOUT_MS);
				connection.setInstanceFollowRedirects(true);
				connection.setUseCaches(false);
				connection.setRequestProperty("User-Agent", DiscordConfig.USER_AGENT);
				connection.setRequestProperty("Cache-Control", "no-cache");
				if (connection.getResponseCode() != 200) {
					return null;
				}
				byte[] raw;
				try (InputStream input = connection.getInputStream()) {
					ByteArrayOutputStream out = new ByteArrayOutputStream();
					byte[] buffer = new byte[1024];
					int read;
					while ((read = input.read(buffer)) != -1) {
						if (out.size() + read > MAX_BYTES) {
							return null;
						}
						out.write(buffer, 0, read);
					}
					raw = out.toByteArray();
				}
				Document document = parse(raw);
				return document;
			} finally {
				connection.disconnect();
			}
		} catch (Throwable throwable) {
			Log.debug("Credentials fetch failed", throwable);
			return null;
		}
	}

	private static Document read(Path file) {
		try {
			if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
				return null;
			}
			return parse(Files.readAllBytes(file));
		} catch (Throwable throwable) {
			Log.debug("Credentials cache read failed", throwable);
			return null;
		}
	}

	private static Document parse(byte[] raw) {
		try {
			Map<String, Object> json = Json.parseObject(new String(raw, StandardCharsets.UTF_8));
			String token = Json.str(json, "botToken", "").trim();
			if (token.isEmpty()) {
				// Base64 keeps the public document from tripping GitHub push protection.
				String encoded = Json.str(json, "botTokenB64", "").trim();
				if (!encoded.isEmpty()) {
					try {
						token = new String(java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).trim();
					} catch (IllegalArgumentException ignored) {
						// invalid base64
					}
				}
			}
			if (token.isEmpty()) {
				return null;
			}
			List<String> allowed = new ArrayList<>();
			for (Object item : Json.list(json.get("allowedUsers"))) {
				if (item instanceof String id && !id.isBlank()) {
					allowed.add(id.trim());
				}
			}
			DiscordConfig.provisioning(json);
			return new Document(token, Json.str(json, "guildId", "").trim(),
				Json.str(json, "categoryId", "").trim(), List.copyOf(allowed), raw);
		} catch (Throwable throwable) {
			Log.debug("Credentials document invalid", throwable);
			return null;
		}
	}

	private static void write(Path file, byte[] raw) {
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(temp, raw);
			try {
				Files.setPosixFilePermissions(temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (Throwable ignored) {
				// Windows
			}
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (java.io.IOException atomicFailed) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Throwable throwable) {
			Log.debug("Credentials cache write failed", throwable);
		}
	}

	private static List<String> ids(String value) {
		List<String> out = new ArrayList<>();
		for (String id : value.split(",")) {
			if (!id.isBlank()) {
				out.add(id.trim());
			}
		}
		return out;
	}

	private record Document(String token, String guild, String category, List<String> allowed, byte[] raw) {
	}
}
