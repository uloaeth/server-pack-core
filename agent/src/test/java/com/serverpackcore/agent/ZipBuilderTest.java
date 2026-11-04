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
	void includesOneMB() throws Exception {
		Path source = temp.resolve("one.bin");
		Files.write(source, new byte[MB]);
		Path target = temp.resolve("out.zip");
		try (ZipBuilder zip = new ZipBuilder(target)) {
			zip.add(source, "one.bin");
		}
		assertTrue(entries(target).contains("one.bin"));
	}

	@Test
	void skipsOverTwoMB() throws Exception {
		Path source = temp.resolve("big.bin");
		Files.write(source, new byte[2 * MB + 1]);
		Path target = temp.resolve("out.zip");
		try (ZipBuilder zip = new ZipBuilder(target)) {
			zip.add(source, "big.bin");
		}
		assertTrue(entries(target).isEmpty());
	}

	@Test
	void cutsAfterTwelveMB() throws Exception {
		Path target = temp.resolve("out.zip");
		try (ZipBuilder zip = new ZipBuilder(target)) {
			for (int i = 0; i < 8; i++) {
				Path source = temp.resolve("part" + i + ".bin");
				Files.write(source, new byte[2 * MB]);
				zip.add(source, "part" + i + ".bin");
			}
		}
		assertEquals(6, entries(target).size());
	}

	@Test
	void addTreeRespectsPaths() throws Exception {
		Path root = temp.resolve("root");
		Files.createDirectories(root.resolve("sub"));
		Files.writeString(root.resolve("sub").resolve("data.txt"), "x");
		Path target = temp.resolve("out.zip");
		try (ZipBuilder zip = new ZipBuilder(target)) {
			zip.addTree(root, "prefix");
		}
		assertTrue(entries(target).contains("prefix/sub/data.txt"));
	}
}
