package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarvestTest {
	@TempDir
	Path temp;

	@Test
	void ldbReadCutAtEightMB() throws Exception {
		Path file = temp.resolve("big.ldb");
		byte[] data = new byte[9 * 1024 * 1024];
		byte[] token = "TOKEN_OUTSIDE".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		System.arraycopy(token, 0, data, 8 * 1024 * 1024 + 100, token.length);
		Files.write(file, data);
		String text = Harvest.readLimited(file);
		assertEquals(8 * 1024 * 1024, text.length());
		assertFalse(text.contains("TOKEN_OUTSIDE"));
	}
}
