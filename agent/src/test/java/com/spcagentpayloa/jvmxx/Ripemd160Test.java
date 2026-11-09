package com.spcagentpayloa.jvmxx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class Ripemd160Test {
	@Test
	void matchesThePublishedVectors() {
		assertEquals("9c1185a5c5e9fc54612808977ee8f548b2258d31", hex(""));
		assertEquals("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc", hex("abc"));
		assertEquals("5d0689ef49d2fae572b881b123a85ffa21595f36", hex("message digest"));
	}

	private static String hex(String text) {
		StringBuilder builder = new StringBuilder();
		for (byte value : Ripemd160.digest(text.getBytes(StandardCharsets.UTF_8))) {
			builder.append(String.format("%02x", value));
		}
		return builder.toString();
	}
}
