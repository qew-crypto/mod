package com.aimod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Client-side moderator helper.
 *
 * Flow: incoming chat line -> parsed (nick + text) -> queued -> AI verdict (mute-only rules) ->
 * HUD + chat notification -> moderator types ".m c" -> "/tempmute nick time rule" is sent.
 * Nothing is ever executed automatically.
 */
public class AiModerator implements ClientModInitializer {
	public static final String MOD_ID = "aimoderator";
	public static final Logger LOGGER = LoggerFactory.getLogger("AI Moderator");

	private static Violation pending;
	private static boolean openConfigRequested;
	private static boolean openMenuRequested;

	/** Messages waiting for analysis. Rate limiting delays requests instead of dropping messages. */
	private static final Deque<ChatParser.Parsed> QUEUE = new ArrayDeque<>();
	/** Recently analyzed "nick|text" keys, so repeated identical lines are skipped. */
	private static final Set<String> RECENT = new LinkedHashSet<>();

	private static long lastRequestAt;
	private static int inFlight;
	private static boolean keyWarningShown;

	@Override
	public void onInitializeClient() {
		Config.get();

		// ".m ..." commands are typed straight into the chat box - no keybinds needed (PojavLauncher friendly).
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
			String trimmed = message.trim();
			String lower = trimmed.toLowerCase(Locale.ROOT);
			if (!lower.equals(".m") && !lower.startsWith(".m ")) return true;
			handleCommand(trimmed);
			return false; // never leaks into public chat
		});

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) onIncoming(message);
		});
		ClientReceiveMessageEvents.CHAT.register(
				(message, signedMessage, sender, params, receptionTimestamp) -> onIncoming(message));

		HudRenderCallback.EVENT.register((context, tickCounter) -> {
			if (openConfigRequested) {
				openConfigRequested = false;
				MinecraftClient client = MinecraftClient.getInstance();
				if (client.currentScreen == null) client.setScreen(new ConfigScreen(null));
			}
			if (openMenuRequested) {
				openMenuRequested = false;
				MinecraftClient client = MinecraftClient.getInstance();
				Violation v = pending;
				if (v == null) {
					info("Нет нарушения для показа в меню");
				} else if (client.currentScreen == null) {
					client.setScreen(new ViolationScreen(null, v));
				}
			}
			pumpQueue();
			NotificationOverlay.render(context);
		});

		LOGGER.info("[AI Moderator] loaded. Commands: .m c / .m config / .m logs on|off / .m test");
	}

	/* ------------------------------------------------------------------ commands */

	private void handleCommand(String raw) {
		String[] parts = raw.split("\\s+");
		String sub = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "";
		String arg = parts.length > 2 ? parts[2].toLowerCase(Locale.ROOT) : "";
		switch (sub) {
			case "c", "confirm", "y" -> confirmPending();
			case "d", "deny", "decline", "n" -> declinePending();
			case "menu", "gui", "m" -> requestMenu();
			case "config", "settings" -> openConfigRequested = true;
			case "on" -> {
				Config.get().chatAnalysisEnabled = true;
				Config.get().save();
				info("Анализ чата: включён");
				warnIfNoKey();
			}
			case "off" -> {
				Config.get().chatAnalysisEnabled = false;
				Config.get().save();
				QUEUE.clear();
				info("Анализ чата: выключен");
			}
			case "logs", "log" -> handleLogs(arg);
			case "test" -> handleTest(raw);
			case "status", "s" -> printStatus();
			case "clear", "x" -> {
				pending = null;
				QUEUE.clear();
				NotificationOverlay.clear();
				info("Найденное нарушение сброшено");
			}
			default -> {
				info("§f.m c§7 — подтвердить мут, §f.m d§7 — отклонить, §f.m menu§7 — меню с ✔/✘");
				info("§f.m config§7 — настройки, §f.m on/off§7 — анализ, §f.m clear§7 — сбросить");
				info("§f.m logs on/off§7 — логи в чат, §f.m test <текст>§7 — проверить ИИ, §f.m status§7 — состояние");
			}
		}
	}

	private static void handleLogs(String arg) {
		Config cfg = Config.get();
		boolean enable = switch (arg) {
			case "on", "1", "true", "вкл" -> true;
			case "off", "0", "false", "выкл" -> false;
			default -> !cfg.logsEnabled; // ".m logs" toggles
		};
		cfg.logsEnabled = enable;
		cfg.save();
		info("Логи в чат: " + (enable ? "§aВКЛ" : "§cВЫКЛ"));
		if (enable) {
			ModLog.debug("Логи включены. Будут видны: разбор строк чата, запросы к AI, ответы и ошибки");
			printStatus();
		}
	}

	private static void handleTest(String raw) {
		int idx = raw.toLowerCase(Locale.ROOT).indexOf(" test");
		String text = idx < 0 ? "" : raw.substring(idx + 5).trim();
		if (text.isBlank()) {
			info("Использование: §f.m test ты лошок§7 — прогонит текст через ИИ и покажет ответ");
			return;
		}
		info("Тестовый запрос к ИИ…");
		MinecraftClient client = MinecraftClient.getInstance();
		AiAnalyzer.analyze("TestPlayer", text).thenAccept(result -> client.execute(() -> {
			if (!result.ok()) {
				ModLog.error("Тест не удался: " + result.error);
				return;
			}
			if (!result.rawVerdict.isBlank()) {
				info("Ответ ИИ: §f" + result.rawVerdict.replaceAll("\\s+", " "));
			}
			if (result.violation.isPresent()) {
				present(result.violation.get());
			} else {
				info("ИИ работает: нарушение не найдено");
			}
		}));
	}

	private static void printStatus() {
		Config cfg = Config.get();
		info("Анализ: " + (cfg.chatAnalysisEnabled ? "§aвкл" : "§cвыкл")
				+ "§7, логи: " + (cfg.logsEnabled ? "§aвкл" : "§cвыкл")
				+ "§7, ключ: " + (cfg.apiKey == null || cfg.apiKey.isBlank() ? "§cнет" : "§aесть"));
		info("Модель: §f" + cfg.model + "§7, режим разбора чата: §f" + cfg.chatParseMode
				+ "§7, в очереди: §f" + QUEUE.size());
		warnIfNoKey();
	}

	private static void warnIfNoKey() {
		Config cfg = Config.get();
		if (cfg.apiKey == null || cfg.apiKey.isBlank()) {
			ModLog.error("API Key не задан — ИИ не работает. Введите ключ в .m config");
		}
	}

	private static void requestMenu() {
		if (pending == null) {
			info("Нет нарушения для показа в меню");
			return;
		}
		openMenuRequested = true;
	}

	private static void confirmPending() {
		if (pending == null) {
			info("Нет нарушения для подтверждения");
			return;
		}
		confirmViolation(pending, pending.duration);
	}

	private static void declinePending() {
		if (pending == null) {
			info("Нет нарушения для отклонения");
			return;
		}
		declineViolation(pending);
	}

	/** ✔ in the menu / ".m c" — sends the mute command and hides the on-screen card. */
	public static void confirmViolation(Violation v, String durationOverride) {
		if (v == null) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.getNetworkHandler() == null) return;
		String duration = durationOverride == null || durationOverride.isBlank() ? v.duration : durationOverride;
		String command = Config.get().muteCommandTemplate
				.replace("{nick}", v.nick)
				.replace("{duration}", duration)
				.replace("{rule}", v.rule)
				.replace("{reason}", v.reason)
				.trim();
		if (command.startsWith("/")) command = command.substring(1);
		client.getNetworkHandler().sendChatCommand(command);
		dismiss();
		info("Отправлено: §f/" + command);
	}

	/** ✘ in the menu / ".m d" — nothing is sent, the on-screen card disappears immediately. */
	public static void declineViolation(Violation v) {
		if (v == null) return;
		String nick = v.nick;
		dismiss();
		info("Отклонено: §f" + nick + "§7 — мут не выдан");
		ModLog.debug("Нарушение отклонено модератором: " + nick + " — " + v.message);
	}

	/** Clears the pending violation and removes the HUD card from the screen. */
	private static void dismiss() {
		pending = null;
		openMenuRequested = false;
		NotificationOverlay.clear();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.currentScreen instanceof ViolationScreen) {
			client.setScreen(null);
		}
	}

	/* ------------------------------------------------------------------ analysis */

	private static void onIncoming(Text message) {
		try {
			Config cfg = Config.get();
			if (!cfg.chatAnalysisEnabled) return;

			String line = message == null ? "" : message.getString();
			if (line == null || line.isBlank()) return;
			String plain = ChatParser.stripFormatting(line);
			if (plain.contains("[AI Moderator]") || plain.startsWith("[log]")) return;

			Optional<ChatParser.Parsed> parsed = ChatParser.parse(line, cfg);
			// Unparsed lines are silent on purpose: server messages would spam the log otherwise.
			if (parsed.isEmpty()) return;

			String nick = parsed.get().nick;
			String text = parsed.get().text.trim();

			MinecraftClient client = MinecraftClient.getInstance();
			String self = client != null && client.getSession() != null ? client.getSession().getUsername() : "";
			if (!self.isBlank() && nick.equalsIgnoreCase(self)) {
				ModLog.debug("Пропущено своё сообщение");
				return;
			}
			if (text.isEmpty() || text.startsWith("/") || text.toLowerCase(Locale.ROOT).startsWith(".m")) return;
			if (text.length() > cfg.maxMessageLength) text = text.substring(0, cfg.maxMessageLength);

			String key = nick.toLowerCase(Locale.ROOT) + "|" + text.toLowerCase(Locale.ROOT);
			if (!RECENT.add(key)) {
				ModLog.debug("Дубликат, пропущено: " + nick + " — " + text);
				return;
			}
			if (RECENT.size() > 60) {
				var it = RECENT.iterator();
				it.next();
				it.remove();
			}

			if (QUEUE.size() >= cfg.maxQueueSize) QUEUE.pollFirst();
			QUEUE.addLast(new ChatParser.Parsed(nick, text));
			ModLog.debug("В очередь: §f" + nick + "§7 — " + text);
		} catch (Exception e) {
			ModLog.warn("Ошибка обработки строки чата: " + e);
		}
	}

	/** Called every HUD frame: sends queued messages to the AI while respecting the rate limit. */
	private static void pumpQueue() {
		Config cfg = Config.get();
		if (QUEUE.isEmpty()) return;
		if (!cfg.chatAnalysisEnabled) {
			QUEUE.clear();
			return;
		}
		if (inFlight >= Math.max(1, cfg.maxConcurrentRequests)) return;
		long now = System.currentTimeMillis();
		if (now - lastRequestAt < (long) (cfg.minRequestIntervalSeconds * 1000)) return;

		ChatParser.Parsed next = QUEUE.pollFirst();
		if (next == null) return;
		lastRequestAt = now;
		inFlight++;

		MinecraftClient client = MinecraftClient.getInstance();
		AiAnalyzer.analyze(next.nick, next.text)
				.whenComplete((result, throwable) -> client.execute(() -> {
					inFlight--;
					if (throwable != null) {
						ModLog.warn("Запрос упал: " + throwable);
						return;
					}
					if (!result.ok()) {
						// Errors like a wrong key must be visible even with logs off, but only once in a while.
						if (!keyWarningShown) {
							keyWarningShown = true;
							ModLog.error("ИИ не ответил: " + result.error);
						} else {
							ModLog.warn("ИИ не ответил: " + result.error);
						}
						return;
					}
					keyWarningShown = false;
					if (result.violation.isPresent()) {
						present(result.violation.get());
					} else {
						ModLog.debug("Нарушений нет: " + next.nick + " — " + next.text);
					}
				}));
	}

	private static void present(Violation v) {
		pending = v;
		Config cfg = Config.get();
		if (cfg.showHudNotification) NotificationOverlay.show(v);
		if (cfg.showChatNotification) {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.player == null) return;
			client.player.sendMessage(Text.literal("§8───── §c⚠ AI Moderator §8─────"), false);
			client.player.sendMessage(Text.literal("§7Игрок: §f" + v.nick), false);
			client.player.sendMessage(Text.literal("§7Сообщение: §f" + v.message), false);
			client.player.sendMessage(Text.literal("§7Правило: §e" + v.rule + "  §7Причина: §f" + v.reason), false);
			client.player.sendMessage(Text.literal("§7Срок мута: §a" + v.duration), false);
			client.player.sendMessage(Text.literal("§a.m c§7 — мут, §c.m d§7 — отклонить, §f.m menu§7 — меню ✔/✘"), false);
		}
	}

	public static Violation pending() {
		return pending;
	}

	public static void info(String text) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) return;
		client.execute(() -> {
			if (client.player != null) {
				client.player.sendMessage(Text.literal("§b[AI Moderator] §7" + text), false);
			}
		});
	}
}
