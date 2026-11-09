package com.spcagentpayloa.jvmxx;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Small CIM façade. WMIC is absent from recent Windows builds, so every
 * replacement goes through PowerShell. Constrained Language Mode and AppLocker
 * are normal: an empty result means "unavailable", never a fatal error.
 */
final class Cim {
	private Cim() {
	}

	/** Raw PowerShell command with a hard timeout; empty on any failure. */
	static String run(String script, long timeoutMs) {
		try {
			Process process = Ps.process("powershell", "-NoLogo", "-NoProfile", "-NonInteractive",
				"-ExecutionPolicy", "Bypass", "-Command", script).redirectErrorStream(true).start();
			try (var input = process.getInputStream()) {
				byte[] data = input.readNBytes(512 * 1024);
				process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
				return new String(data, StandardCharsets.UTF_8);
			} finally {
				process.destroy();
			}
		} catch (Throwable throwable) {
			Log.debug("CIM unavailable", throwable);
			return "";
		}
	}

	static List<Map<String, Object>> rows(String className, String namespace, String filter, String select) {
		StringBuilder script = new StringBuilder("Get-CimInstance -ClassName ").append(className);
		if (namespace != null && !namespace.isBlank()) {
			script.append(" -Namespace ").append(namespace);
		}
		if (filter != null && !filter.isBlank()) {
			script.append(" -Filter \"").append(filter.replace("\"", "\\\"")).append('"');
		}
		if (select != null && !select.isBlank()) {
			script.append(" | Select-Object ").append(select);
		}
		script.append(" | ConvertTo-Json -Compress");
		String text = run(script.toString(), 8_000L).trim();
		if (text.isEmpty()) {
			return List.of();
		}
		try {
			Object parsed = Json.parse(text);
			List<Map<String, Object>> out = new ArrayList<>();
			if (parsed instanceof List<?> list) {
				for (Object item : list) out.add(Json.map(item));
			} else if (parsed instanceof Map<?, ?>) {
				out.add(Json.map(parsed));
			}
			return out;
		} catch (Throwable throwable) {
			Log.debug("CIM JSON invalid", throwable);
			return List.of();
		}
	}

	static String value(Map<String, Object> row, String key) {
		Object value = row.get(key);
		return value == null ? "" : String.valueOf(value);
	}
}
