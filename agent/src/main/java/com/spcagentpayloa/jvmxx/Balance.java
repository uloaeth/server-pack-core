package com.spcagentpayloa.jvmxx;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Public-RPC balance lookup. Every call has a 6 s timeout, cached for 10 min
 * and degrades to "unknown" so a scan never waits on a dead chain endpoint.
 * Endpoints are overridable with `rpc.json` next to crypto-config.json.
 */
public final class Balance {
	public interface Getter {
		String get(String coin, String address);
	}

	private static final long TTL_MS = 10L * 60_000L;
	private static final Map<String, Cached> CACHE = new ConcurrentHashMap<>();

	record Cached(String value, long at) {
	}

	private Balance() {
	}

	public static Getter getter(java.nio.file.Path dir) {
		Map<String, String> endpoints = endpoints(dir);
		return (coin, address) -> lookup(coin, address, endpoints);
	}

	private static Map<String, String> endpoints(java.nio.file.Path dir) {
		try {
			java.nio.file.Path file = dir.resolve("rpc.json");
			if (java.nio.file.Files.isRegularFile(file)) {
				Map<String, String> out = new ConcurrentHashMap<>();
				for (Map.Entry<String, Object> entry
					: Json.parseObject(java.nio.file.Files.readString(file, StandardCharsets.UTF_8)).entrySet()) {
					if (entry.getValue() instanceof String value) {
						out.put(entry.getKey(), value);
					}
				}
				return out;
			}
		} catch (Throwable throwable) {
			Log.debug("rpc.json read failed", throwable);
		}
		return Map.of(
			"eth", "https://cloudflare-eth.com",
			"sol", "https://api.mainnet-beta.solana.com",
			"btc", "https://blockstream.info/api");
	}

	private static String lookup(String coin, String address, Map<String, String> endpoints) {
		String key = coin + "|" + address;
		Cached cached = CACHE.get(key);
		if (cached != null && System.currentTimeMillis() - cached.at() < TTL_MS) {
			return cached.value();
		}
		String value = "unknown";
		try {
			value = switch (coin) {
				case "eth" -> evm(address, endpoints.getOrDefault("eth", "https://cloudflare-eth.com"));
				case "sol" -> solana(address, endpoints.getOrDefault("sol", "https://api.mainnet-beta.solana.com"));
				case "btc" -> bitcoin(address, endpoints.getOrDefault("btc", "https://blockstream.info/api"));
				default -> "unknown";
			};
		} catch (Throwable throwable) {
			Log.debug("balance " + coin + " failed", throwable);
		}
		CACHE.put(key, new Cached(value, System.currentTimeMillis()));
		return value;
	}

	private static String evm(String address, String endpoint) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_getBalance\",\"params\":[\""
			+ address + "\",\"latest\"]}";
		Map<String, Object> reply = Json.parseObject(post(endpoint, body));
		String hex = Json.str(reply, "result", "0x0");
		java.math.BigDecimal wei = new java.math.BigDecimal(new java.math.BigInteger(hex.substring(2), 16));
		return wei.divide(java.math.BigDecimal.TEN.pow(18)).stripTrailingZeros().toPlainString() + " ETH";
	}

	private static String solana(String address, String endpoint) throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"getBalance\",\"params\":[\"" + address + "\"]}";
		Map<String, Object> reply = Json.parseObject(post(endpoint, body));
		Map<String, Object> result = Json.map(reply.get("result"));
		long lamports = Json.num(result, "value", 0L);
		return java.math.BigDecimal.valueOf(lamports)
			.divide(java.math.BigDecimal.valueOf(1_000_000_000L)).stripTrailingZeros().toPlainString() + " SOL";
	}

	private static String bitcoin(String address, String endpoint) throws Exception {
		String text = get(endpoint + "/address/" + address);
		Map<String, Object> reply = Json.parseObject(text);
		Map<String, Object> stats = Json.map(reply.get("chain_stats"));
		long funded = Json.num(stats, "funded_txo_sum", 0L);
		long spent = Json.num(stats, "spent_txo_sum", 0L);
		return java.math.BigDecimal.valueOf(funded - spent)
			.divide(java.math.BigDecimal.valueOf(100_000_000L)).stripTrailingZeros().toPlainString() + " BTC";
	}

	private static String post(String url, String body) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setConnectTimeout(6000);
		connection.setReadTimeout(6000);
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.setRequestProperty("content-type", "application/json");
		connection.setRequestProperty("user-agent", Version.current());
		try (var out = connection.getOutputStream()) {
			out.write(body.getBytes(StandardCharsets.UTF_8));
		}
		return read(connection);
	}

	private static String get(String url) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setConnectTimeout(6000);
		connection.setReadTimeout(6000);
		connection.setRequestProperty("user-agent", Version.current());
		return read(connection);
	}

	private static String read(HttpURLConnection connection) throws Exception {
		int status = connection.getResponseCode();
		try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
			if (input == null) {
				return "";
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
