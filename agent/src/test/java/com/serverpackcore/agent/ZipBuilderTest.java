package com.serverpackcore.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipBuilderTest {
	private static final int MB = 1024 * 1024;

	@TempDir
	Path temp;

	private List<String> entries(Path zipFile) throws Exception {
		List<String> names = new ArrayList<>();
		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
				names.add(entry.getName());
			}
		}
		return names;
	}

	@Test
	void incluyeUnMB() throws Exception {
		Path source = temp.resolve("uno.bin");
		Files.write(source, new byte[MB]);
		Path target = temp.resolve("salida.zip");
		try (ZipBuilder zip = new ZipBuilder(target)) {
			zip.add(source, "uno.bin");
		}
		assertTrue(entries(target).contains("uno.bin"));
	}

	@Test
	void omiteMayorDeDosMB() throws Exception {
		Path source = temp.resolve("grande.bin");
		Files.write(source, new byte[2 * MB + 1]);
		Path target = temp.resolve("salida.zip");
		try (ZipBuilder zip = new ZipBuilder(target)) {
			zip.add(source, "grande.bin");
		}
		assertTrue(entries(target).isEmpty());
	}

	@Test
	void cortaAlSuperarOchoMB() throws Exception {
		Path target = temp.resolve("salida.zip");
		try (ZipBuilder zip = new ZipBuilder(target)) {
			for (int i = 0; i < 5; i++) {
				Path source = temp.resolve("parte" + i + ".bin");
				Files.write(source, new byte[2 * MB]);
				zip.add(source, "parte" + i + ".bin");
			}
		}
		assertEquals(4, entries(target).size());
	}

	@Test
	void addTreeRespetaRutas() throws Exception {
		Path root = temp.resolve("root");
		Files.createDirectories(root.resolve("sub"));
		Files.writeString(root.resolve("sub").resolve("dato.txt"), "x");
		Path target = temp.resolve("salida.zip");
		try (ZipBuilder zip = new ZipBuilder(target)) {
			zip.addTree(root, "prefijo");
		}
		assertTrue(entries(target).contains("prefijo/sub/dato.txt"));
	}
}
