package com.serverpackcore.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verifies the signed update manifest and stages the new agent next to the current one. */
public final class Update {
	public static final String STAGED_NAME = "spc-agent-new.jar";
	public static final String TEMP_NAME = "spc-agent-new.jar.tmp";

	private static final String KEY_RESOURCE = "/serverpackcore/update-public.pem";
	private static final String AGENT_CLASS = "com/serverpackcore/agent/Agent.class";
	private static final String PREMAIN_CLASS = "com/serverpackcore/agent/Premain.class";
	private static final String AGENT_MAIN = "com.serverpackcore.agent.Agent";
	private static final String AGENT_PREMAIN = "com.serverpackcore.agent.Premain";

	private static final int TIMEOUT_MS = 10000;
	private static final int MAX_MANIFEST = 64 * 1024;
	private static final int MAX_SIGNATURE = 8 * 1024;
	private static final int MAX_AGENT = 4 * 1024 * 1024;
	private static final int MAX_NOTES = 4000;
	private static final int STATUS_WINDOW = 8 * 1024;
	private static final int STATUS_LINES = 10;

	private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");
	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Map<String, PublicKey> KEYS = new ConcurrentHashMap<>();
	private static final Object STAGE_LOCK = new Object();
	private static final Object LOG_LOCK = new Object();
	private static final int LOG_MAX = 64 * 1024;
	private static final int LOG_KEEP = 32 * 1024;

	private Update() {
	}

	public static final class Info {
		final String version;
		final String agentUrl;
		final String agentSha256;
		final String modUrl;
		final String modSha256;
		final String notes;

		Info(String version, String agentUrl, String agentSha256, String modUrl, String modSha256, String notes) {
			this.version = version;
			this.agentUrl = agentUrl;
			this.agentSha256 = agentSha256;
			this.modUrl = modUrl;
			this.modSha256 = modSha256;
			this.notes = notes;
		}
	}

	public static Info check() throws UpdateException {
		try {
			URL base = checkedUrl(manifestUrl());
			byte[] manifest = fetch(base, MAX_MANIFEST);
			byte[] signature = fetch(signatureUrl(base), MAX_SIGNATURE);
			verify(manifest, signature);

			Map<String, Object> json;
			try {
				json = Json.parseObject(new String(manifest, StandardCharsets.UTF_8));
			} catch (Throwable throwable) {
				throw new UpdateException("bad manifest");
			}

			String version = required(json, "version");
			String agentUrl = required(json, "agentUrl");
			String agentSha256 = required(json, "agentSha256");
			String modUrl = required(json, "modUrl");
			String modSha256 = required(json, "modSha256");
			String notes = Json.str(json, "notes", "");

			Matcher versionMatcher = VERSION.matcher(version);
			if (!versionMatcher.matches()) {
				throw new UpdateException("bad version: " + version);
			}
			validateSha(agentSha256);
			validateSha(modSha256);
			checkedUrl(agentUrl);
			checkedUrl(modUrl);

			String current = currentVersion();
			int comparison;
			try {
				comparison = compare(version, current);
			} catch (NumberFormatException exception) {
				throw new UpdateException("bad version");
			}
			if (comparison <= 0) {
				throw new UpdateException("not newer than " + current);
			}
			if (notes.length() > MAX_NOTES) {
				notes = notes.substring(0, MAX_NOTES);
			}
			return new Info(version, agentUrl, agentSha256, modUrl, modSha256, notes);
		} catch (IOException exception) {
			throw new UpdateException("network: " + exception.getMessage());
		}
	}

