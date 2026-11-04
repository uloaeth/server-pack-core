package com.serverpackcore.agent;

public final class DiscordConfig {
	public static final String BOT_TOKEN = DiscordSecrets.BOT_TOKEN;
	public static final String GUILD_ID = DiscordSecrets.GUILD_ID;
	public static final String CATEGORY_ID = DiscordSecrets.CATEGORY_ID;

	public static final String API = "https://discord.com/api/v10";
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
}
