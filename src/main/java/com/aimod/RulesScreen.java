package com.aimod;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EditBoxWidget;
import net.minecraft.text.Text;

/** Multi-line editor for the server rules that are sent to the AI. */
public class RulesScreen extends Screen {
	private final Screen parent;
	private EditBoxWidget editBox;

	public RulesScreen(Screen parent) {
		super(Text.literal("Правила сервера"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int w = Math.min(320, this.width - 30);
		int x = (this.width - w) / 2;
		int h = this.height - 90;

		editBox = new EditBoxWidget(this.textRenderer, x, 34, w, h,
				Text.literal("Вставьте правила сервера..."), Text.literal("Правила"));
		editBox.setMaxLength(20000);
		editBox.setText(Config.get().loadRules());
		addDrawableChild(editBox);

		addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить"), b -> {
			Config.get().saveRules(editBox.getText());
			this.close();
		}).dimensions(x, this.height - 48, w / 2 - 4, 20).build());

		addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), b -> this.client.setScreen(parent))
				.dimensions(x + w / 2 + 4, this.height - 48, w / 2 - 4, 20).build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("§8Бан / IP-бан / «по решению администрации» игнорируются"),
				this.width / 2, this.height - 66, 0x888888);
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
