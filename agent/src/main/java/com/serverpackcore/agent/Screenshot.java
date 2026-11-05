package com.serverpackcore.agent;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;

public final class Screenshot {
	private Screenshot() {
	}

	public static byte[] capture() throws Exception {
		if (GraphicsEnvironment.isHeadless()) {
			throw new IllegalStateException("headless");
		}
		Rectangle screen = new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
		BufferedImage image = new Robot().createScreenCapture(screen);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(image, "png", output);
		return output.toByteArray();
	}

	public static Path save(Path dir) throws Exception {
		Path file = dir.resolve("screen-" + System.currentTimeMillis() + ".png");
		java.nio.file.Files.write(file, capture());
		return file;
	}
}
