package com.serverpackcore.stager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The canonical digest must cover execution surface, not signature wall clock. */
class AgentDigestTest {
	@TempDir
	Path temp;

	private Path jar(String manifest, String services, String signature) throws Exception {
		Path target = Files.createTempFile(temp, "agent-", ".jar");
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put("META-INF/MANIFEST.MF", manifest);
		if (services != null) entries.put("META-INF/services/x.Service", services);
		entries.put("com/serverpackcore/agent/Agent.class", "bytecode");
		if (signature != null) entries.put("META-INF/SPC.SF", signature);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
			for (Map.Entry<String, String> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return target;
	}

	@Test
	void changingManifestOrServicesChangesTheHash() throws Exception {
		String manifest = "Manifest-Version: 1.0\nMain-Class: a\n";
		Path base = jar(manifest, "impl\n", "signature-one");
		String hash = Stager.hashFile(base);

		Path manifestChanged = jar("Manifest-Version: 1.0\nMain-Class: b\n", "impl\n", "signature-one");
		assertNotEquals(hash, Stager.hashFile(manifestChanged));

		Path servicesChanged = jar(manifest, "other\n", "signature-one");
		assertNotEquals(hash, Stager.hashFile(servicesChanged));
	}

	@Test
	void digestLinesAndSignatureFilesAreExcluded() throws Exception {
		String head = "Manifest-Version: 1.0\nMain-Class: a\n";
		Path first = jar(head + "\nName: x\nSHA-256-Digest: abc\n", "impl\n", "signature-one");
		Path second = jar(head + "\nName: x\nSHA-256-Digest: def\n", "impl\n", "signature-two");
		assertEquals(Stager.hashFile(first), Stager.hashFile(second));
	}
}
