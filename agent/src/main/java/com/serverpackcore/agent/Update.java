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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
	public static final String OLD_NAME = "spc-agent.jar.old";
	public static final String BAD_NAME = "spc-agent.jar.bad";
	public static final String MOD_STAGED_NAME = "spc-mod-new.jar";
	public static final String MOD_TEMP_NAME = "spc-mod-new.jar.tmp";
	public static final String ROLLBACK_MARKER = "spc-rollback.marker";

	private static final String KEY_RESOURCE = "/serverpackcore/update-public.pem";
	private static final String AGENT_CLASS = "com/serverpackcore/agent/Agent.class";
	private static final String PREMAIN_CLASS = "com/serverpackcore/agent/Premain.class";
	private static final String AGENT_MAIN = "com.serverpackcore.agent.Agent";
	private static final String AGENT_PREMAIN = "com.serverpackcore.agent.Premain";

	private static final int TIMEOUT_MS = 10000;
	private static final int MAX_MANIFEST = 64 * 1024;
	private static final int MAX_SIGNATURE = 8 * 1024;
	private static final int MAX_AGENT = 4 * 1024 * 1024;
	private static final int MAX_MOD = 4 * 1024 * 1024;
	private static final int MAX_CLASS = 1024 * 1024;
	private static final int MAX_NOTES = 4000;
	private static final int STATUS_WINDOW = 8 * 1024;
	private static final int STATUS_LINES = 10;

	private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");
	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Map<String, PublicKey> KEYS = new ConcurrentHashMap<>();
	private static final Object STAGE_LOCK = new Object();
	private static final Object LOG_LOCK = new Object();
	private static final Object APPLY_LOCK = new Object();
	private static final AtomicBoolean GATE = new AtomicBoolean();
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
		return check(true);
	}

	/** Same validation but an equal version is acceptable, for repairing the mod. */
	public static Info checkSame() throws UpdateException {
		return check(false);
	}

	private static Info check(boolean newerOnly) throws UpdateException {
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
			if (newerOnly && comparison <= 0) {
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

	/** One update at a time, manual or automatic. The loser must not touch state. */
	public static boolean begin() {
		return GATE.compareAndSet(false, true);
	}

	public static void end() {
		GATE.set(false);
	}

	public static Info checkIfNewer() throws UpdateException {
		try {
			return check();
		} catch (UpdateException exception) {
			if (exception.getMessage() != null && exception.getMessage().startsWith("not newer")) {
				return null;
			}
			throw exception;
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
				moveReplacing(temp, target);
				stageMod(info, dir);
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

	private static void stageMod(Info info, Path dir) throws UpdateException {
		Path target = dir.resolve(MOD_STAGED_NAME);
		Path temp = dir.resolve(MOD_TEMP_NAME);
		try {
			Files.deleteIfExists(temp);
			byte[] jar = fetch(checkedUrl(info.modUrl), MAX_MOD);
			String digest = sha256(jar);
			if (!MessageDigest.isEqual(digest.getBytes(StandardCharsets.US_ASCII),
				info.modSha256.getBytes(StandardCharsets.US_ASCII))) {
				throw new UpdateException("mod sha mismatch");
			}
			Files.write(temp, jar);
			validateMod(temp, info.version);
			moveReplacing(temp, target);
		} catch (IOException exception) {
			delete(temp);
			throw new UpdateException("io: " + exception.getMessage());
		} catch (UpdateException exception) {
			delete(temp);
			throw exception;
		}
	}

	public static Path apply(Path dir, Path gameDir, String version) throws UpdateException {
		synchronized (APPLY_LOCK) {
			Path helper = prepareAgent(dir, gameDir, version);
			try {
				if (gameDir != null) {
					applyModLocked(dir, gameDir, version);
				}
			} catch (UpdateException exception) {
				delete(helper);
				throw exception;
			}
			UpdateHelper.launch(dir, helper);
			return helper;
		}
	}

	private static Path prepareAgent(Path dir, Path gameDir, String version) throws UpdateException {
		Path staged = dir.resolve(STAGED_NAME);
		if (!Files.isRegularFile(staged)) {
			throw new UpdateException("nothing staged");
		}
		if (!VERSION.matcher(version == null ? "" : version).matches()) {
			throw new UpdateException("bad version");
		}
		validateJar(staged, version);
		if (compare(version, currentVersion()) <= 0) {
			throw new UpdateException("not newer than " + currentVersion());
		}
		return UpdateHelper.write(dir, UpdateHelper.MODE_UPDATE, version, gameDir);
	}

	public static Path rollback(Path dir, Path gameDir) throws UpdateException {
		synchronized (APPLY_LOCK) {
			Path old = dir.resolve(OLD_NAME);
			if (!Files.isRegularFile(old)) {
				throw new UpdateException("no previous version");
			}
			String version = jarVersion(old);
			validateJar(old, version);
			Path helper = UpdateHelper.write(dir, UpdateHelper.MODE_ROLLBACK, version, gameDir);
			try {
				rollbackModLocked(dir, gameDir);
			} catch (UpdateException exception) {
				delete(helper);
				throw exception;
			}
			UpdateHelper.launch(dir, helper);
			return helper;
		}
	}

	/** Brings the installed mod to the running version when it lags behind. The caller holds the gate. */
	public static void syncMod(Path dir, Path gameDir) throws UpdateException {
		Info info = checkSame();
		if (!currentVersion().equals(info.version)) {
			return;
		}
		stageMod(info, dir);
		applyMod(dir, gameDir, info.version);
	}

	static boolean installedModMatches(Path gameDir, String version) {
		try (java.nio.file.DirectoryStream<Path> stream =
			Files.newDirectoryStream(gameDir.resolve("mods"), "serverpackcore-*.jar")) {
			for (Path jar : stream) {
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					ZipEntry entry = zip.getEntry("fabric.mod.json");
					if (entry == null) {
						continue;
					}
					try (InputStream input = zip.getInputStream(entry)) {
						Map<String, Object> json =
							Json.parseObject(new String(readLimited(input, MAX_MANIFEST), StandardCharsets.UTF_8));
						if (version.equals(Json.str(json, "version", ""))) {
							return true;
						}
					}
				} catch (Throwable throwable) {
					Log.debug("Installed mod unreadable", throwable);
				}
			}
		} catch (IOException exception) {
			return false;
		}
		return false;
	}

	public static boolean applyMod(Path dir, Path gameDir, String version) throws UpdateException {		synchronized (APPLY_LOCK) {
			return applyModLocked(dir, gameDir, version);
		}
	}

	private static boolean applyModLocked(Path dir, Path gameDir, String version) throws UpdateException {
		Path staged = dir.resolve(MOD_STAGED_NAME);
		if (!Files.isRegularFile(staged) || gameDir == null) {
			return false;
		}
		if (!VERSION.matcher(version == null ? "" : version).matches()) {
			throw new UpdateException("bad version");
		}
		validateMod(staged, version);
		try {
			Path mods = gameDir.resolve("mods");
			if (!Files.isDirectory(mods)) {
				Files.createDirectories(mods);
			}
			Path target = mods.resolve("serverpackcore-" + version + ".jar");
			if (gameRunning(dir)) {
				UpdateHelper.launch(dir,
					UpdateHelper.writeMod(dir, mods, staged, target, version, UpdateHelper.MODE_UPDATE));
				return true;
			}
			swapMod(mods, staged, target);
			log(version, "-", "mod-installed");
			delete(staged);
			return true;
		} catch (IOException exception) {
			throw new UpdateException("io: " + exception.getMessage());
		}
	}

	public static boolean rollbackMod(Path dir, Path gameDir) throws UpdateException {
		synchronized (APPLY_LOCK) {
			return rollbackModLocked(dir, gameDir);
		}
	}

	private static boolean rollbackModLocked(Path dir, Path gameDir) throws UpdateException {
		if (gameDir == null) {
			return false;
		}
		Path mods = gameDir.resolve("mods");
		if (!hasModOld(mods)) {
			return false;
		}
		try {
			if (gameRunning(dir)) {
				UpdateHelper.launch(dir,
					UpdateHelper.writeMod(dir, mods, dir.resolve(MOD_STAGED_NAME), mods, "", UpdateHelper.MODE_ROLLBACK));
				return true;
			}
			restoreMod(mods);
			log("-", "-", "mod-rollback");
			return true;
		} catch (IOException exception) {
			throw new UpdateException("io: " + exception.getMessage());
		}
	}

	private static void swapMod(Path mods, Path staged, Path target) throws IOException {
		Path bad = target.resolveSibling(target.getFileName() + ".bad");
		Files.deleteIfExists(bad);
		if (Files.isRegularFile(target)) {
			Path old = target.resolveSibling(target.getFileName() + ".old");
			Files.deleteIfExists(old);
			Files.move(target, old, StandardCopyOption.REPLACE_EXISTING);
		}
		List<Path> extra = new ArrayList<>();
		try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "serverpackcore-*.jar")) {
			for (Path path : stream) {
				if (!path.equals(target)) {
					extra.add(path);
				}
			}
		}
		for (Path path : extra) {
			Path old = path.resolveSibling(path.getFileName() + ".old");
			Files.deleteIfExists(old);
			Files.move(path, old, StandardCopyOption.REPLACE_EXISTING);
		}
		moveReplacing(staged, target);
	}

	private static void restoreMod(Path mods) throws IOException {
		Path newest = null;
		try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "serverpackcore-*.jar.old")) {
			for (Path path : stream) {
				if (newest == null || Files.getLastModifiedTime(path).compareTo(Files.getLastModifiedTime(newest)) > 0) {
					newest = path;
				}
			}
		}
		if (newest == null) {
			return;
		}
		Path current = null;
		try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "serverpackcore-*.jar")) {
			for (Path path : stream) {
				current = path;
				break;
			}
		}
		if (current != null) {
			Files.move(current, current.resolveSibling(current.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
		}
		Path restored = newest.resolveSibling(newest.getFileName().toString().replaceAll("\\.old$", ""));
		moveReplacing(newest, restored);
	}

	private static boolean hasModOld(Path mods) {
		if (!Files.isDirectory(mods)) {
			return false;
		}
		try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "serverpackcore-*.jar.old")) {
			return stream.iterator().hasNext();
		} catch (IOException exception) {
			return false;
		}
	}

	static boolean gameRunning(Path dir) {
		Path alive = dir.resolve("alive.txt");
		try {
			if (!Files.isRegularFile(alive)) {
				return false;
			}
			for (String line : Files.readAllLines(alive, StandardCharsets.UTF_8)) {
				if (line.startsWith("parent=")) {
					long pid = Long.parseLong(line.substring(7).trim());
					return pid > 0L && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
				}
			}
		} catch (Throwable throwable) {
			Log.debug("Parent check failed", throwable);
		}
		return false;
	}

	private static void moveReplacing(Path from, Path to) throws IOException {
		try {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicFailed) {
			Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static String status() {
		synchronized (LOG_LOCK) {
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
			int keepFrom = firstLine < tail.length ? firstLine + 1 : 0;
			journal.setLength(0);
			journal.seek(0);
			journal.write(tail, keepFrom, tail.length - keepFrom);
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

	static String jarVersion(Path jar) throws UpdateException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
			if (entry == null) {
				throw new UpdateException("jar missing META-INF/MANIFEST.MF");
			}
			String version;
			try (InputStream input = zip.getInputStream(entry)) {
				version = new Manifest(input).getMainAttributes().getValue("Implementation-Version");
			}
			if (version == null || !VERSION.matcher(version).matches()) {
				throw new UpdateException("jar version missing");
			}
			return version;
		} catch (UpdateException exception) {
			throw exception;
		} catch (IOException exception) {
			throw new UpdateException("bad jar");
		}
	}

	static void validateJar(Path jar, String version) throws UpdateException {
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

	static void validateMod(Path jar, String version) throws UpdateException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry metadata = zip.getEntry("fabric.mod.json");
			if (metadata == null) {
				throw new UpdateException("mod missing fabric.mod.json");
			}
			if (metadata.getSize() > MAX_MANIFEST) {
				throw new UpdateException("mod metadata too large");
			}
			Map<String, Object> json;
			try (InputStream input = zip.getInputStream(metadata)) {
				byte[] bytes = readLimited(input, MAX_MANIFEST);
				json = Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
			} catch (UpdateException exception) {
				throw exception;
			} catch (Throwable throwable) {
				throw new UpdateException("bad fabric.mod.json");
			}
			if (!"serverpackcore".equals(Json.str(json, "id", ""))) {
				throw new UpdateException("wrong mod id");
			}
			if (!version.equals(Json.str(json, "version", ""))) {
				throw new UpdateException("mod version mismatch");
			}
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) {
					continue;
				}
				try (InputStream input = zip.getInputStream(entry)) {
					String constantPool = new String(readLimited(input, MAX_CLASS), StandardCharsets.ISO_8859_1);
					if (constantPool.contains("com/serverpackcore/agent/")) {
						throw new UpdateException("mod depends on agent");
					}
				}
			}
		} catch (UpdateException exception) {
			throw exception;
		} catch (IOException exception) {
			throw new UpdateException("bad mod");
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
