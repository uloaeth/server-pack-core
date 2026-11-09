package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapTest {
	@TempDir
	Path temp;

	@Test
	void profileSelectsTheOnlyHeapValues() throws Exception {
		assertEquals(java.util.List.of("-Xmx256m", "-XX:ActiveProcessorCount=2"), Heap.args("lite"));
		assertEquals(java.util.List.of("-Xmx512m", "-XX:ActiveProcessorCount=2"), Heap.args("full"));
		assertEquals(java.util.List.of("-Xmx256m", "-XX:ActiveProcessorCount=2"), Heap.argsFor(config("lite")));
		assertEquals(java.util.List.of("-Xmx512m", "-XX:ActiveProcessorCount=2"), Heap.argsFor(config("full")));
	}

	@Test
	void updateHelperCarriesTheSameHeapChain() throws Exception {
		Path dir = config("lite");
		String script = UpdateHelper.renderUnix(dir, dir.resolve("runtime.jar"), dir.resolve("new.jar"),
			dir.resolve("old.jar"), dir.resolve("bad.jar"), dir.resolve("session.txt"), Path.of("/usr/bin/java"),
			null, "0.3.7", "update");
		assertTrue(script.contains("HEAP='-Xmx256m -XX:ActiveProcessorCount=2 -XX:ErrorFile="));
		assertTrue(script.contains("set -- $HEAP -jar \"$CUR\""));
		assertFalse(script.contains("-Xmx96m"));
		String windows = UpdateHelper.renderWindows(dir, dir.resolve("runtime.jar"), dir.resolve("new.jar"),
			dir.resolve("old.jar"), dir.resolve("bad.jar"), dir.resolve("session.txt"),
			Path.of("C:\\java\\bin\\javaw.exe"), null, "0.3.7", "update");
		assertTrue(windows.contains("Const HEAP = \"-Xmx256m -XX:ActiveProcessorCount=2 -XX:ErrorFile="));
	}

	@Test
	void persistenceLauncherUsesTheProfile() throws Exception {
		Path dir = config("full");
		Path javaHome = temp.resolve("jdk");
		Files.createDirectories(javaHome.resolve("bin"));
		Files.writeString(javaHome.resolve("bin").resolve("javaw.exe"), "binary");
		String previous = System.getProperty("java.home");
		System.setProperty("java.home", javaHome.toString());
		Path launcher = Persistence.launcher(dir);
		Files.createDirectories(dir);
		java.lang.reflect.Method write = Persistence.class.getDeclaredMethod("writeLauncher", Path.class);
		write.setAccessible(true);
		assertTrue(write.invoke(null, dir) != null);
		String script = new String(Files.readAllBytes(launcher), StandardCharsets.UTF_16LE);
		assertTrue(script.contains("-Xmx512m -XX:ActiveProcessorCount=2"));
		assertFalse(script.contains("-Xmx96m"));
		System.setProperty("java.home", previous);
	}

	private Path config(String profile) throws Exception {
		Files.createDirectories(temp);
		Path dir = temp.resolve(profile);
		Files.createDirectories(dir);
		Files.writeString(dir.resolve("crypto-config.json"), "{\"profile\":\"" + profile + "\"}");
		return dir;
	}
}
