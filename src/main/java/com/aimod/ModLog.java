package com.aimod;

import net.minecraft.client.MinecraftClient;

/** Logging helper. ".m logs on" mirrors the debug log into the game chat. */
public final class ModLog {
	private ModLog() {}

	/** Debug line: always to the launcher log, to chat only when ".m logs on". */
	public static void debug(String text) {
		AiModerator.LOGGER.info("[AI Moderator] {}", text);
		if (Config.get().logsEnabled) {
			send("\u00A78[log] \u00A77" + text);
		}
	}

	/** Warning: always to the launcher log, to chat only when ".m logs on". */
	public static void warn(String text) {
		AiModerator.LOGGER.warn("[AI Moderator] {}", text);
		if (Config.get().logsEnabled) {
			send("\u00A78[log] \u00A7c" + text);
		}
	}

	/** Error the moderator must see even with logs off (bad key, no key, HTTP 401...). */
	public static void error(String text) {
		AiModerator.LOGGER.error("[AI Moderator] {}", text);
		send("\u00A7c[AI Moderator] \u00A7f" + text);
	}

	private static void send(String formatted) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		client.execute(() -> {
			if (client.player != null) {
				client.player.sendMessage(net.minecraft.text.Text.literal(formatted), false);
			}
		});
	}
}
