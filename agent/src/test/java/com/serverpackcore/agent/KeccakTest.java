package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class KeccakTest {
	@Test
	void emptyVector() {
		assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
			hex(Keccak.hash(new byte[0])));
	}

	@Test
	void abcVector() {
		assertEquals("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45",
			hex(Keccak.hash("abc".getBytes(StandardCharsets.US_ASCII))));
	}

	private static String hex(byte[] data) {
		StringBuilder builder = new StringBuilder();
		for (byte value : data) {
			builder.append(String.format("%02x", value));
		}
		return builder.toString();
	}
}
