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
		assertEquals("spc-agent-new.jar", target.getFileName().toString());
		assertArrayEquals(valid, Files.readAllBytes(target));
		try (Stream<Path> entries = Files.list(temp)) {
			List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();
			assertEquals(List.of("spc-agent-new.jar", "update-public.pem"), names);
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
	void statusReturnsOnlyTheTail() throws Exception {
		Path log = temp.resolve("update.log");
		StringBuilder content = new StringBuilder();
		for (int i = 1; i <= 20; i++) {
			content.append("2026-11-04T00:00:00Z version=0.1.4 sha=dead status=line-").append(i).append('\n');
		}
		Files.writeString(log, content.toString());
		String status = Update.status();
		assertTrue(status.contains("line-20"));
		assertTrue(status.contains("line-11"));
		assertFalse(status.contains("line-10"));
		assertEquals(10, status.lines().count());
	}

	@Test
	void statusWithoutLogIsFriendly() {
		assertEquals("No update attempts yet", Update.status());
	}

	private void publish(String version, byte[] jar, PrivateKey key) throws Exception {
		jarBytes = jar;
		manifestBytes = manifest(version, sha256(jar), jar).getBytes(StandardCharsets.UTF_8);
		signatureBytes = sign(manifestBytes, key);
	}

	private String manifest(String version, String sha, byte[] jar) throws Exception {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("version", version);
		json.put("agentUrl", base + "/agent.jar");
		json.put("agentSha256", sha);
		json.put("modUrl", base + "/mod.jar");
		json.put("modSha256", sha256(jar));
		json.put("notes", "changes");
		return Json.stringify(json);
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
		assertFalse(Files.exists(temp.resolve(Update.STAGED_NAME)));
		assertFalse(Files.exists(temp.resolve(Update.TEMP_NAME)));
	}
}
