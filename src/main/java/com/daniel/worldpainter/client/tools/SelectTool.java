package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.map.MapView;
import com.daniel.worldpainter.client.gui.Gfx;

/** Drag to select a rectangle; the side panel then offers actions (save as template, smooth, clear...). */
public final class SelectTool implements Tool {
	private int startX, startZ, endX, endZ;
	private boolean dragging;

	@Override
	public String name() {
		return "Select";
	}

	@Override
	public char hotkey() {
		return 'L';
	}

	@Override
	public String help() {
		return "Drag to select an area. Shift+click clears the selection. Actions are in the side panel.";
	}

	@Override
	public boolean usesBrush() {
		return false;
	}

	@Override
	public void press(PainterState s, int x, int z, boolean alt) {
		if (alt) {
			s.selection = null;
			return;
		}
		startX = endX = x;
		startZ = endZ = z;
		dragging = true;
	}

	@Override
	public void drag(PainterState s, int x, int z, boolean alt) {
		if (dragging) {
			endX = x;
			endZ = z;
			s.selection = new int[]{Math.min(startX, endX), Math.min(startZ, endZ), Math.max(startX, endX), Math.max(startZ, endZ)};
		}
	}

	@Override
	public void release(PainterState s, int x, int z, boolean alt) {
		if (dragging) {
			drag(s, x, z, alt);
			dragging = false;
			int[] sel = s.selection;
			s.say("Selected " + (sel[2] - sel[0] + 1) + " x " + (sel[3] - sel[1] + 1) + " blocks");
		}
	}

	@Override
	public void drawOverlay(PainterState s, Gfx g, MapView view, double mouseX, double mouseY) {
		// The selection itself is drawn by the map for every tool.
	}
}
