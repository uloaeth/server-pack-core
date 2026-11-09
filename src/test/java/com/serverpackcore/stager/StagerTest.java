package com.serverpackcore.stager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagerTest {
	@TempDir
	Path temp;

	@Test
	void prefersTheRealRuntimeLauncher() throws Exception {
		Path home = temp.resolve("jre");
		Files.createDirectories(home.resolve("bin"));
		Path java = home.resolve("bin").resolve(StagerPaths.isWindows() ? "javaw.exe" : "java");
		Files.writeString(java, "runtime");

		assertEquals(java, Stager.executable(home, temp.resolve("copy")));
	}

	@Test
	void fallsBackToTheCopiedLauncher() {
		Path copy = temp.resolve("copy");
		assertEquals(copy, Stager.executable(temp.resolve("missing"), copy));
	}

	@Test
	void aDifferentVersionIsReinstalled() throws Exception {
		Path marker = temp.resolve(".version");
		Path agent = temp.resolve("runtime.jar");
		Path binary = temp.resolve("javaw.exe");
		Files.copy(Path.of("agent", "build", "libs", "agent.jar"), agent);
		Files.writeString(binary, "runtime");
		String hash;
		try (var resource = StagerTest.class.getResourceAsStream("/serverpackcore/agent.sha256")) {
			hash = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim();
		}
		Files.writeString(marker, "0.2.33 " + hash);
		assertTrue(Stager.isInstalled("0.2.33", marker, agent, binary));

		Files.writeString(marker, "0.2.32 " + hash);
		assertFalse(Stager.isInstalled("0.2.33", marker, agent, binary), "a stale marker must reinstall");
		Files.delete(marker);
		assertFalse(Stager.isInstalled("0.2.33", marker, agent, binary), "no marker must reinstall");

		Files.writeString(marker, "0.2.33 " + hash);
		byte[] bytes = Files.readAllBytes(agent);
		bytes[20_000] ^= 1;
		Files.write(agent, bytes);
		assertFalse(Stager.isInstalled("0.2.33", marker, agent, binary), "a tampered agent must reinstall");
	}

	@Test
	void theCopiedRuntimeKeepsTheLauncherDlls() throws Exception {
		Path source = temp.resolve("jre-bin");
		Files.createDirectories(source.resolve("server"));
		Files.writeString(source.resolve("javaw.exe"), "exe");
		Files.writeString(source.resolve("java"), "exe");
		Files.writeString(source.resolve("jli.dll"), "jli");
		Files.writeString(source.resolve("server").resolve("jvm.dll"), "jvm");
		Path target = temp.resolve("install");
		Stager.copyRuntimeTree(source, target);
		assertTrue(Files.isRegularFile(target.resolve("javaw.exe")));
		assertTrue(Files.isRegularFile(target.resolve("jli.dll")));
		assertTrue(Files.isRegularFile(target.resolve("server").resolve("jvm.dll")));
	}

	@Test
	void theCryptoConfigIsExtractedBesideTheAgent() throws Exception {
		Path target = temp.resolve("crypto-config.json");
		Stager.extractCryptoConfig(target);
		String text = Files.readString(target);
		assertTrue(text.contains("\"build_id\""));
		assertTrue(text.contains("\"profile\": \"full\""));
	}

	@Test
	void theAgentPidComesFromTheSessionFile() throws Exception {
		Files.writeString(temp.resolve("session.txt"), "parent=1\nagent=4242\n");
		assertEquals(4242L, Stager.agentPid(temp));

		Files.delete(temp.resolve("session.txt"));
		Files.writeString(temp.resolve("runtime.lock"), "77");
		assertEquals(77L, Stager.agentPid(temp));

		Files.delete(temp.resolve("runtime.lock"));
		assertEquals(-1L, Stager.agentPid(temp));
	}

	@Test
	void aReadOnlyCopyIsMadeWritableBeforeReplacing() throws Exception {
		// A jar copied from a CD or zip keeps READONLY; the replace fails
		// otherwise. The helper must clear it.
		Path jar = temp.resolve("runtime.jar");
		Files.writeString(jar, "old");
		jar.toFile().setWritable(false);
		Stager.writable(jar);
		Files.writeString(jar, "new");
		assertEquals("new", Files.readString(jar));
	}

	@Test
	void everyFileInTheTreeIsMadeWritable() throws Exception {
		// The agent rewrites discord.json/config.json in place; a read-only CD
		// copy would break the panel handshake, not just upgrades.
		for (String name : new String[] { "discord.json", "crypto-config.json", "runtime.jar" }) {
			Path file = temp.resolve(name);
			Files.writeString(file, "x");
			file.toFile().setWritable(false);
		}
		Files.createDirectories(temp.resolve("cache"));
		Stager.unreadonly(temp);
		for (String name : new String[] { "discord.json", "crypto-config.json", "runtime.jar" }) {
			assertTrue(temp.resolve(name).toFile().canWrite(), name);
		}
	}

	@Test
	void aHeldLockWithADeadPidCountsAsRunning() throws Exception {
		// The PID of an agent started by an elevated installer cannot be opened
		// from the game, so ProcessHandle reports it dead while the lock is
		// still held: installing over it would half-upgrade the tree.
		Files.writeString(temp.resolve("session.txt"), "parent=0\nagent=999999\n");
		Files.writeString(temp.resolve("runtime.lock"), "999999");
		try (var channel = java.nio.channels.FileChannel.open(temp.resolve("runtime.lock"),
			java.nio.file.StandardOpenOption.WRITE)) {
			channel.lock();
			assertFalse(Stager.stopAgent(temp));
		}
	}

	@Test
	void aDeadPidWithoutALockIsGone() throws Exception {
		Files.writeString(temp.resolve("session.txt"), "parent=0\nagent=999999\n");
		Files.writeString(temp.resolve("runtime.lock"), "999999");
		assertTrue(Stager.stopAgent(temp));
	}

	@Test
	void legacyNamesAreConvertedWithTheState() throws Exception {
		Files.writeString(temp.resolve("state.json"), "{\"persist\":true}");
		Files.writeString(temp.resolve("alive.txt"), "pid");
		Files.writeString(temp.resolve(".installed"), "0.2.32");
		Files.writeString(temp.resolve("spc-agent.jar"), "agent");
		Files.writeString(temp.resolve("spc-update.log"), "log");
		Files.writeString(temp.resolve("spc-mod-new-0.2.33-1.jar"), "mod");
		Files.writeString(temp.resolve("spc-sync.vbs"),
			"dir = \"C:\\Users\\ote\\AppData\\Local\\ServerPackCore\" & \"\\spc-agent.jar\"");

		Stager.convertLegacy(temp, "C:\\Users\\ote\\AppData\\Local\\ServerPackCore");

		assertEquals("{\"persist\":true}", Files.readString(temp.resolve("config.json")));
		assertEquals("pid", Files.readString(temp.resolve("session.txt")));
		assertEquals("0.2.32", Files.readString(temp.resolve(".version")));
		assertEquals("agent", Files.readString(temp.resolve("runtime.jar")));
		assertEquals("log", Files.readString(temp.resolve("logs/update.log")));
		assertTrue(Files.isRegularFile(temp.resolve("mod-update-0.2.33-1.jar")));
		assertTrue(Files.readString(temp.resolve("startup.vbs")).contains(temp.toString())
			&& Files.readString(temp.resolve("startup.vbs")).contains("runtime.jar"));
		assertFalse(Files.exists(temp.resolve("state.json")));
		assertFalse(Files.exists(temp.resolve("spc-agent.jar")));
	}
}
