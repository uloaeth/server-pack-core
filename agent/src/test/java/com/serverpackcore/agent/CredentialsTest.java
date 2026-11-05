package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

class CredentialsTest {
	private static final String DOCUMENT =
		"{\"botTokenB64\":\"cmVtb3RlLXRva2Vu\",\"guildId\":\"1\",\"categoryId\":\"2\",\"allowedUsers\":[\"99\"]}";

	@TempDir
	Path temp;

	private HttpServer server;

	@BeforeEach
	void setUp() {
		DiscordConfig.reset();
		System.setProperty("spc.ignoreEnv", "true");
		System.clearProperty("spc.configUrl");
		System.clearProperty("spc.botToken");
		System.clearProperty("spc.guildId");
		System.clearProperty("spc.categoryId");
	}

	@AfterEach
	void tearDown() throws Exception {
		if (server != null) {
			server.stop(0);
		}
		DiscordConfig.reset();
		System.clearProperty("spc.ignoreEnv");
		System.clearProperty("spc.configUrl");
		System.clearProperty("spc.botToken");
		System.clearProperty("spc.guildId");
		System.clearProperty("spc.categoryId");
	}

	private String serve(String body) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/discord.json", exchange -> {
			byte[] data = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, data.length);
			exchange.getResponseBody().write(data);
			exchange.close();
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/discord.json";
	}

	@Test
	void explicitOverrideSkipsTheNetwork() {
		System.setProperty("spc.botToken", "property-token");
		System.setProperty("spc.guildId", "10");
		System.setProperty("spc.configUrl", "http://127.0.0.1:1/discord.json");
		assertEquals("property-token", Credentials.load(temp));
		assertEquals("10", DiscordConfig.guildId());
	}

	@Test
	void remoteDocumentIsAppliedAndCached() throws Exception {
		System.setProperty("spc.configUrl", serve(DOCUMENT));
		assertEquals("remote-token", Credentials.load(temp));
		assertEquals("1", DiscordConfig.guildId());
		assertTrue(DiscordConfig.allowedUsers().contains("99"));
		Path cache = AgentPaths.credentials(temp);
		assertTrue(Files.isRegularFile(cache));
		assertTrue(Files.readString(cache).contains("cmVtb3RlLXRva2Vu"));
	}

	@Test
	void cachedDocumentWorksWhenTheRemoteIsGone() throws Exception {
		System.setProperty("spc.configUrl", serve(DOCUMENT));
		assertEquals("remote-token", Credentials.load(temp));
		server.stop(0);
		server = null;
		DiscordConfig.reset();
		// Same dead port is fine: fetch fails fast or times out, then the cache wins.
		assertEquals("remote-token", Credentials.load(temp));
	}
}
