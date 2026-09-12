package com.aimod;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

/**
 * ".m menu" — touch-friendly mute dialog for the pending violation.
 *
 * Shows nick / message / rule / reason / duration and two big buttons:
 * ✔ sends the mute command, ✘ declines (same as ".m d").
 * The duration can be cycled through presets before confirming.
 */
public class ViolationScreen extends Screen {
	private static final List<String> PRESETS = List.of("10m", "30m", "1h", "2h", "3h", "6h", "12h", "1d", "3d", "7d");

	private final Screen parent;
	private final Violation violation;
	private String duration;
	private ButtonWidget durationButton;

	private int layoutX;
	private int layoutW;
	private int textTop;
	private int lineHeight;

	public ViolationScreen(Screen parent, Violation violation) {
		super(Text.literal("⚠ Нарушение"));
		this.parent = parent;
		this.violation = violation;
		this.duration = violation.duration;
	}

	@Override
	protected void init() {
		layoutW = Math.min(280, this.width - 20);
		layoutX = (this.width - layoutW) / 2;
		lineHeight = this.textRenderer.fontHeight + 2;
		textTop = 22;

		// Bottom-anchored controls so nothing can leave the screen on phones.
		int bottom = 6;
		int btnH = Math.max(14, Math.min(22, (this.height - textTop - bottom) / 6));
		int gap = 4;

		int actionY = this.height - bottom - btnH;
		int durationY = actionY - btnH - gap;

		durationButton = ButtonWidget.builder(durationLabel(), b -> {
			duration = nextPreset(duration);
			durationButton.setMessage(durationLabel());
		}).dimensions(layoutX, durationY, layoutW, btnH).build();
		addDrawableChild(durationButton);

		int half = (layoutW - gap) / 2;
		addDrawableChild(ButtonWidget.builder(Text.literal("§a✔ Мут"), b -> {
			this.client.setScreen(parent);
			AiModerator.confirmViolation(violation, duration);
		}).dimensions(layoutX, actionY, half, btnH).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("§c✘ Отклонить"), b -> {
			this.client.setScreen(parent);
			AiModerator.declineViolation(violation);
		}).dimensions(layoutX + half + gap, actionY, layoutW - half - gap, btnH).build());
	}

	private Text durationLabel() {
		boolean suggested = duration.equalsIgnoreCase(violation.duration);
		return Text.literal("Срок мута: §a" + duration
				+ (suggested ? "§7 (ИИ)" : "§7 (вручную)") + " §8— нажмите, чтобы изменить");
	}

	private static String nextPreset(String current) {
		int idx = PRESETS.indexOf(current.toLowerCase());
		return PRESETS.get((idx + 1) % PRESETS.size());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§c⚠ Нарушение найдено"),
				this.width / 2, 8, 0xFFFFFF);

		int y = textTop;
		y = line(context, y, "§7Игрок: §f" + violation.nick);
		y = line(context, y, "§7Правило: §e" + violation.rule);
		y = wrapped(context, y, "§7Причина: §f" + violation.reason);
		y = wrapped(context, y, "§8Сообщение: " + violation.message);
		line(context, y + 2, "§8/" + Config.get().muteCommandTemplate
				.replace("{nick}", violation.nick)
				.replace("{duration}", duration)
				.replace("{rule}", violation.rule)
				.replace("{reason}", violation.reason));
	}

	private int line(DrawContext context, int y, String text) {
		context.drawTextWithShadow(this.textRenderer, Text.literal(text), layoutX, y, 0xFFFFFF);
		return y + lineHeight;
	}

	private int wrapped(DrawContext context, int y, String text) {
		int cursor = y;
		for (var ordered : this.textRenderer.wrapLines(Text.literal(text), layoutW)) {
			context.drawTextWithShadow(this.textRenderer, ordered, layoutX, cursor, 0xFFFFFF);
			cursor += lineHeight;
		}
		return cursor;
	}

	@Override
	public void close() {
		this.client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
