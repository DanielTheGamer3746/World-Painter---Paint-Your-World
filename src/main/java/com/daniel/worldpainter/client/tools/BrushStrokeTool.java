package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.map.MapDraw;
import com.daniel.worldpainter.client.map.MapView;
import com.daniel.worldpainter.client.gui.Gfx;

/** Base for tools that paint with the brush: fills in gaps when the mouse moves fast. */
public abstract class BrushStrokeTool implements Tool {
	private int lastX;
	private int lastZ;
	private boolean active;
	protected int dabCounter;

	protected abstract void dab(PainterState s, int cx, int cz, boolean alt);

	/** Called once when a stroke starts. */
	protected void startStroke(PainterState s, int x, int z, boolean alt) {
	}

	@Override
	public boolean usesBrush() {
		return true;
	}

	@Override
	public void press(PainterState s, int x, int z, boolean alt) {
		s.session.begin(name());
		startStroke(s, x, z, alt);
		active = true;
		lastX = x;
		lastZ = z;
		dabCounter++;
		dab(s, x, z, alt);
	}

	@Override
	public void drag(PainterState s, int x, int z, boolean alt) {
		if (!active) {
			return;
		}
		double dx = x - lastX;
		double dz = z - lastZ;
		double dist = Math.sqrt(dx * dx + dz * dz);
		double spacing = Math.max(1.0, s.brush.radius / 3.0);
		if (dist < spacing) {
			return;
		}
		int steps = (int) Math.ceil(dist / spacing);
		for (int i = 1; i <= steps; i++) {
			int px = (int) Math.round(lastX + dx * i / steps);
			int pz = (int) Math.round(lastZ + dz * i / steps);
			dabCounter++;
			dab(s, px, pz, alt);
		}
		lastX = x;
		lastZ = z;
	}

	@Override
	public void release(PainterState s, int x, int z, boolean alt) {
		if (active) {
			s.session.end();
		}
		active = false;
	}

	@Override
	public void drawOverlay(PainterState s, Gfx g, MapView view, double mouseX, double mouseY) {
		if (!view.contains(mouseX, mouseY)) {
			return;
		}
		int cx = (int) Math.floor(view.toWorldX(mouseX));
		int cz = (int) Math.floor(view.toWorldZ(mouseY));
		MapDraw.brush(g, view, s.brush, cx, cz, 0xFFFFFFFF);
	}

	/** Deterministic 0..1 value per block and dab, used to spread fractional height changes. */
	protected static double hash01(int x, int z, int salt) {
		long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return (h >>> 11) * 0x1.0p-53;
	}
}
