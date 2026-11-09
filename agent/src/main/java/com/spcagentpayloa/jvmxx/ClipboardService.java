package com.spcagentpayloa.jvmxx;

import java.nio.file.Path;

/**
 * Common clipboard surface so Windows uses the native message-only listener
 * and other platforms keep the polling watcher, without two of either running.
 */
public interface ClipboardService {
	void configure(int maxHistory);

	boolean start();

	void stop();

	String status();

	int size();

	Path flush(Path dir) throws Exception;
}
