package com.daniel.worldpainter.client.gui;

import org.lwjgl.input.Keyboard;

import java.util.function.Consumer;

/** A one-line text field for the painter (search boxes, template names). */
public final class TextBox {
	public int x, y, width, height = 14;
	public boolean visible;
	private boolean focused;
	private String text = "";
	private final String hint;
	private int maxLength = 64;
	private final Consumer<String> responder;

	public TextBox(String hint, String value, Consumer<String> responder) {
		this.hint = hint;
		this.text = value == null ? "" : value;
		this.responder = responder;
	}

	public void setMaxLength(int n) {
		maxLength = n;
	}

	public String text() {
		return text;
	}

	/** Replaces the text (the responder hears it). */
	public void setText(String value) {
		set(value == null ? "" : value);
	}

	public boolean focused() {
		return focused && visible;
	}

	public void setFocused(boolean on) {
		focused = on;
	}

	public void place(int x, int y, int w) {
		this.x = x;
		this.y = y;
		this.width = w;
		this.visible = true;
	}

	public boolean contains(double mx, double my) {
		return visible && mx >= x && mx < x + width && my >= y && my < y + height;
	}

	public void draw(Gfx g, long ticks) {
		if (!visible) {
			return;
		}
		g.fill(x, y, x + width, y + height, 0xFF000000);
		g.outline(x, y, width, height, focused ? 0xFFFFFFFF : 0xFF6A6A76);
		String shown = text;
		int room = width - 8;
		while (!shown.isEmpty() && g.width(shown) > room) {
			shown = shown.substring(1);
		}
		if (text.isEmpty() && !focused) {
			g.text(hint, x + 4, y + 3, 0xFF707078, false);
		} else {
			g.text(shown + (focused && (ticks / 6) % 2 == 0 ? "_" : ""), x + 4, y + 3, 0xFFE0E0E0, false);
		}
	}

	/** Handles a typed key while focused; returns true if it was used. */
	public boolean key(char c, int key, boolean control, String clipboard) {
		if (!focused()) {
			return false;
		}
		if (key == Keyboard.KEY_BACK) {
			if (!text.isEmpty()) {
				set(text.substring(0, text.length() - 1));
			}
			return true;
		}
		if (control && key == Keyboard.KEY_V && clipboard != null) {
			set(text + clipboard.replaceAll("[\\r\\n\\t]", " "));
			return true;
		}
		if (c >= ' ' && c != 127 && c != '§') {
			set(text + c);
			return true;
		}
		return false;
	}

	private void set(String value) {
		text = value.length() > maxLength ? value.substring(0, maxLength) : value;
		responder.accept(text);
	}
}
