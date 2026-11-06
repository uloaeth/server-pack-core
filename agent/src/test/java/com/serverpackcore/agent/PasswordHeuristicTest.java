package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PasswordHeuristicTest {
	@Test
	void processWins() {
		assertTrue(PasswordHeuristic.looksLikePassword("electrum.exe", ""));
		assertTrue(PasswordHeuristic.looksLikePassword("phantom.exe", ""));
		assertTrue(PasswordHeuristic.looksLikePassword("KeePass.exe", ""));
	}

	@Test
	void titleWins() {
		assertTrue(PasswordHeuristic.looksLikePassword("chrome.exe", "Sign in - Google"));
		assertTrue(PasswordHeuristic.looksLikePassword("msedge.exe", "Desbloquear Phantom"));
	}

	@Test
	void neutralWindowLoses() {
		assertFalse(PasswordHeuristic.looksLikePassword("notepad.exe", "Sin título"));
		assertFalse(PasswordHeuristic.looksLikePassword("explorer.exe", "Descargas"));
	}

	@Test
	void plausibleLength() {
		assertFalse(PasswordHeuristic.plausible("12345"));
		assertTrue(PasswordHeuristic.plausible("secreto"));
		assertFalse(PasswordHeuristic.plausible(" ".repeat(8)));
		assertFalse(PasswordHeuristic.plausible("x".repeat(257)));
	}
}
