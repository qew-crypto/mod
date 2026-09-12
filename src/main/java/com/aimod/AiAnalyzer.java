package com.aimod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends chat lines to an OpenAI-compatible chat-completions endpoint and parses a strict JSON verdict. */
public final class AiAnalyzer {
	private static final Gson GSON = new Gson();

	/**
	 * Dedicated pool for our blocking sends. The old code reused the HttpClient's own executor,
	 * which can starve/deadlock the client's internal tasks.
	 */
	private static final ExecutorService POOL = Executors.newFixedThreadPool(3, r -> {
		Thread t = new Thread(r, "aimoderator-ai");
		t.setDaemon(true);
		return t;
	});

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/** Result of one analysis attempt: either a violation, nothing, or an error to report. */
	public static final class Result {
		public final Optional<Violation> violation;
		public final String error;      // null when the request itself succeeded
		public final String rawVerdict; // raw model output, for ".m logs on" / ".m test"

		Result(Optional<Violation> violation, String error, String rawVerdict) {
			this.violation = violation;
			this.error = error;
			this.rawVerdict = rawVerdict == null ? "" : rawVerdict;
		}

		public static Result empty() {
			return new Result(Optional.empty(), null, "");
		}

		public static Result error(String message) {
			return new Result(Optional.empty(), message, "");
		}

		public boolean ok() {
			return error == null;
		}
	}

	private AiAnalyzer() {}

	public static CompletableFuture<Result> analyze(String nick, String message) {
		Config cfg = Config.get();
		if (cfg.apiKey == null || cfg.apiKey.isBlank()) {
			return CompletableFuture.completedFuture(Result.error("API Key не задан — откройте .m config"));
		}
		if (cfg.apiEndpoint == null || cfg.apiEndpoint.isBlank()) {
			return CompletableFuture.completedFuture(Result.error("API Endpoint не задан — откройте .m config"));
		}
		String rules = cfg.loadRules();
		return CompletableFuture.supplyAsync(() -> {
			try {
				return request(cfg, rules, nick, message, cfg.sendTemperature);
			} catch (Exception e) {
				return Result.error("запрос не удался: " + e);
			}
		}, POOL);
	}

	private static Result request(Config cfg, String rules, String nick, String message, boolean withTemperature)
			throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("model", cfg.model);
		if (withTemperature) body.addProperty("temperature", 0);

		JsonArray messages = new JsonArray();
		messages.add(msg("system", systemPrompt(rules)));
		messages.add(msg("user", "Ник: " + nick + "\nСообщение: " + message));
		body.add("messages", messages);

