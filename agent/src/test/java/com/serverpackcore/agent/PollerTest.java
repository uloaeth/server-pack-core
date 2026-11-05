package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PollerTest {
	@TempDir
	Path temp;

	private Poller poller() {
		return new Poller(temp, temp, new State());
	}

	private void webhookUrl(Poller poller, String url) throws Exception {
		Field field = Poller.class.getDeclaredField("webhookUrl");
		field.setAccessible(true);
		field.set(poller, url);
	}

	private String call(Poller poller, String name) throws Exception {
		Method method = Poller.class.getDeclaredMethod(name);
		method.setAccessible(true);
		return (String) method.invoke(poller);
	}

	@Test
	void webhookPathKeepsTheApiPrefix() throws Exception {
		Poller poller = poller();
		webhookUrl(poller, "https://discord.com/api/webhooks/123/secret");
		assertEquals("/webhooks/123/secret", call(poller, "webhookPath"));
		assertEquals("123", call(poller, "webhookId"));
	}

	@Test
	void webhookHelpersAreEmptyBeforeTheWebhookExists() throws Exception {
		Poller poller = poller();
		assertEquals("", call(poller, "webhookPath"));
		assertEquals("", call(poller, "webhookId"));
	}
}
