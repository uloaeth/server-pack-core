package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ArgsTest {
	@Test
	void respectsQuotes() {
		assertEquals(List.of("a", "b c", "d"), Args.split("a \"b c\" d"));
	}

	@Test
	void collapsesMultipleSpaces() {
		assertEquals(List.of("a", "b"), Args.split("a    b"));
	}

	@Test
	void emptyString() {
		assertEquals(List.of(), Args.split(""));
		assertEquals(List.of(), Args.split("   "));
	}

	@Test
	void unclosedQuote() {
		assertEquals(List.of("a", "b c"), Args.split("a \"b c"));
	}
}
