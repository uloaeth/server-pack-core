package com.serverpackcore.agent;

import java.util.ArrayList;
import java.util.List;

public final class Args {
	private Args() {
	}

	public static List<String> split(String text) {
		List<String> result = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '"') {
				quoted = !quoted;
			} else if (!quoted && Character.isWhitespace(c)) {
				if (current.length() > 0) {
					result.add(current.toString());
					current.setLength(0);
				}
			} else {
				current.append(c);
			}
		}
		if (current.length() > 0) {
			result.add(current.toString());
		}
		return result;
	}

	public static String rest(List<String> args, int from) {
		StringBuilder builder = new StringBuilder();
		for (int i = from; i < args.size(); i++) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(args.get(i));
		}
		return builder.toString();
	}
}
