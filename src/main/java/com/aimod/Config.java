package com.aimod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persisted client settings. Stored in config/aimoderator/config.json */
public class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String apiEndpoint = "https://api.openai.com/v1/chat/completions";
	public String apiKey = "";
	public String model = "gpt-4o-mini";
	public boolean chatAnalysisEnabled = false;

	/** ".m logs on/off" — mirror debug logs into the game chat. */
	public boolean logsEnabled = false;

	/**
	 * "auto" (default) — smart parser: understands "PLAYER Nick → text", "HELPER ~Nick SUPPORT → text",
	 * "[VIP] Nick: text", "&lt;Nick&gt; text".
	 * "regex" — use chatRegex only (group 1 = nick, group 2 = message).
	 */
	public String chatParseMode = "auto";

	/** Optional custom regex. Empty by default so the auto parser is used. */
	public String chatRegex = "";

	/** Seconds between AI requests, protects from spam / rate limits. Messages are queued, not dropped. */
	public double minRequestIntervalSeconds = 1.0;

	/** How many AI requests may run at the same time. */
	public int maxConcurrentRequests = 2;

	/** Max queued messages waiting for analysis. */
	public int maxQueueSize = 20;

	public int maxMessageLength = 256;
	public int requestTimeoutSeconds = 25;
	public boolean showHudNotification = true;
	public boolean showChatNotification = true;
	public int hudSeconds = 30;

	/** Command sent on ".m c". Placeholders: {nick} {duration} {rule} {reason} */
	public String muteCommandTemplate = "tempmute {nick} {duration} {rule}";

	/** Some endpoints/models reject "temperature"; it is dropped automatically on a 400 error. */
	public boolean sendTemperature = true;

	private static Config instance;

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve("aimoderator");
	}

	public static Path file() {
		return dir().resolve("config.json");
	}

	public static Path rulesFile() {
		return dir().resolve("rules.txt");
	}

	public static Config get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Config load() {
		try {
			if (Files.exists(file())) {
				String json = Files.readString(file(), StandardCharsets.UTF_8);
				Config cfg = GSON.fromJson(json, Config.class);
				if (cfg != null) {
					cfg.migrate();
					return cfg;
				}
			}
		} catch (Exception e) {
			AiModerator.LOGGER.warn("[AI Moderator] Failed to read config", e);
		}
		Config cfg = new Config();
		cfg.save();
		return cfg;
	}

	/** Repairs configs written by older versions (the old hardcoded regex never matched real servers). */
	private void migrate() {
		if (chatParseMode == null || chatParseMode.isBlank()) chatParseMode = "auto";
		if (muteCommandTemplate == null || muteCommandTemplate.isBlank()) {
			muteCommandTemplate = "tempmute {nick} {duration} {rule}";
		}
		if (model == null || model.isBlank()) model = "gpt-4o-mini";
		if (maxConcurrentRequests <= 0) maxConcurrentRequests = 2;
		if (maxQueueSize <= 0) maxQueueSize = 20;
		if (maxMessageLength <= 0) maxMessageLength = 256;
		if (requestTimeoutSeconds <= 0) requestTimeoutSeconds = 25;
		if (minRequestIntervalSeconds < 0) minRequestIntervalSeconds = 1.0;

		// The broken legacy default regex is dropped so the auto parser takes over.
		if (chatRegex != null && chatRegex.contains("([A-Za-z0-9_]{3,16})>?")) {
			chatRegex = "";
			save();
		}
	}

	public void save() {
		try {
			Files.createDirectories(dir());
			Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (Exception e) {
			AiModerator.LOGGER.warn("[AI Moderator] Failed to save config", e);
		}
	}

	public String loadRules() {
		try {
			if (Files.exists(rulesFile())) {
				String text = Files.readString(rulesFile(), StandardCharsets.UTF_8);
				if (!text.isBlank()) return text;
			}
			Files.createDirectories(dir());
			Files.writeString(rulesFile(), DEFAULT_RULES, StandardCharsets.UTF_8);
		} catch (Exception e) {
			AiModerator.LOGGER.warn("[AI Moderator] Failed to read rules", e);
		}
		return DEFAULT_RULES;
	}

	public void saveRules(String rules) {
		try {
			Files.createDirectories(dir());
			Files.writeString(rulesFile(), rules, StandardCharsets.UTF_8);
		} catch (Exception e) {
			AiModerator.LOGGER.warn("[AI Moderator] Failed to save rules", e);
		}
	}

	/**
	 * Last Hero — только чатовые правила (раздел 1) с наказанием МУТ.
	 */
	public static final String DEFAULT_RULES = """
			# Last Hero — правила чата с наказанием МУТ
			# Формат: номер | срок мута | описание

			1.1 | 30m | Флуд / спам / капс в любом проявлении
			1.2 | 1h | Любые оскорбления игроков
			1.3 | 3d | Оскорбление проекта
			1.5 | 2h | Провокация игроков на флуд и нарушение правил
			1.6 | 3h | Разжигание конфликта, пропаганда ненависти или дискриминации (социальной, расовой, национальной, религиозной)
			1.7 | 2h | Упоминание сторонних серверов по Minecraft
			1.8 | 6h | Оскорбление родных и близких игроков
			1.9 | 6h | Оскорбление или неуважительное общение с членами команды проекта
			1.10 | 1h | Лексика сексуального или аморального характера
			1.12 | 1h | Обсуждение наказания, выданного командой проекта
			1.13 | 30m | Попрошайничество в любом виде
			1.16 | 2h | Введение игроков в заблуждение
			""";
}
