package com.aimod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Compact HUD card in the top-left corner. Large enough to read on a phone screen, small enough not to block the game. */
public final class NotificationOverlay {
	private static Violation current;
	private static long shownAt;

	private NotificationOverlay() {}

	public static void show(Violation v) {
		current = v;
		shownAt = System.currentTimeMillis();
	}

	public static void clear() {
		current = null;
	}

	public static void render(DrawContext context) {
		Violation v = current;
		if (v == null) return;
		Config cfg = Config.get();
		if (cfg.hudSeconds > 0 && System.currentTimeMillis() - shownAt > cfg.hudSeconds * 1000L) {
			current = null;
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options.hudHidden) return;
		TextRenderer tr = client.textRenderer;

		int maxWidth = Math.min(230, client.getWindow().getScaledWidth() - 20);

		List<Text> lines = new ArrayList<>();
		lines.add(Text.literal("§c⚠ Нарушение обнаружено"));
		lines.add(Text.literal("§7Игрок: §f" + v.nick));
		for (String part : wrap(tr, "Сообщение: " + v.message, maxWidth - 12)) {
			lines.add(Text.literal("§8" + part));
		}
		lines.add(Text.literal("§7Правило: §e" + v.rule));
		for (String part : wrap(tr, "Причина: " + v.reason, maxWidth - 12)) {
			lines.add(Text.literal("§7" + part));
		}
		lines.add(Text.literal("§7Срок мута: §a" + v.duration));
		lines.add(Text.literal("§f.m c §8— подтвердить"));

		int x = 6;
		int y = 6;
		int lineHeight = tr.fontHeight + 2;
		int height = lines.size() * lineHeight + 10;
		int width = maxWidth;

		context.fill(x, y, x + width, y + height, 0xC8000000);
		context.fill(x, y, x + width, y + 1, 0xFFFF5555);
		context.fill(x, y + height - 1, x + width, y + height, 0xFFFF5555);
		context.fill(x, y, x + 1, y + height, 0xFFFF5555);
		context.fill(x + width - 1, y, x + width, y + height, 0xFFFF5555);

		int textY = y + 5;
		for (Text line : lines) {
			context.drawTextWithShadow(tr, line, x + 6, textY, 0xFFFFFF);
			textY += lineHeight;
		}
	}

	private static List<String> wrap(TextRenderer tr, String text, int maxWidth) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (tr.getWidth(candidate) > maxWidth && !line.isEmpty()) {
				out.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(candidate);
			}
			if (out.size() >= 4) break;
		}
		if (!line.isEmpty() && out.size() < 5) out.add(line.toString());
		return out;
	}
}
