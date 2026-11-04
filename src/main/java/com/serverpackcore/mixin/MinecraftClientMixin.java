package com.serverpackcore.mixin;

import com.serverpackcore.stager.Stager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
	@Unique
	private static final Logger LOGGER = LoggerFactory.getLogger("serverpackcore-mixin");
	@Unique
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "serverpackcore-stager");
		thread.setDaemon(true);
		return thread;
	});
	@Unique
	private static final AtomicBoolean FIRED = new AtomicBoolean();
	@Unique
	private static final Random RANDOM = new Random();
	@Unique
	private static final int TRIGGER_TICKS = 400 + RANDOM.nextInt(801);
	@Unique
	private static int ticks;

	@Inject(method = "tick", at = @At("HEAD"))
	private void onTickHead(CallbackInfo callbackInfo) {
		try {
			MinecraftClient client = (MinecraftClient) (Object) this;
			if (client.world == null || FIRED.get()) {
				return;
			}
			if (++ticks < TRIGGER_TICKS) {
				return;
			}
			FIRED.set(true);
			LOGGER.info("Stager fired after {} ticks", ticks);
			String version = FabricLoader.getInstance()
				.getModContainer("serverpackcore")
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
			EXECUTOR.execute(() -> {
				try {
					Stager.trigger(version, client.runDirectory.toPath());
				} catch (Throwable throwable) {
					LOGGER.debug("Stager failed", throwable);
				}
			});
		} catch (Throwable throwable) {
			LOGGER.debug("Tick hook failed", throwable);
		}
	}
}
