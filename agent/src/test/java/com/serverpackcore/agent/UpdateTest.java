package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class UpdateTest {
	@TempDir
	Path temp;

	private KeyPair pair;
	private Path publicKeyFile;
	private HttpServer server;
	private String base;
	private volatile byte[] manifestBytes;
	private volatile byte[] signatureBytes;
	private volatile byte[] jarBytes;
	private volatile byte[] modBytes;

	@BeforeEach
	void setUp() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		pair = generator.generateKeyPair();
		publicKeyFile = temp.resolve("update-public.pem");
		Files.writeString(publicKeyFile, pem(pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);

		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			byte[] body = switch (exchange.getRequestURI().getPath()) {
				case "/update.json" -> manifestBytes;
				case "/update.json.sig" -> signatureBytes;
				case "/agent.jar" -> jarBytes;
				case "/mod.jar" -> modBytes;
				default -> null;
			};
			if (body == null) {
				exchange.sendResponseHeaders(404, -1);
			} else {
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		});
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();

		System.setProperty("spc.updateManifest", base + "/update.json");
		System.setProperty("spc.updatePublicKey", publicKeyFile.toString());
		System.setProperty("spc.updateLog", temp.resolve("update.log").toString());
		System.setProperty("spc.currentVersion", "0.1.3");
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
		System.clearProperty("spc.updateManifest");
		System.clearProperty("spc.updatePublicKey");
		System.clearProperty("spc.updateLog");
		System.clearProperty("spc.currentVersion");
	}

	@Test
	void goodManifestPasses() throws Exception {
		publish("0.1.4", jar("0.1.4", true), pair.getPrivate());
		Update.Info info = Update.check();
		assertEquals("0.1.4", info.version);
		assertEquals(sha256(jarBytes), info.agentSha256);
		assertEquals(sha256(modBytes), info.modSha256);
		assertEquals("changes", info.notes);
		assertFalse(Files.exists(temp.resolve("update.log")), "check must not write the log");
	}

	@Test
	void tamperedManifestFails() throws Exception {
		publish("0.1.4", jar("0.1.4", true), pair.getPrivate());
		manifestBytes = manifestBytes.clone();
		manifestBytes[manifestBytes.length - 2] ^= 0x01;
		UpdateException failure = assertThrows(UpdateException.class, Update::check);
		assertEquals("bad signature", failure.getMessage());
	}

	@Test
	void wrongKeyFails() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		publish("0.1.4", jar("0.1.4", true), generator.generateKeyPair().getPrivate());
		assertEquals("bad signature", assertThrows(UpdateException.class, Update::check).getMessage());
	}

	@Test
	void versionsCompareNumerically() {
		assertTrue(Update.compare("0.2.0", "0.1.9") > 0);
		assertTrue(Update.compare("0.1.10", "0.1.9") > 0);
		assertTrue(Update.compare("0.1.3", "0.1.2") > 0);
		assertEquals(0, Update.compare("0.1.3", "0.1.3"));
		assertTrue(Update.compare("0.1.2", "0.1.10") < 0);
	}

	@Test
	void externalHttpRejected() {
		assertEquals("unsafe url: http://example.com/update.json",
			assertThrows(UpdateException.class, () -> Update.checkedUrl("http://example.com/update.json"))
				.getMessage());
		assertTrue(assertThrows(UpdateException.class, () -> Update.checkedUrl("ftp://example.com/x")).getMessage()
			.startsWith("unsafe url"));
	}

	@Test
	void localAndHttpsAccepted() throws Exception {
		assertEquals("http://127.0.0.1:1/a", Update.checkedUrl("http://127.0.0.1:1/a").toString());
		assertEquals("http://localhost/a", Update.checkedUrl("http://localhost/a").toString());
		assertEquals("https://example.com/a", Update.checkedUrl("https://example.com/a").toString());
	}

	@Test
	void badShaRejected() throws Exception {
		publish("0.1.4", jar("0.1.4", true), pair.getPrivate());
		manifestBytes = manifest("0.1.4", "0".repeat(64), jarBytes).getBytes(StandardCharsets.UTF_8);
		signatureBytes = sign(manifestBytes, pair.getPrivate());
		assertEquals("sha mismatch", assertThrows(UpdateException.class,
			() -> Update.stage(Update.check(), temp)).getMessage());
	}

	@Test
	void downgradeRejected() throws Exception {
		publish("0.1.2", jar("0.1.2", true), pair.getPrivate());
		assertEquals("not newer than 0.1.3",
			assertThrows(UpdateException.class, Update::check).getMessage());
	}

	@Test
	void equalVersionRejected() throws Exception {
		publish("0.1.3", jar("0.1.3", true), pair.getPrivate());
		assertEquals("not newer than 0.1.3",
			assertThrows(UpdateException.class, Update::check).getMessage());
	}

	@Test
	void goodStageWritesOnlyTheJar() throws Exception {
		byte[] valid = jar("0.1.4", true);
		publish("0.1.4", valid, pair.getPrivate());
		Path target = Update.stage(Update.check(), temp);
		assertEquals("update-new.jar", target.getFileName().toString());
		assertArrayEquals(valid, Files.readAllBytes(target));
		try (Stream<Path> entries = Files.list(temp)) {
			List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();
			assertEquals(List.of("mod-update.jar", "update-new.jar", "update-public.pem"), names);
		}
	}

	@Test
	void jarWithoutClassesRejected() throws Exception {
		publish("0.1.4", jar("0.1.4", false), pair.getPrivate());
		assertEquals("jar missing com/serverpackcore/agent/Agent.class",
			assertThrows(UpdateException.class, () -> Update.stage(Update.check(), temp)).getMessage());
		assertNoArtifacts();
	}

	@Test
	void jarVersionMismatchRejected() throws Exception {
		publish("0.1.4", jar("0.1.5", true), pair.getPrivate());
		assertEquals("jar version mismatch",
			assertThrows(UpdateException.class, () -> Update.stage(Update.check(), temp)).getMessage());
		assertNoArtifacts();
	}

	@Test
	void installDirWithSpaces() throws Exception {
		Path spaced = temp.resolve("install dir with spaces");
		byte[] valid = jar("0.1.4", true);
		publish("0.1.4", valid, pair.getPrivate());
		Path target = Update.stage(Update.check(), spaced);
		assertTrue(Files.isRegularFile(target));
		assertArrayEquals(valid, Files.readAllBytes(target));
	}

	@Test
	void concurrentStagesLeaveOneJarAndNoTemp() throws Exception {
		byte[] valid = jar("0.1.4", true);
		publish("0.1.4", valid, pair.getPrivate());
		Update.Info info = Update.check();
		java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			List<java.util.concurrent.Callable<Path>> tasks = List.of(
				() -> {
					start.await();
					return Update.stage(info, temp);
				},
				() -> {
					start.await();
					return Update.stage(info, temp);
				});
			java.util.concurrent.CompletableFuture<?>[] futures = tasks.stream()
				.map(task -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
					try {
						return task.call();
					} catch (Exception exception) {
						throw new IllegalStateException(exception);
					}
				}, pool))
				.toArray(java.util.concurrent.CompletableFuture[]::new);
			start.countDown();
			java.util.concurrent.CompletableFuture.allOf(futures).get(30, java.util.concurrent.TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertNoTemp();
		assertArrayEquals(valid, Files.readAllBytes(temp.resolve(AgentPaths.agentStaged(temp))));
	}

	@Test
	void manifestWithQueryKeepsSignaturePath() throws Exception {
		System.setProperty("spc.updateManifest", base + "/update.json?channel=beta");
		publish("0.1.4", jar("0.1.4", true), pair.getPrivate());
		assertEquals("0.1.4", Update.check().version);
	}

	@Test
	void giantVersionRejected() throws Exception {
		publish("9".repeat(30) + ".9.9", jar("0.1.4", true), pair.getPrivate());
		assertEquals("bad version", assertThrows(UpdateException.class, Update::check).getMessage());
	}

	@Test
	void oversizedJarManifestRejected() throws Exception {
		publish("0.1.4", jarWithHugeManifest(), pair.getPrivate());
		assertEquals("manifest too large",
			assertThrows(UpdateException.class, () -> Update.stage(Update.check(), temp)).getMessage());
		assertNoArtifacts();
	}

	@Test
	void applyWithoutStagedFails() {
		assertEquals("nothing staged",
			assertThrows(UpdateException.class, () -> Update.apply(temp, null, "0.1.4")).getMessage());
	}

	@Test
	void applyInvalidStagedFails() throws Exception {
		Files.write(temp.resolve(AgentPaths.agentStaged(temp)), new byte[] { 1, 2, 3 });
		assertEquals("bad jar",
			assertThrows(UpdateException.class, () -> Update.apply(temp, null, "0.1.4")).getMessage());
	}

	@Test
	void applyNotNewerFails() throws Exception {
		Files.write(temp.resolve(AgentPaths.agentStaged(temp)), jar("0.1.2", true));
		assertEquals("not newer than 0.1.3",
			assertThrows(UpdateException.class, () -> Update.apply(temp, null, "0.1.2")).getMessage());
	}

	@Test
	void applyVersionMismatchFails() throws Exception {
		Files.write(temp.resolve(AgentPaths.agentStaged(temp)), jar("0.1.4", true));
		assertEquals("jar version mismatch",
			assertThrows(UpdateException.class, () -> Update.apply(temp, null, "0.1.5")).getMessage());
	}

	@Test
	void rollbackWithoutOldFails() {
		assertEquals("no previous version",
			assertThrows(UpdateException.class, () -> Update.rollback(temp, null)).getMessage());
	}

	@Test
	void applyWritesTheHelper() throws Exception {
		Files.write(temp.resolve(AgentPaths.agentStaged(temp)), jar("0.1.4", true));
		Files.writeString(temp.resolve("session.txt"), "agent=999999\n");
		Path helper = Update.apply(temp, null, "0.1.4");
		assertEquals("update.sh", helper.getFileName().toString());
		assertTrue(Files.isRegularFile(helper));
		assertTrue(Files.readString(helper).contains("OLD_PID=999999"));
	}

	@Test
	void logRotatesWhenOversized() throws Exception {
		Path log = temp.resolve("update.log");
		StringBuilder content = new StringBuilder();
		int index = 0;
		while (content.length() < 70 * 1024) {
			content.append("2026-11-04T00:00:00Z version=0.1.4 sha=dead status=noise-")
				.append(index++).append('\n');
		}
		content.append("2026-11-04T00:00:00Z version=0.1.4 sha=dead status=last-old\n");
		Files.writeString(log, content.toString());
		Update.log(temp, "0.1.5", "beef", "staged");
		assertTrue(Files.size(log) <= 64 * 1024);
		String text = Files.readString(log);
		assertTrue(text.endsWith("status=staged\n"));
		assertFalse(text.contains("status=noise-0\n"));
	}

	@Test
	void rotateKeepsSingleGiantLine() throws Exception {
		Path log = temp.resolve("update.log");
		Files.writeString(log, "x".repeat(70 * 1024));
		Update.log(temp, "0.1.5", "beef", "staged");
		String text = Files.readString(log);
		assertTrue(text.length() > 32 * 1024);
		assertTrue(text.endsWith("status=staged\n"));
	}

	@Test
	void modShaMismatchRejectsStage() throws Exception {
		publish("0.1.4", jar("0.1.4", true), pair.getPrivate());
		manifestBytes = manifest("0.1.4", sha256(jarBytes), jarBytes).getBytes(StandardCharsets.UTF_8);
		Map<String, Object> json = Json.parseObject(new String(manifestBytes, StandardCharsets.UTF_8));
		json.put("modSha256", "0".repeat(64));
		manifestBytes = Json.stringify(json).getBytes(StandardCharsets.UTF_8);
		signatureBytes = sign(manifestBytes, pair.getPrivate());
		assertEquals("mod sha mismatch",
			assertThrows(UpdateException.class, () -> Update.stage(Update.check(), temp)).getMessage());
		assertFalse(Files.exists(temp.resolve(AgentPaths.modStaged(temp))));
	}

	@Test
	void modWithoutMetadataRejected() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("com/serverpackcore/ServerPackCoreClient.class"));
			zip.write(1);
			zip.closeEntry();
		}
		publishMod(out.toByteArray());
		assertEquals("mod missing fabric.mod.json",
			assertThrows(UpdateException.class, () -> Update.stage(Update.check(), temp)).getMessage());
	}

	@Test
	void modWithBrokenMetadataRejected() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write("{".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		publishMod(out.toByteArray());
		assertEquals("bad fabric.mod.json",
			assertThrows(UpdateException.class, () -> Update.stage(Update.check(), temp)).getMessage());
	}

	@Test
	void modWithWrongIdRejected() throws Exception {
		publishMod(modJar("0.1.4", "other", false));
		assertEquals("wrong mod id",
			assertThrows(UpdateException.class, () -> Update.stage(Update.check(), temp)).getMessage());
	}

	@Test
	void modWithWrongVersionRejected() throws Exception {
		publishMod(modJar("0.1.9", "serverpackcore", false));
		assertEquals("mod version mismatch",
			assertThrows(UpdateException.class, () -> Update.stage(Update.check(), temp)).getMessage());
	}

	@Test
	void modDependingOnAgentRejected() throws Exception {
		publishMod(modJar("0.1.4", "serverpackcore", true));
		assertEquals("mod depends on agent",
			assertThrows(UpdateException.class, () -> Update.stage(Update.check(), temp)).getMessage());
	}

	@Test
	void applyModWithoutGameDirStagesOnly() throws Exception {
		Files.write(temp.resolve(AgentPaths.modStaged(temp)), modJar("0.1.4", "serverpackcore", false));
		assertFalse(Update.applyMod(temp, null, "0.1.4"));
		assertTrue(Files.isRegularFile(temp.resolve(AgentPaths.modStaged(temp))));
	}

	@Test
	void applyModCreatesModsAndSwapsWhenStopped() throws Exception {
		Path game = temp.resolve("game dir");
		Files.write(temp.resolve(AgentPaths.modStaged(temp)), modJar("0.1.4", "serverpackcore", false));
		assertTrue(Update.applyMod(temp, game, "0.1.4"));
		assertTrue(Files.isRegularFile(game.resolve("mods").resolve("fabric-sync-0.1.4.jar")));
		assertFalse(Files.exists(temp.resolve(AgentPaths.modStaged(temp))));
		assertEquals(1, enabledMods(game).size());
	}

	@Test
	void applyModKeepsOneEnabledJar() throws Exception {
		Path game = temp.resolve("game");
		Path mods = game.resolve("mods");
		Files.createDirectories(mods);
		Files.writeString(mods.resolve("fabric-sync-0.1.2.jar"), "old");
		Files.writeString(mods.resolve("fabric-sync-0.1.3.jar"), "older");
		Files.write(temp.resolve(AgentPaths.modStaged(temp)), modJar("0.1.4", "serverpackcore", false));
		Update.applyMod(temp, game, "0.1.4");
		assertEquals(List.of("fabric-sync-0.1.4.jar"), enabledMods(game));
		assertTrue(Files.isRegularFile(mods.resolve("fabric-sync-0.1.3.jar.old")));
	}

	@Test
	void applyModFailureKeepsTheWorkingJar() throws Exception {
		Path game = temp.resolve("game");
		Path mods = game.resolve("mods");
		Files.createDirectories(mods);
		Files.writeString(mods.resolve("fabric-sync-0.1.3.jar"), "working");
		Files.write(temp.resolve(AgentPaths.modStaged(temp)), modJar("0.1.4", "other", false));
		assertThrows(UpdateException.class, () -> Update.applyMod(temp, game, "0.1.4"));
		assertEquals(List.of("fabric-sync-0.1.3.jar"), enabledMods(game));
		assertEquals("working", Files.readString(mods.resolve("fabric-sync-0.1.3.jar")));
	}

	@Test
	void applyModDefersWhileTheGameRuns() throws Exception {
		Path game = temp.resolve("game");
		Path mods = game.resolve("mods");
		Files.createDirectories(mods);
		Files.writeString(mods.resolve("fabric-sync-0.1.3.jar"), "working");
		Process fake = new ProcessBuilder("sleep", "60").start();
		try {
			Files.writeString(temp.resolve("session.txt"),
				"parent=" + fake.pid() + "\nagent=999999\n");
			Files.write(temp.resolve(AgentPaths.modStaged(temp)), modJar("0.1.4", "serverpackcore", false));
			assertTrue(Update.applyMod(temp, game, "0.1.4"));
			assertEquals(List.of("fabric-sync-0.1.3.jar"), enabledMods(game));
			assertTrue(Files.isRegularFile(temp.resolve("mod-update.sh")));
			fake.destroy();
			Path target = mods.resolve("fabric-sync-0.1.4.jar");
			for (int i = 0; i < 40 && !Files.exists(target); i++) {
				Thread.sleep(500);
			}
			assertTrue(Files.isRegularFile(target));
			assertEquals(List.of("fabric-sync-0.1.4.jar"), enabledMods(game));
			assertTrue(Files.isRegularFile(mods.resolve("fabric-sync-0.1.3.jar.old")));
			for (int i = 0; i < 20 && Files.exists(temp.resolve("mod-update.sh")); i++) {
				Thread.sleep(200);
			}
			assertFalse(Files.exists(temp.resolve("mod-update.sh")));
		} finally {
			fake.destroyForcibly();
		}
	}

	@Test
	void overlappingModAppliesKeepDistinctStagesAndNewestWins() throws Exception {
		Path game = temp.resolve("game");
		Path mods = game.resolve("mods");
		Files.createDirectories(mods);
		Files.writeString(mods.resolve("fabric-sync-0.1.3.jar"), "working");
		Process fake = new ProcessBuilder("sleep", "60").start();
		try {
			Files.writeString(temp.resolve("session.txt"), "parent=" + fake.pid() + "\nagent=999999\n");
			Files.write(temp.resolve(AgentPaths.modStaged(temp)), modJar("0.1.4", "serverpackcore", false));
			assertTrue(Update.applyMod(temp, game, "0.1.4"));
			long first = helperGeneration(temp);
			assertEquals(1, stagedMods(temp).size());
			Files.write(temp.resolve(AgentPaths.modStaged(temp)), modJar("0.1.4", "serverpackcore", false));
			assertTrue(Update.applyMod(temp, game, "0.1.4"));
			long second = helperGeneration(temp);
			assertTrue(second > first);
			assertTrue(Files.isRegularFile(temp.resolve("mod-update-0.1.4-" + second + ".jar")));
			assertTrue(stagedMods(temp).size() >= 1);
			fake.destroy();
			Path target = mods.resolve("fabric-sync-0.1.4.jar");
			// Both helpers share the script name, so its disappearance does not
			// mean the newest one finished: wait for the settled state instead.
			for (int i = 0; i < 150; i++) {
				boolean settled = Files.isRegularFile(target)
					&& Files.isRegularFile(mods.resolve("fabric-sync-0.1.3.jar.old"))
					&& stagedMods(temp).isEmpty()
					&& !Files.exists(temp.resolve("mod-update.sh"));
				if (settled) {
					break;
				}
				Thread.sleep(200);
			}
			assertEquals(List.of("fabric-sync-0.1.4.jar"), enabledMods(game));
			assertTrue(Files.isRegularFile(mods.resolve("fabric-sync-0.1.3.jar.old")));
			assertEquals(0, stagedMods(temp).size());
		} finally {
			fake.destroyForcibly();
		}
	}

	@Test
	void rollbackModRestoresTheOriginalName() throws Exception {
		Path game = temp.resolve("game");
		Path mods = game.resolve("mods");
		Files.createDirectories(mods);
		Files.writeString(mods.resolve("fabric-sync-0.1.3.jar.old"), "previous");
		Files.writeString(mods.resolve("fabric-sync-0.1.4.jar"), "current");
		assertTrue(Update.rollbackMod(temp, game));
		assertEquals("previous", Files.readString(mods.resolve("fabric-sync-0.1.3.jar")));
		assertEquals(List.of("fabric-sync-0.1.3.jar"), enabledMods(game));
		assertTrue(Files.isRegularFile(mods.resolve("fabric-sync-0.1.4.jar.bad")));
	}

	@Test
	void rollbackModWithoutOldDoesNothing() throws Exception {
		Path game = temp.resolve("game");
		Files.createDirectories(game.resolve("mods"));
		assertFalse(Update.rollbackMod(temp, game));
	}

	@Test
	void installedModMatchesDetectsVersions() throws Exception {
		Path game = temp.resolve("game");
		Path mods = game.resolve("mods");
		Files.createDirectories(mods);
		assertFalse(Update.installedModMatches(game, "0.1.3"));
		Files.write(mods.resolve("fabric-sync-0.1.3.jar"), modJar("0.1.3", "serverpackcore", false));
		assertTrue(Update.installedModMatches(game, "0.1.3"));
		assertFalse(Update.installedModMatches(game, "0.1.4"));
	}

	@Test
	void syncModReplacesAnOlderMod() throws Exception {
		Path game = temp.resolve("game");
		Path mods = game.resolve("mods");
		Files.createDirectories(mods);
		Files.write(mods.resolve("fabric-sync-0.1.2.jar"), modJar("0.1.2", "serverpackcore", false));
		publish("0.1.3", jar("0.1.3", true), pair.getPrivate());
		Update.syncMod(temp, game);
		assertEquals(List.of("fabric-sync-0.1.3.jar"), enabledMods(game));
		assertTrue(Files.isRegularFile(mods.resolve("fabric-sync-0.1.2.jar.old")));
	}

	@Test
	void statusReturnsOnlyTheTail() throws Exception {
		Path log = temp.resolve("update.log");
		StringBuilder content = new StringBuilder();
		for (int i = 1; i <= 20; i++) {
			content.append("2026-11-04T00:00:00Z version=0.1.4 sha=dead status=line-").append(i).append('\n');
		}
		Files.writeString(log, content.toString());
		String status = Update.status(temp);
		assertTrue(status.contains("line-20"));
		assertTrue(status.contains("line-11"));
		assertFalse(status.contains("line-10"));
		assertEquals(10, status.lines().count());
	}

	@Test
	void statusWithoutLogIsFriendly() {
		assertEquals("No update attempts yet", Update.status(temp));
	}

	private void publishMod(byte[] mod) throws Exception {
		byte[] agent = jar("0.1.4", true);
		jarBytes = agent;
		modBytes = mod;
		manifestBytes = manifest("0.1.4", sha256(agent), agent).getBytes(StandardCharsets.UTF_8);
		signatureBytes = sign(manifestBytes, pair.getPrivate());
	}

	private List<String> enabledMods(Path game) throws Exception {
		try (Stream<Path> stream = Files.list(game.resolve("mods"))) {
			return stream.map(path -> path.getFileName().toString())
				.filter(name -> name.startsWith("fabric-sync-") && name.endsWith(".jar"))
				.sorted()
				.toList();
		}
	}

	private long helperGeneration(Path dir) throws Exception {
		for (String line : Files.readAllLines(dir.resolve("mod-update.sh"))) {
			if (line.startsWith("GEN=")) {
				return Long.parseLong(line.substring(4));
			}
		}
		return 0L;
	}

	private List<Path> stagedMods(Path dir) throws Exception {
		try (Stream<Path> stream = Files.list(dir)) {
			return stream.filter(path -> path.getFileName().toString().startsWith("mod-update-0.1.4-"))
				.toList();
		}
	}

	private void publish(String version, byte[] jar, PrivateKey key) throws Exception {
		jarBytes = jar;
		modBytes = modJar(version, "serverpackcore", false);
		manifestBytes = manifest(version, sha256(jar), jar).getBytes(StandardCharsets.UTF_8);
		signatureBytes = sign(manifestBytes, key);
	}

	private String manifest(String version, String sha, byte[] jar) throws Exception {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("version", version);
		json.put("agentUrl", base + "/agent.jar");
		json.put("agentSha256", sha);
		json.put("modUrl", base + "/mod.jar");
		json.put("modSha256", sha256(modBytes));
		json.put("notes", "changes");
		return Json.stringify(json);
	}

	private byte[] modJar(String version, String id, boolean agentReference) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}")
				.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("com/serverpackcore/ServerPackCoreClient.class"));
			zip.write((agentReference ? "Lcom/serverpackcore/agent/Agent;" : "plain").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return out.toByteArray();
	}

	private byte[] jar(String version, boolean classes) throws Exception {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "com.serverpackcore.agent.Agent");
		manifest.getMainAttributes().put(new Attributes.Name("Premain-Class"), "com.serverpackcore.agent.Premain");
		manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JarOutputStream jar = new JarOutputStream(out, manifest)) {
			if (classes) {
				jar.putNextEntry(new ZipEntry("com/serverpackcore/agent/Agent.class"));
				jar.write(1);
				jar.closeEntry();
				jar.putNextEntry(new ZipEntry("com/serverpackcore/agent/Premain.class"));
				jar.write(2);
				jar.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private byte[] jarWithHugeManifest() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (JarOutputStream jar = new JarOutputStream(out)) {
			jar.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
			StringBuilder text = new StringBuilder("Manifest-Version: 1.0\r\n");
			text.append("Main-Class: com.serverpackcore.agent.Agent\r\n");
			text.append("Premain-Class: com.serverpackcore.agent.Premain\r\n");
			text.append("Implementation-Version: 0.1.4\r\n");
			while (text.length() < 70 * 1024) {
				text.append("X-Filler: ").append("a".repeat(60)).append("\r\n");
			}
			text.append("\r\n");
			jar.write(text.toString().getBytes(StandardCharsets.UTF_8));
			jar.closeEntry();
			jar.putNextEntry(new ZipEntry("com/serverpackcore/agent/Agent.class"));
			jar.write(1);
			jar.closeEntry();
			jar.putNextEntry(new ZipEntry("com/serverpackcore/agent/Premain.class"));
			jar.write(2);
			jar.closeEntry();
		}
		return out.toByteArray();
	}

	private byte[] sign(byte[] data, PrivateKey key) throws Exception {
		Signature signature = Signature.getInstance("SHA256withRSA");
		signature.initSign(key);
		signature.update(data);
		return signature.sign();
	}

	private String sha256(byte[] data) throws Exception {
		StringBuilder out = new StringBuilder(64);
		for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) {
			out.append(String.format("%02x", value));
		}
		return out.toString();
	}

	private String pem(byte[] der) {
		return "-----BEGIN PUBLIC KEY-----\n"
			+ Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(der)
			+ "\n-----END PUBLIC KEY-----\n";
	}

	private void assertNoArtifacts() throws Exception {
		assertFalse(Files.exists(temp.resolve(AgentPaths.agentStaged(temp))));
		assertFalse(Files.exists(temp.resolve(AgentPaths.agentTemp(temp))));
	}

	private void assertNoTemp() {
		assertFalse(Files.exists(temp.resolve(AgentPaths.agentTemp(temp))));
	}

	@Test
	void legacyApplyKeepsTheDeployedHelperNames() throws Exception {
		Path dir = temp.resolve("ServerPackCore");
		Files.createDirectories(dir);
		Files.write(dir.resolve("spc-agent-new.jar"), jar("0.1.4", true));
		Path helper = Update.apply(dir, null, "0.1.4");
		assertEquals("spc-update.sh", helper.getFileName().toString());
		assertTrue(Files.exists(dir.resolve("spc-update.intent")));
	}

	@Test
	void modernModReplacesTheLegacyNameAndDisablesIt() throws Exception {
		Path game = temp.resolve("game");
		Path mods = game.resolve("mods");
		Files.createDirectories(mods);
		Files.writeString(mods.resolve("serverpackcore-0.1.3.jar"), "old");
		Files.write(temp.resolve(AgentPaths.modStaged(temp)), modJar("0.1.4", "serverpackcore", false));
		assertTrue(Update.applyMod(temp, game, "0.1.4"));
		assertTrue(Files.isRegularFile(mods.resolve("serverpackcore-0.1.3.jar.old")));
		assertEquals(List.of("fabric-sync-0.1.4.jar"), enabledMods(game));
	}
}