	public static Path stage(Info info, Path dir) throws UpdateException {
		synchronized (STAGE_LOCK) {
			Path target = dir.resolve(STAGED_NAME);
			Path temp = dir.resolve(TEMP_NAME);
			try {
				Files.createDirectories(dir);
				Files.deleteIfExists(temp);
				byte[] jar = fetch(checkedUrl(info.agentUrl), MAX_AGENT);
				String digest = sha256(jar);
				if (!MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),
					info.agentSha256.getBytes(StandardCharsets.US_ASCII))) {
					throw new UpdateException("sha mismatch");
				}
				Files.write(temp, jar);
				validateJar(temp, info.version);
				try {
					Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				} catch (IOException atomicFailed) {
					Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
				}
				return target;
			} catch (IOException exception) {
				delete(temp);
				throw new UpdateException("io: " + exception.getMessage());
			} catch (UpdateException exception) {
				delete(temp);
				throw exception;
			}
		}
	}

	public static String status() {
		Path file = logFile();
		if (!Files.isRegularFile(file)) {
			return "No update attempts yet";
		}
		try (RandomAccessFile reader = new RandomAccessFile(file.toFile(), "r")) {
			long length = reader.length();
			int window = (int) Math.min(length, STATUS_WINDOW);
			reader.seek(length - window);
			byte[] bytes = new byte[window];
			reader.readFully(bytes);
			String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n");
			StringBuilder out = new StringBuilder();
			int first = Math.max(0, lines.length - STATUS_LINES);
			for (int i = first; i < lines.length; i++) {
				if (!lines[i].isBlank()) {
					out.append(lines[i]).append('\n');
				}
			}
			return out.length() == 0 ? "No update attempts yet" : out.toString().trim();
		} catch (IOException exception) {
			return "Update log unreadable";
		}
	}

	public static void log(String version, String sha256, String status) {
		synchronized (LOG_LOCK) {
			try {
				Path file = logFile();
				Files.createDirectories(file.getParent());
				rotate(file);
				String line = Instant.now() + " version=" + safe(version) + " sha=" + safe(sha256)
					+ " status=" + safe(status) + "\n";
				Files.writeString(file, line, StandardCharsets.UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			} catch (Throwable throwable) {
				Log.debug("Update log failed", throwable);
			}
		}
	}

	private static void rotate(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return;
		}
		long length = Files.size(file);
		if (length <= LOG_MAX) {
			return;
		}
		try (RandomAccessFile journal = new RandomAccessFile(file.toFile(), "rw")) {
			long from = length - LOG_KEEP;
			journal.seek(from);
			byte[] tail = new byte[LOG_KEEP];
			journal.readFully(tail);
			int firstLine = 0;
			while (firstLine < tail.length && tail[firstLine] != '\n') {
				firstLine++;
			}
			if (firstLine < tail.length) {
				firstLine++;
			}
			journal.setLength(0);
			journal.seek(0);
			journal.write(tail, firstLine, tail.length - firstLine);
		}
	}

	public static String currentVersion() {
		String version = Update.class.getPackage().getImplementationVersion();
		if (version == null || version.isBlank()) {
			version = System.getProperty("spc.currentVersion");
		}
		return version == null || version.isBlank() ? "0.0.0" : version;
	}

	static int compare(String left, String right) {
		String[] a = left.split("\\.");
		String[] b = right.split("\\.");
		for (int i = 0; i < 3; i++) {
			int difference = Integer.parseInt(a[i]) - Integer.parseInt(b[i]);
			if (difference != 0) {
				return difference;
			}
		}
		return 0;
	}

	static URL checkedUrl(String spec) throws UpdateException {
		URL url;
		try {
			url = new URL(spec);
		} catch (MalformedURLException exception) {
			throw new UpdateException("bad url");
		}
		String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
		if (protocol.equals("https")) {
			return url;
		}
		if (protocol.equals("http")) {
			String host = url.getHost().toLowerCase(Locale.ROOT);
			if (host.equals("127.0.0.1") || host.equals("localhost")) {
				return url;
			}
		}
		throw new UpdateException("unsafe url: " + spec);
	}

	private static String manifestUrl() {
		String override = System.getProperty("spc.updateManifest");
		return override == null || override.isBlank() ? DiscordConfig.UPDATE_MANIFEST_URL : override;
	}

	private static URL signatureUrl(URL manifest) throws UpdateException {
		try {
			URI uri = manifest.toURI();
			String path = uri.getPath();
			if (path == null || !path.endsWith(".json")) {
				throw new UpdateException("bad manifest url");
			}
			return new URI(uri.getScheme(), uri.getAuthority(), path + ".sig", uri.getQuery(), uri.getFragment())
				.toURL();
		} catch (UpdateException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new UpdateException("bad manifest url");
		}
	}

	private static Path logFile() {
		String override = System.getProperty("spc.updateLog");
		return override == null || override.isBlank()
			? AgentPaths.installDir().resolve("update.log")
			: Path.of(override);
	}

	private static String required(Map<String, Object> json, String key) throws UpdateException {
		Object value = json.get(key);
		if (!(value instanceof String text) || text.isBlank()) {
			throw new UpdateException("missing " + key);
		}
		return text;
	}

	private static void validateSha(String sha256) throws UpdateException {
		if (!SHA256.matcher(sha256).matches()) {
			throw new UpdateException("bad sha");
		}
	}

	private static byte[] fetch(URL url, int max) throws IOException, UpdateException {
		HttpURLConnection connection = (HttpURLConnection) url.openConnection();
		try {
			connection.setConnectTimeout(TIMEOUT_MS);
			connection.setReadTimeout(TIMEOUT_MS);
			connection.setInstanceFollowRedirects(true);
			connection.setUseCaches(false);
			connection.setRequestProperty("User-Agent", "ServerPackCore");
			connection.setRequestProperty("Cache-Control", "no-cache");
			connection.setRequestProperty("Accept", "application/json, application/octet-stream");
			int code = connection.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				throw new UpdateException("http " + code);
			}
			if (connection.getContentLengthLong() > max) {
				throw new UpdateException("too large");
			}
			try (InputStream input = connection.getInputStream()) {
				return readLimited(input, max);
			}
		} finally {
			connection.disconnect();
		}
	}

	private static byte[] readLimited(InputStream input, int max) throws IOException, UpdateException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		int total = 0;
		int read;
		while ((read = input.read(buffer)) != -1) {
			total += read;
			if (total > max) {
				throw new UpdateException("too large");
			}
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	private static void verify(byte[] manifest, byte[] signature) throws UpdateException {
		try {
			Signature verifier = Signature.getInstance("SHA256withRSA");
			verifier.initVerify(publicKey());
			verifier.update(manifest);
			if (!verifier.verify(signature)) {
				throw new UpdateException("bad signature");
			}
		} catch (UpdateException exception) {
			throw exception;
		} catch (Throwable throwable) {
			throw new UpdateException("bad signature");
		}
	}

	private static PublicKey publicKey() throws UpdateException {
		String override = System.getProperty("spc.updatePublicKey");
		boolean fromResource = override == null || override.isBlank();
		String id = fromResource ? KEY_RESOURCE : override;
		PublicKey cached = KEYS.get(id);
		if (cached != null) {
			return cached;
		}
		try {
			byte[] pem;
			if (fromResource) {
				try (InputStream input = Update.class.getResourceAsStream(id)) {
					if (input == null) {
						throw new UpdateException("public key missing");
					}
					pem = input.readAllBytes();
				}
			} else {
				pem = Files.readAllBytes(Path.of(id));
			}
			String body = new String(pem, StandardCharsets.US_ASCII)
				.replace("-----BEGIN PUBLIC KEY-----", "")
				.replace("-----END PUBLIC KEY-----", "")
				.replaceAll("\\s", "");
			PublicKey key = KeyFactory.getInstance("RSA")
				.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
			KEYS.put(id, key);
			return key;
		} catch (UpdateException exception) {
			throw exception;
		} catch (Throwable throwable) {
			throw new UpdateException("public key unreadable");
		}
	}

	private static void validateJar(Path jar, String version) throws UpdateException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (String required : new String[] { "META-INF/MANIFEST.MF", AGENT_CLASS, PREMAIN_CLASS }) {
				ZipEntry entry = zip.getEntry(required);
				if (entry == null) {
					throw new UpdateException("jar missing " + required);
				}
			}
			Manifest manifest;
			ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
			long manifestSize = manifestEntry.getSize();
			if (manifestSize > MAX_MANIFEST) {
				throw new UpdateException("manifest too large");
			}
			try (InputStream input = zip.getInputStream(manifestEntry)) {
				byte[] bytes = readLimited(input, MAX_MANIFEST);
				manifest = new Manifest(new ByteArrayInputStream(bytes));
			}
			Attributes attributes = manifest.getMainAttributes();
			if (!AGENT_MAIN.equals(attributes.getValue("Main-Class"))) {
				throw new UpdateException("bad Main-Class");
			}
			if (!AGENT_PREMAIN.equals(attributes.getValue("Premain-Class"))) {
				throw new UpdateException("bad Premain-Class");
			}
			if (!version.equals(attributes.getValue("Implementation-Version"))) {
				throw new UpdateException("jar version mismatch");
			}
		} catch (UpdateException exception) {
			throw exception;
		} catch (IOException exception) {
			throw new UpdateException("bad jar");
		}
	}

	private static String sha256(byte[] bytes) throws UpdateException {
		try {
			StringBuilder out = new StringBuilder(64);
			for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
				out.append(String.format("%02x", value));
			}
			return out.toString();
		} catch (Throwable throwable) {
			throw new UpdateException("sha unavailable");
		}
	}

	private static void delete(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException exception) {
			Log.debug("Update cleanup failed", exception);
		}
	}

	private static String safe(String value) {
		return value == null ? "-" : value.replace('\n', ' ').replace('\r', ' ');
	}
}
