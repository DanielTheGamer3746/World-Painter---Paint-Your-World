package com.daniel.worldpainter.client.screen;

import java.util.ArrayList;
import java.util.List;

/** Minimal hit-testing for the custom-drawn parts of the painter (side panel, tool bar). */
final class Hits {
	interface Hit {
		void click(double mouseX, double mouseY, int button);

		default void drag(double mouseX, double mouseY) {
		}

		/** @return true if the scroll was used */
		default boolean scroll(double amount) {
			return false;
		}
	}

	private record Area(int x, int y, int w, int h, Hit hit, String tooltip) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Area> areas = new ArrayList<>();
	private Area dragging;

	void clear() {
		areas.clear();
	}

	void add(int x, int y, int w, int h, Hit hit) {
		areas.add(new Area(x, y, w, h, hit, null));
	}

	void add(int x, int y, int w, int h, Hit hit, String tooltip) {
		areas.add(new Area(x, y, w, h, hit, tooltip));
	}

	private Area find(double mx, double my) {
		for (int i = areas.size() - 1; i >= 0; i--) {
			if (areas.get(i).contains(mx, my)) {
				return areas.get(i);
			}
		}
		return null;
	}

	boolean click(double mx, double my, int button) {
		Area a = find(mx, my);
		if (a == null) {
			return false;
		}
		dragging = a;
		a.hit.click(mx, my, button);
		return true;
	}

	boolean drag(double mx, double my) {
		if (dragging == null) {
			return false;
		}
		dragging.hit.drag(mx, my);
		return true;
	}

	boolean release() {
		boolean was = dragging != null;
		dragging = null;
		return was;
	}

	boolean scroll(double mx, double my, double amount) {
		Area a = find(mx, my);
		return a != null && a.hit.scroll(amount);
	}

	String tooltip(double mx, double my) {
		Area a = find(mx, my);
		return a == null ? null : a.tooltip;
	}
}
