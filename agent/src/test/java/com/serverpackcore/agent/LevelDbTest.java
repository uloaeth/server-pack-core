package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LevelDbTest {
	@TempDir
	Path temp;

	@Test
	void snappyLiteralsDecode() {
		assertEquals("hello hello", new String(LevelDb.snappy(literal("hello hello")), StandardCharsets.UTF_8));
	}

	@Test
	void logRecordsAreReassembled() throws Exception {
		String value = "x dQw4w9WgXcQ:QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVoQUJDQUJD";
		Path file = temp.resolve("000003.log");
		Files.write(file, logRecord(value));
		List<String> blobs = LevelDb.tokenBlobs(file);
		assertEquals(List.of("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVoQUJDQUJD"), blobs);
	}

	@Test
	void snappySstableBlockYieldsItsValues() throws Exception {
		String value = "token dQw4w9WgXcQ:QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVoQUJDQUJD";
		Path file = temp.resolve("000005.ldb");
		Files.write(file, sstableBlock(value));
		assertTrue(LevelDb.values(file).stream().anyMatch(text -> text.contains("dQw4w9WgXcQ:")));
		assertTrue(LevelDb.tokenBlobs(file).contains("QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVoQUJDQUJD"));
	}

	private byte[] literal(String text) {
		byte[] data = text.getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		varint(out, data.length);
		int size = data.length;
		if (size <= 60) {
			out.write((size - 1) << 2);
		} else {
			out.write(60 << 2);
			out.write(size - 1);
		}
		out.writeBytes(data);
		return out.toByteArray();
	}

	private byte[] logRecord(String value) throws Exception {
		byte[] data = value.getBytes(StandardCharsets.ISO_8859_1);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(new byte[4]);
		out.write(data.length & 0xff);
		out.write((data.length >>> 8) & 0xff);
		out.write(1);
		out.writeBytes(data);
		return out.toByteArray();
	}

	private byte[] sstableBlock(String value) throws Exception {
		byte[] key = "token".getBytes(StandardCharsets.ISO_8859_1);
		byte[] data = value.getBytes(StandardCharsets.ISO_8859_1);
		ByteArrayOutputStream payload = new ByteArrayOutputStream();
		payload.write(0);
		varint(payload, key.length);
		varint(payload, data.length);
		payload.writeBytes(key);
		payload.writeBytes(data);
		payload.write(new byte[4]);
		byte[] compressed = literal(new String(payload.toByteArray(), StandardCharsets.ISO_8859_1));
		ByteArrayOutputStream block = new ByteArrayOutputStream();
		block.writeBytes(compressed);
		block.write(1);
		block.write(new byte[4]);
		ByteArrayOutputStream file = new ByteArrayOutputStream();
		file.write(new byte[4]);
		byte[] body = block.toByteArray();
		file.write(body.length & 0xff);
		file.write((body.length >>> 8) & 0xff);
		file.write((body.length >>> 16) & 0xff);
		file.write((body.length >>> 24) & 0xff);
		file.writeBytes(body);
		return file.toByteArray();
	}

	private void varint(ByteArrayOutputStream out, int value) {
		while (value >= 0x80) {
			out.write((value & 0x7f) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}
}
