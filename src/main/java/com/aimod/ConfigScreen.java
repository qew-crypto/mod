package com.aimod;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Touch-friendly settings menu opened with ".m config".
 *
 * The layout is fully adaptive: row height and gaps shrink to the available screen height,
 * so no widget can ever fall below the bottom edge (phones / big GUI scale).
 */
public class ConfigScreen extends Screen {
	private static final int ROWS = 9; // 3 fields + 6 buttons

	private final Screen parent;
	private TextFieldWidget endpointField;
	private TextFieldWidget keyField;
	private TextFieldWidget modelField;
	private ButtonWidget toggleButton;
	private ButtonWidget logsButton;
	private boolean showKey;
	private String status = "";

	// computed in init(), reused by render() so labels never overlap the widgets
	private int layoutX;
	private int layoutW;
	private int step;
	private int widgetH;
	private boolean showLabels;
	private int endpointY;
	private int keyY;
	private int modelY;

	public ConfigScreen(Screen parent) {
		super(Text.literal("AI Moderator — настройки"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Config cfg = Config.get();

		layoutW = Math.min(280, this.width - 20);
		layoutX = (this.width - layoutW) / 2;

		int top = 26;
		int bottom = 6;
		int available = Math.max(60, this.height - top - bottom);

		// step = height of one row including its gap; shrinks until everything fits
		step = available / ROWS;
		step = Math.max(13, Math.min(34, step));
		widgetH = Math.max(11, Math.min(20, step - 4));
		// labels above the text fields only when there is spare vertical room
		showLabels = step >= 30;

		int y = top;

		endpointY = y;
		endpointField = new TextFieldWidget(this.textRenderer, layoutX, y + labelOffset(), layoutW, widgetH,
				Text.literal("API Endpoint"));
		endpointField.setMaxLength(512);
		endpointField.setText(cfg.apiEndpoint == null ? "" : cfg.apiEndpoint);
		addDrawableChild(endpointField);
		y += step;

		keyY = y;
		int keyButtonW = Math.min(50, layoutW / 3);
		keyField = new TextFieldWidget(this.textRenderer, layoutX, y + labelOffset(), layoutW - keyButtonW - 4, widgetH,
				Text.literal("API Key"));
		keyField.setMaxLength(512);
		keyField.setText(cfg.apiKey == null ? "" : cfg.apiKey);
		keyField.setRenderTextProvider((text, index) -> showKey
				? Text.literal(text).asOrderedText()
				: Text.literal("*".repeat(text.length())).asOrderedText());
		addDrawableChild(keyField);
		addDrawableChild(ButtonWidget.builder(Text.literal(showKey ? "Скрыть" : "Показать"), b -> {
			save();
			showKey = !showKey;
			clearAndInit();
		}).dimensions(layoutX + layoutW - keyButtonW, y + labelOffset(), keyButtonW, widgetH).build());
		y += step;

		modelY = y;
		modelField = new TextFieldWidget(this.textRenderer, layoutX, y + labelOffset(), layoutW, widgetH,
				Text.literal("Model"));
		modelField.setMaxLength(128);
		modelField.setText(cfg.model == null ? "" : cfg.model);
		addDrawableChild(modelField);
		y += step;

		toggleButton = ButtonWidget.builder(toggleLabel(), b -> {
			Config c = Config.get();
			c.chatAnalysisEnabled = !c.chatAnalysisEnabled;
			save();
			toggleButton.setMessage(toggleLabel());
		}).dimensions(layoutX, y, layoutW, widgetH).build();
		addDrawableChild(toggleButton);
		y += step;

		logsButton = ButtonWidget.builder(logsLabel(), b -> {
			Config c = Config.get();
			c.logsEnabled = !c.logsEnabled;
			save();
			logsButton.setMessage(logsLabel());
		}).dimensions(layoutX, y, layoutW, widgetH).build();
		addDrawableChild(logsButton);
		y += step;

		addDrawableChild(ButtonWidget.builder(Text.literal("Правила сервера"), b -> {
			save();
			this.client.setScreen(new RulesScreen(this));
		}).dimensions(layoutX, y, layoutW, widgetH).build());
		y += step;

		addDrawableChild(ButtonWidget.builder(Text.literal("Проверить ИИ (тест)"), b -> {
			save();
			this.close();
			AiModerator.info("Тест ИИ: отправляю тестовое оскорбление…");
			AiAnalyzer.analyze("TestPlayer", "ты лошок и еблан").thenAccept(result -> {
				if (!result.ok()) {
					ModLog.error("Тест не удался: " + result.error);
					return;
				}
				AiModerator.info("Ответ ИИ: §f" + result.rawVerdict.replaceAll("\\s+", " "));
			});
		}).dimensions(layoutX, y, layoutW, widgetH).build());
		y += step;

		addDrawableChild(ButtonWidget.builder(Text.literal("Текущие настройки в чат"), b -> {
			save();
			printSettings();
			this.close();
		}).dimensions(layoutX, y, layoutW, widgetH).build());
		y += step;

		// The last button is pinned to the bottom edge, so it can never go off-screen.
		int saveY = Math.min(y, this.height - widgetH - bottom);
		addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить и закрыть"), b -> {
			save();
			status = "Сохранено";
			this.close();
		}).dimensions(layoutX, saveY, layoutW, widgetH).build());
	}

	/** Space reserved above a text field for its label (0 when labels are hidden). */
	private int labelOffset() {
		return showLabels ? 9 : 0;
	}

	private Text toggleLabel() {
		boolean on = Config.get().chatAnalysisEnabled;
		return Text.literal("Анализ чата: " + (on ? "§aВКЛ" : "§cВЫКЛ"));
	}

	private Text logsLabel() {
		boolean on = Config.get().logsEnabled;
		return Text.literal("Логи в чат: " + (on ? "§aВКЛ" : "§cВЫКЛ"));
	}

	private void save() {
		Config cfg = Config.get();
		if (endpointField != null) cfg.apiEndpoint = endpointField.getText().trim();
		if (keyField != null) cfg.apiKey = keyField.getText().trim();
		if (modelField != null) cfg.model = modelField.getText().trim();
		cfg.save();
	}

	private void printSettings() {
		Config cfg = Config.get();
		String masked = cfg.apiKey == null || cfg.apiKey.isBlank()
				? "§cне задан"
				: "§aзадан (" + cfg.apiKey.length() + " симв.)";
		AiModerator.info("Endpoint: §f" + cfg.apiEndpoint);
		AiModerator.info("API Key: " + masked);
		AiModerator.info("Model: §f" + cfg.model);
		AiModerator.info("Анализ чата: " + (cfg.chatAnalysisEnabled ? "§aвкл" : "§cвыкл"));
		AiModerator.info("Логи в чат: " + (cfg.logsEnabled ? "§aвкл" : "§cвыкл") + "§7 (.m logs on/off)");
		AiModerator.info("Файл правил: §f" + Config.rulesFile());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, 0xFFFFFF);

		if (showLabels) {
			context.drawTextWithShadow(this.textRenderer, Text.literal("§7API Endpoint"), layoutX, endpointY, 0xAAAAAA);
			context.drawTextWithShadow(this.textRenderer, Text.literal("§7API Key"), layoutX, keyY, 0xAAAAAA);
			context.drawTextWithShadow(this.textRenderer, Text.literal("§7Model"), layoutX, modelY, 0xAAAAAA);
		}
		if (!status.isEmpty() && showLabels) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("§a" + status),
					this.width / 2, 16, 0xFFFFFF);
		}
	}

	@Override
	public void close() {
		save();
		this.client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
