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
		} catch (Throwable throwable) {
			Log.debug("State read failed", throwable);
		}
		return state;
	}

	public void save(Path file) {
		try {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("version", version);
			map.put("channelId", channelId);
			map.put("webhookUrl", webhookUrl);
			map.put("lastId", lastId);
			map.put("lastHeartbeat", lastHeartbeat);
			map.put("hello", hello);
			map.put("persist", persist);
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, Json.stringify(map), StandardCharsets.UTF_8);
			Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		} catch (Throwable throwable) {
			Log.debug("State write failed", throwable);
		}
	}
}
