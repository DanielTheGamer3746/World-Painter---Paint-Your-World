package com.daniel.worldpainter.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A plain message with an OK button. */
public final class InfoScreen extends Screen {
	private final Screen parent;
	private final String[] lines;

	public InfoScreen(Screen parent, String title, String... lines) {
		super(Component.literal(title));
		this.parent = parent;
		this.lines = lines;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(Component.literal("OK"), b -> onClose())
				.bounds(width / 2 - 50, height / 2 + 10 + lines.length * 12, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.centeredText(font, getTitle(), width / 2, height / 2 - 20, 0xFFFFFFFF);
		for (int i = 0; i < lines.length; i++) {
			g.centeredText(font, lines[i], width / 2, height / 2 + i * 12, 0xFFB0B0B0);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
