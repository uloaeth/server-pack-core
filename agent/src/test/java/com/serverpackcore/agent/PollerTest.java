package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
	void watchdogIgnoresAHealthyIdleGatewayAndFlagsAWedgedCycle() {
		long now = 1_000_000L;
		assertEquals(false, Poller.pollStale(now, now - 75_000L, 0L));
		assertEquals(true, Poller.pollStale(now, now - 91_000L, 0L));
		assertEquals(false, Poller.pollStale(now, now - 200_000L, now - 1_000L));
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

	@Test
	void onlyOneHeavyCollectionRunsAndRejectsTheNextReadably() throws Exception {
		Poller poller = poller();
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch started = new CountDownLatch(1);
		try {
			assertTrue(poller.submitHeavy("scan_wallets", 5_000L, () -> {
				started.countDown();
				try {
					release.await(2L, TimeUnit.SECONDS);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
			}));
			assertTrue(started.await(2L, TimeUnit.SECONDS));
			assertFalse(poller.submitHeavy("dump_vault", 5_000L, () -> { }));
			release.countDown();
			long until = System.currentTimeMillis() + 2_000L;
			while (System.currentTimeMillis() < until) {
				Thread.sleep(50L);
				if (poller.submitHeavy("harvest", 5_000L, () -> { })) {
					return;
				}
			}
			assertTrue(false, "heavy slot was not released");
		} finally {
			poller.kill();
		}
	}
}
