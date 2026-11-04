package com.serverpackcore;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ServerPackCoreClient implements ClientModInitializer {
	public static final String MOD_ID = "serverpackcore";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		try {
			LOGGER.debug("Server Pack Core initialized");
		} catch (Throwable throwable) {
			LOGGER.debug("Client initialization failed", throwable);
		}
	}
}
