package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.edit.Ops;
import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.map.MapDraw;
import com.daniel.worldpainter.client.map.MapView;
import com.daniel.worldpainter.client.gui.Gfx;

/** Drag a rectangle to fill it with the chosen value (Shift: erase it). Works for huge areas. */
public final class RectTool implements Tool {
	public static final long MAX_AREA = 16384L * 16384L;
	private int startX, startZ, endX, endZ;
	private boolean dragging;

	@Override
	public String name() {
		return "Rectangle";
	}

	@Override
	public char hotkey() {
		return 'R';
	}

	@Override
	public String help() {
		return "Drag to fill a rectangle with the chosen value (Shift erases). Up to 16384 x 16384 blocks.";
	}

	@Override
	public boolean usesBrush() {
		return false;
	}

	@Override
	public void press(PainterState s, int x, int z, boolean alt) {
		startX = endX = x;
		startZ = endZ = z;
		dragging = true;
	}

	@Override
	public void drag(PainterState s, int x, int z, boolean alt) {
		endX = x;
		endZ = z;
	}

	@Override
	public void release(PainterState s, int x, int z, boolean alt) {
		if (!dragging) {
			return;
		}
		dragging = false;
		endX = x;
		endZ = z;
		int minX = Math.min(startX, endX), maxX = Math.max(startX, endX);
		int minZ = Math.min(startZ, endZ), maxZ = Math.max(startZ, endZ);
		long area = (maxX - minX + 1L) * (maxZ - minZ + 1L);
		if (area > MAX_AREA) {
			s.say("Rectangle too big (max 16384 x 16384 blocks).");
			return;
		}
		s.session.begin(name());
		if (alt) {
			Ops.clearRect(s.session, minX, minZ, maxX, maxZ, s.layer);
		} else {
			Ops.fillRect(s.session, minX, minZ, maxX, maxZ, s.currentValue());
		}
		s.session.end();
		s.say((alt ? "Erased " : "Filled ") + (maxX - minX + 1) + " x " + (maxZ - minZ + 1) + " blocks");
	}

	@Override
	public void drawOverlay(PainterState s, Gfx g, MapView view, double mouseX, double mouseY) {
		if (dragging) {
			MapDraw.rect(g, view, Math.min(startX, endX), Math.min(startZ, endZ), Math.max(startX, endX), Math.max(startZ, endZ), 0xFFFFFF00);
		}
	}

	@Override
	public int[] dragRect() {
		return dragging ? new int[]{Math.min(startX, endX), Math.min(startZ, endZ), Math.max(startX, endX), Math.max(startZ, endZ)} : null;
	}
}
