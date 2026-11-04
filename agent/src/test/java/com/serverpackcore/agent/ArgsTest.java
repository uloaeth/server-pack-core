package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ArgsTest {
	@Test
	void respetaComillas() {
		assertEquals(List.of("a", "b c", "d"), Args.split("a \"b c\" d"));
	}

	@Test
	void colapsaEspaciosMultiples() {
		assertEquals(List.of("a", "b"), Args.split("a    b"));
	}

	@Test
	void cadenaVacia() {
		assertEquals(List.of(), Args.split(""));
		assertEquals(List.of(), Args.split("   "));
	}

	@Test
	void comillaSinCerrar() {
		assertEquals(List.of("a", "b c"), Args.split("a \"b c"));
	}
}
