package com.daniel.worldpainter.client.screen;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/** A plain message with an OK button. */
public final class InfoScreen extends Screen {
	private final Screen parent;
	private final String title;
	private final String[] lines;

	public InfoScreen(Screen parent, String title, String... lines) {
		this.parent = parent;
		this.title = title;
		this.lines = lines;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void init() {
		buttons.clear();
		buttons.add(new ButtonWidget(0, width / 2 - 50, height / 2 + 10 + lines.length * 12, 100, 20, "OK"));
	}

	@Override
	public void buttonClicked(ButtonWidget button) {
		if (button.id == 0) {
			minecraft.setScreen(parent);
		}
	}

	@Override
	public void render(int mouseX, int mouseY, float delta) {
		renderBackground();
		drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 20, 0xFFFFFF);
		for (int i = 0; i < lines.length; i++) {
			drawCenteredTextWithShadow(textRenderer, lines[i], width / 2, height / 2 + i * 12, 0xB0B0B0);
		}
		super.render(mouseX, mouseY, delta);
	}
}
