package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.map.MapView;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A map tool. Coordinates are block coordinates. {@code alt} is true while Shift is held
 * (erase for painting tools, lower for raise, clear for rectangle, ...).
 */
public interface Tool {
	String name();

	/** Keyboard shortcut shown in the tool bar. */
	char hotkey();

	/** One line explaining the tool. */
	String help();

	/** Whether the brush size/shape settings apply. */
	boolean usesBrush();

	default void press(PainterState s, int x, int z, boolean alt) {
	}

	default void drag(PainterState s, int x, int z, boolean alt) {
	}

	default void release(PainterState s, int x, int z, boolean alt) {
	}

	/** Called every tick (20 per second) while the mouse button is held on the map. */
	default void hold(PainterState s, int x, int z, boolean alt) {
	}

	/** Extra drawing on top of the map (previews, outlines). */
	default void drawOverlay(PainterState s, GuiGraphicsExtractor g, MapView view, double mouseX, double mouseY) {
	}

	/** The rectangle being dragged out (inclusive block bounds), or null; also drawn in the 3D view. */
	default int[] dragRect() {
		return null;
	}
}
