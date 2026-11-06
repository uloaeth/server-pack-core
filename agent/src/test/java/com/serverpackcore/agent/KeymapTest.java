package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KeymapTest {
	@Test
	void lettersFollowShiftAndCaps() {
		assertEquals("a", Keymap.map(0x41, false, false, ""));
		assertEquals("A", Keymap.map(0x41, true, false, ""));
		assertEquals("A", Keymap.map(0x41, false, true, ""));
		assertEquals("a", Keymap.map(0x41, true, true, ""));
	}

	@Test
	void digitsAndSymbols() {
		assertEquals("1", Keymap.map(0x31, false, false, ""));
		assertEquals("!", Keymap.map(0x31, true, false, ""));
		assertEquals(";", Keymap.map(0xBA, false, false, ""));
		assertEquals(":", Keymap.map(0xBA, true, false, ""));
		assertEquals("\"", Keymap.map(0xDE, true, false, ""));
	}

	@Test
	void specialKeys() {
		assertEquals("\b", Keymap.map(0x08, false, false, ""));
		assertEquals("\t", Keymap.map(0x09, false, false, ""));
		assertEquals("\n", Keymap.map(0x0D, false, false, ""));
		assertEquals(" ", Keymap.map(0x20, false, false, ""));
	}

	@Test
	void nativeUnicodeWins() {
		assertEquals("\u00f1", Keymap.map(0xBA, false, false, "\u00f1"));
		assertEquals("\n", Keymap.map(0x0D, false, false, "\r"));
	}
}
