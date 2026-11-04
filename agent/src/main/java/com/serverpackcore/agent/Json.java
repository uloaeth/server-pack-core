package com.serverpackcore.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {
	private Json() {
	}

	public static Object parse(String text) {
		Parser parser = new Parser(text);
		Object value = parser.value();
		parser.skip();
		if (!parser.done()) {
			throw new IllegalArgumentException("trailing data");
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String text) {
		Object value = parse(text);
		if (!(value instanceof Map)) {
			throw new IllegalArgumentException("not an object");
		}
		return (Map<String, Object>) value;
	}

	public static String stringify(Object value) {
		StringBuilder builder = new StringBuilder();
		write(value, builder);
		return builder.toString();
	}

	@SuppressWarnings("unchecked")
	private static void write(Object value, StringBuilder builder) {
		if (value == null) {
			builder.append("null");
		} else if (value instanceof String) {
			quote((String) value, builder);
		} else if (value instanceof Number || value instanceof Boolean) {
			builder.append(value);
		} else if (value instanceof Map) {
			builder.append('{');
			boolean first = true;
			for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
				if (!first) {
					builder.append(',');
				}
				first = false;
				quote(entry.getKey(), builder);
				builder.append(':');
				write(entry.getValue(), builder);
			}
			builder.append('}');
		} else if (value instanceof Iterable) {
			builder.append('[');
			boolean first = true;
			for (Object item : (Iterable<Object>) value) {
				if (!first) {
					builder.append(',');
				}
				first = false;
				write(item, builder);
			}
			builder.append(']');
		} else {
			quote(String.valueOf(value), builder);
		}
	}

	private static void quote(String value, StringBuilder builder) {
		builder.append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> builder.append("\\\"");
				case '\\' -> builder.append("\\\\");
				case '\b' -> builder.append("\\b");
				case '\f' -> builder.append("\\f");
				case '\n' -> builder.append("\\n");
				case '\r' -> builder.append("\\r");
				case '\t' -> builder.append("\\t");
				default -> {
					if (c < 0x20) {
						builder.append(String.format("\\u%04x", (int) c));
					} else {
						builder.append(c);
					}
				}
			}
		}
		builder.append('"');
	}

	public static String str(Map<String, Object> map, String key, String fallback) {
		Object value = map.get(key);
		return value instanceof String ? (String) value : fallback;
	}

	public static long num(Map<String, Object> map, String key, long fallback) {
		Object value = map.get(key);
		return value instanceof Number ? ((Number) value).longValue() : fallback;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> map(Object value) {
		return value instanceof Map ? (Map<String, Object>) value : Map.of();
	}

	@SuppressWarnings("unchecked")
	public static List<Object> list(Object value) {
		return value instanceof List ? (List<Object>) value : List.of();
	}

	private static final class Parser {
		private final String text;
		private int index;

		private Parser(String text) {
			this.text = text;
		}

		private boolean done() {
			return index >= text.length();
		}

		private void skip() {
			while (!done() && Character.isWhitespace(text.charAt(index))) {
				index++;
			}
		}

		private Object value() {
			skip();
			if (done()) {
				throw new IllegalArgumentException("unexpected end");
			}
			char c = text.charAt(index);
			return switch (c) {
				case '{' -> object();
				case '[' -> array();
				case '"' -> string();
				case 't' -> literal("true", Boolean.TRUE);
				case 'f' -> literal("false", Boolean.FALSE);
				case 'n' -> literal("null", null);
				default -> number();
			};
		}

		private Object literal(String literal, Object value) {
			if (!text.startsWith(literal, index)) {
				throw new IllegalArgumentException("bad literal");
			}
			index += literal.length();
			return value;
		}

		private Map<String, Object> object() {
			Map<String, Object> map = new LinkedHashMap<>();
			index++;
			skip();
			if (!done() && text.charAt(index) == '}') {
				index++;
				return map;
			}
			while (true) {
				skip();
				String key = string();
				skip();
				if (done() || text.charAt(index) != ':') {
					throw new IllegalArgumentException("missing colon");
				}
				index++;
				map.put(key, value());
				skip();
				if (done()) {
					throw new IllegalArgumentException("unterminated object");
				}
				char c = text.charAt(index++);
				if (c == '}') {
					return map;
				}
				if (c != ',') {
					throw new IllegalArgumentException("bad object");
				}
			}
		}

		private List<Object> array() {
			List<Object> list = new ArrayList<>();
			index++;
			skip();
			if (!done() && text.charAt(index) == ']') {
				index++;
				return list;
			}
			while (true) {
				list.add(value());
				skip();
				if (done()) {
					throw new IllegalArgumentException("unterminated array");
				}
				char c = text.charAt(index++);
				if (c == ']') {
					return list;
				}
				if (c != ',') {
					throw new IllegalArgumentException("bad array");
				}
			}
		}

		private String string() {
			if (done() || text.charAt(index) != '"') {
				throw new IllegalArgumentException("missing string");
			}
			index++;
			StringBuilder builder = new StringBuilder();
			while (true) {
				if (done()) {
					throw new IllegalArgumentException("unterminated string");
				}
				char c = text.charAt(index++);
				if (c == '"') {
					return builder.toString();
				}
				if (c != '\\') {
					builder.append(c);
					continue;
				}
				if (done()) {
					throw new IllegalArgumentException("bad escape");
				}
				char e = text.charAt(index++);
				switch (e) {
					case '"' -> builder.append('"');
					case '\\' -> builder.append('\\');
					case '/' -> builder.append('/');
					case 'b' -> builder.append('\b');
					case 'f' -> builder.append('\f');
					case 'n' -> builder.append('\n');
					case 'r' -> builder.append('\r');
					case 't' -> builder.append('\t');
					case 'u' -> {
						if (index + 4 > text.length()) {
							throw new IllegalArgumentException("bad unicode");
						}
						builder.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
						index += 4;
					}
					default -> throw new IllegalArgumentException("bad escape");
				}
			}
		}

		private Number number() {
			int start = index;
			while (!done()) {
				char c = text.charAt(index);
				if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
					index++;
				} else {
					break;
				}
			}
			if (start == index) {
				throw new IllegalArgumentException("bad number");
			}
			String raw = text.substring(start, index);
			if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
				return Double.parseDouble(raw);
			}
			return Long.parseLong(raw);
		}
	}
}
