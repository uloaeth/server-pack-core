package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {
	@Test
	void objects() {
		Map<String, Object> map = Json.parseObject("{\"a\":1,\"b\":true}");
		assertEquals(1L, map.get("a"));
		assertEquals(Boolean.TRUE, map.get("b"));
	}

	@Test
	void arrays() {
		assertEquals(List.of(1L, 2L, 3L), Json.parse("[1,2,3]"));
	}

	@Test
	void escapes() {
		assertEquals("a\n\t\"b", Json.parse("\"a\\n\\t\\\"b\""));
	}

	@Test
	void unicode() {
		assertEquals("\u00f1", Json.parse("\"\\u00f1\""));
	}

	@Test
	void invalidInput() {
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,]"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1} extra"));
	}

	@Test
	void reasonableDepth() {
		String text = "[".repeat(100) + "]".repeat(100);
		assertTrue(Json.parse(text) instanceof List);
	}
}
