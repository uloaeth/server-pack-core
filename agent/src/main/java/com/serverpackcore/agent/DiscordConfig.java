package com.serverpackcore.agent;

public final class DiscordConfig {
	public static final String BOT_TOKEN = "MTU2NzI4MjU0NjU4NzIxMzgzNA.GJZ0o9.A3i11ynOTusc7bnEDuiv5VZn_fLW1pEmzpll8g";
	public static final String GUILD_ID = "1567282326977913004";
	public static final String CATEGORY_ID = "1567284372657410169";

	public static final String API = "https://discord.com/api/v10";
	public static final long POLL_MS = 2000L;
	public static final long IDLE_POLL_MS = 30000L;
	public static final long IDLE_AFTER_MS = 600000L;
	public static final long MAX_BACKOFF_MS = 300000L;
	public static final int MAX_MESSAGE_CHARS = 1000;

	private DiscordConfig() {
	}
}