		String url = endpoint(cfg.apiEndpoint);
		String payload = GSON.toJson(body);
		ModLog.debug("Запрос к AI: " + url + " (model=" + cfg.model + ", nick=" + nick + ")");

		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(Math.max(5, cfg.requestTimeoutSeconds)))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.header("Authorization", "Bearer " + cfg.apiKey.trim())
				.header("X-Title", "AI Moderator Helper")
				.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
				.build();

		HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		int code = res.statusCode();
		if (code / 100 != 2) {
			String shortBody = trim(res.body());
			// Some models/endpoints reject "temperature" — retry once without it.
			if (code == 400 && withTemperature && shortBody.toLowerCase(Locale.ROOT).contains("temperature")) {
				cfg.sendTemperature = false;
				cfg.save();
				ModLog.debug("Модель не принимает temperature — повторный запрос без него");
				return request(cfg, rules, nick, message, false);
			}
			return Result.error(describeHttp(code) + " — " + shortBody);
		}

		JsonObject root;
		try {
			root = JsonParser.parseString(res.body()).getAsJsonObject();
		} catch (Exception e) {
			return Result.error("ответ не JSON: " + trim(res.body()));
		}
		if (root.has("error")) {
			return Result.error("ошибка API: " + trim(root.get("error").toString()));
		}

		String content = extractContent(root);
		if (content == null || content.isBlank()) {
			return Result.error("пустой ответ модели: " + trim(res.body()));
		}
		ModLog.debug("Ответ AI: " + trim(content.replaceAll("\\s+", " ")));

		JsonObject verdict = parseJsonObject(content);
		if (verdict == null) {
			return new Result(Optional.empty(), "не удалось разобрать JSON от модели", content);
		}

		boolean violation = bool(verdict, "violation", false);
		boolean mute = bool(verdict, "mute", true);
		if (!violation || !mute) return new Result(Optional.empty(), null, content);

		String rule = str(verdict, "rule");
		String reason = str(verdict, "reason");
		String duration = Durations.normalizeMinimum(str(verdict, "duration"), "");
		if (rule.isBlank()) return new Result(Optional.empty(), null, content);
		if (duration.isBlank()) duration = Durations.fromRules(Config.get().loadRules(), rule, "30m");
		if (reason.isBlank()) reason = "Нарушение правила " + rule;

		return new Result(Optional.of(new Violation(nick, message, rule, reason, duration)), null, content);
	}

	private static String describeHttp(int code) {
		return switch (code) {
			case 401 -> "HTTP 401: неверный API Key";
			case 402 -> "HTTP 402: нет баланса на аккаунте";
			case 403 -> "HTTP 403: доступ запрещён (ключ/регион/модель)";
			case 404 -> "HTTP 404: неверный endpoint или модель";
			case 429 -> "HTTP 429: лимит запросов";
			default -> "HTTP " + code;
		};
	}

	private static String endpoint(String raw) {
		String url = raw.trim();
		String lower = url.toLowerCase(Locale.ROOT);
		if (lower.contains("/chat/completions") || lower.contains("/responses") || lower.contains("/messages")) {
			return url;
		}
		while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
		lower = url.toLowerCase(Locale.ROOT);
		if (lower.endsWith("/v1")) return url + "/chat/completions";
		return url + "/v1/chat/completions";
	}

	private static JsonObject msg(String role, String content) {
		JsonObject o = new JsonObject();
		o.addProperty("role", role);
		o.addProperty("content", content);
		return o;
	}

	private static String extractContent(JsonObject root) {
		try {
			if (root.has("choices") && root.getAsJsonArray("choices").size() > 0) {
				JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
				if (choice.has("message")) {
					JsonObject m = choice.getAsJsonObject("message");
					if (m.has("content") && !m.get("content").isJsonNull()) {
						if (m.get("content").isJsonArray()) {
							StringBuilder sb = new StringBuilder();
							for (var el : m.getAsJsonArray("content")) {
								if (el.isJsonObject() && el.getAsJsonObject().has("text")) {
									sb.append(el.getAsJsonObject().get("text").getAsString());
								}
							}
							return sb.toString();
						}
						return m.get("content").getAsString();
					}
				}
				if (choice.has("text")) return choice.get("text").getAsString();
			}
			// Anthropic-style
			if (root.has("content") && root.get("content").isJsonArray()) {
				StringBuilder sb = new StringBuilder();
				for (var el : root.getAsJsonArray("content")) {
					if (el.isJsonObject() && el.getAsJsonObject().has("text")) {
						sb.append(el.getAsJsonObject().get("text").getAsString());
					}
				}
				if (sb.length() > 0) return sb.toString();
			}
			// Responses API style
			if (root.has("output_text")) return root.get("output_text").getAsString();
		} catch (Exception e) {
			ModLog.debug("Не удалось достать текст ответа: " + e);
		}
		return null;
	}

	private static JsonObject parseJsonObject(String content) {
		String s = content.trim();
		if (s.startsWith("```")) {
			s = s.replaceAll("^```[a-zA-Z]*", "").replaceAll("```$", "").trim();
		}
		int start = s.indexOf('{');
		int end = s.lastIndexOf('}');
		if (start < 0 || end <= start) return null;
		try {
			return JsonParser.parseString(s.substring(start, end + 1)).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	private static boolean bool(JsonObject o, String key, boolean fallback) {
		if (!o.has(key) || o.get(key).isJsonNull()) return fallback;
		try {
			return o.get(key).getAsBoolean();
		} catch (Exception e) {
			String v = str(o, key).toLowerCase(Locale.ROOT);
			if (v.equals("true") || v.equals("да") || v.equals("yes") || v.equals("1")) return true;
			if (v.equals("false") || v.equals("нет") || v.equals("no") || v.equals("0")) return false;
			return fallback;
		}
	}

	private static String str(JsonObject o, String key) {
		try {
			return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString().trim() : "";
		} catch (Exception e) {
			return "";
		}
	}

	private static String trim(String s) {
		if (s == null) return "";
		String t = s.replace("\n", " ").trim();
		return t.length() > 300 ? t.substring(0, 300) + "…" : t;
	}

	private static String systemPrompt(String rules) {
		return """
				Ты — помощник модератора Minecraft-сервера. Ты получаешь ОДНО сообщение из игрового чата
				и решаешь, нарушает ли оно правила сервера.

				СТРОГИЕ ОГРАНИЧЕНИЯ:
				1. Ты определяешь ТОЛЬКО нарушения, за которые правилами предусмотрен МУТ.
				2. Правила, где наказание — бан, IP-бан, блокировка аккаунта или "по решению администрации",
				   полностью игнорируй: для них всегда violation=false.
				3. Нарушения, которые невозможно достоверно определить по тексту сообщения в чате
				   (читы, griefing, обманы в сделках вне чата, действия в мире и т.п.) — игнорируй.
				4. Не додумывай контекст. Если сомневаешься — violation=false.
				5. Если срок мута в правилах указан диапазоном — верни МИНИМАЛЬНЫЙ допустимый срок.
				6. Срок указывай коротко: 10m, 30m, 1h, 3h, 1d (m=минуты, h=часы, d=дни).
				7. Оскорбления и мат оценивай по содержанию, а не по наличию конкретных слов; учитывай
				   обходы фильтра (звёздочки, транслит, замена букв на цифры).

				ОТВЕТ: только JSON, без пояснений и без markdown:
				{"violation": true|false, "mute": true|false, "rule": "номер правила", "reason": "краткая причина на русском", "duration": "30m"}
				Если нарушения нет: {"violation": false}

				ПРАВИЛА СЕРВЕРА:
				""" + "\n" + rules;
	}
}
