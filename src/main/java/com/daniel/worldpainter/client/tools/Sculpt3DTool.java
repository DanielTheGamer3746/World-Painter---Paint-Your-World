package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.util.MathUtil;

import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.edit.VolumeOps;
import com.daniel.worldpainter.client.map.MapDraw;
import com.daniel.worldpainter.client.map.MapView;
import com.daniel.worldpainter.client.gui.Gfx;

/**
 * Sculpts in 3D: adds ground (overhangs, arches, pillars), carves it away (caves, tunnels, holes),
 * restores the 2D design, or places floating islands. Works best in the 3D view; on the map it acts
 * on the top of the clicked column.
 */
public final class Sculpt3DTool implements Tool, Tool3D {
	/** Largest ball radius (bigger balls would mean millions of blocks per dab). */
	public static final int MAX_RADIUS = 48;

	private boolean active;
	private double lastX, lastY, lastZ;
	private int salt;
	private int holdTicks;

	@Override
	public String name() {
		return "3D Sculpt";
	}

	@Override
	public char hotkey() {
		return 'C';
	}

	@Override
	public String help() {
		return "Sculpt in 3D: add ground, carve caves and tunnels, restore, or place floating islands (mode in the side panel). Shift swaps add and carve. Hold to keep going.";
	}

	@Override
	public boolean usesBrush() {
		return true;
	}

	public static int radius(PainterState s) {
		return MathUtil.clamp(s.brush.radius, 1, MAX_RADIUS);
	}

	private static VolumeOps.Action action(PainterState s, boolean alt) {
		return switch (s.sculpt3dMode) {
			case ADD -> alt ? VolumeOps.Action.CARVE : VolumeOps.Action.ADD;
			case CARVE -> alt ? VolumeOps.Action.ADD : VolumeOps.Action.CARVE;
			default -> VolumeOps.Action.RESTORE;
		};
	}

	@Override
	public double[] target(PainterState s, Hit3D hit, boolean alt) {
		double hx = hit.x() + 0.5, hy = hit.y() + 0.5, hz = hit.z() + 0.5;
		if (s.sculpt3dMode == PainterState.Sculpt3DMode.ISLAND) {
			return new double[]{hx, hit.y() + 1 + s.islandHeight, hz};
		}
		int r = radius(s);
		// Adding grows out of the face you point at, carving digs into it.
		double offset = switch (action(s, alt)) {
			case ADD -> r * 0.5 + 0.5;
			case CARVE -> -(r * 0.45);
			case RESTORE -> 0;
		};
		return new double[]{hx + hit.nx() * offset, hy + hit.ny() * offset, hz + hit.nz() * offset};
	}

	@Override
	public void press3d(PainterState s, Hit3D hit, boolean alt) {
		if (s.sculpt3dMode == PainterState.Sculpt3DMode.ISLAND) {
			double[] c = target(s, hit, alt);
			s.session.begin("Floating island");
			VolumeOps.island(s, (int) Math.floor(c[0]), (int) Math.floor(c[1]), (int) Math.floor(c[2]), s.islandSize, s.templateSeed);
			s.session.end();
			s.templateSeed = System.nanoTime();
			s.say("Placed a floating island " + s.islandSize + " blocks wide at Y " + (int) Math.floor(c[1]));
			return;
		}
		s.session.begin(name() + ": " + s.sculpt3dMode.label);
		active = true;
		holdTicks = 0;
		double[] c = target(s, hit, alt);
		dab(s, c, alt);
	}

	@Override
	public void drag3d(PainterState s, Hit3D hit, boolean alt) {
		if (!active || hit == null) {
			return;
		}
		double[] c = target(s, hit, alt);
		double dx = c[0] - lastX, dy = c[1] - lastY, dz = c[2] - lastZ;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		double spacing = Math.max(1.0, radius(s) * 0.5);
		if (dist < spacing) {
			return;
		}
		int steps = Math.min(64, (int) Math.ceil(dist / spacing));
		double sx = lastX, sy = lastY, sz = lastZ;
		for (int i = 1; i <= steps; i++) {
			dab(s, new double[]{sx + dx * i / steps, sy + dy * i / steps, sz + dz * i / steps}, alt);
		}
	}

	@Override
	public void hold3d(PainterState s, Hit3D hit, boolean alt) {
		if (!active || hit == null) {
			return;
		}
		// Strength sets how often the ball is applied again while the button is held.
		int interval = MathUtil.clamp(12 - s.brush.strength / 3, 1, 12);
		if (++holdTicks % interval == 0) {
			dab(s, target(s, hit, alt), alt);
		}
	}

	@Override
	public void release3d(PainterState s, boolean alt) {
		if (active) {
			s.session.end();
		}
		active = false;
	}

	private void dab(PainterState s, double[] c, boolean alt) {
		VolumeOps.ball(s, c[0], c[1], c[2], radius(s) + 0.5, s.brush.hardness, action(s, alt), ++salt);
		lastX = c[0];
		lastY = c[1];
		lastZ = c[2];
	}

	// ---- on the 2D map: act on the top of the clicked column ----

	private static Hit3D topHit(PainterState s, int x, int z) {
		return Hit3D.top(x, VolumeOps.topSolid(s, x, z), z);
	}

	@Override
	public void press(PainterState s, int x, int z, boolean alt) {
		press3d(s, topHit(s, x, z), alt);
	}

	@Override
	public void drag(PainterState s, int x, int z, boolean alt) {
		drag3d(s, topHit(s, x, z), alt);
	}

	@Override
	public void hold(PainterState s, int x, int z, boolean alt) {
		hold3d(s, topHit(s, x, z), alt);
	}

	@Override
	public void release(PainterState s, int x, int z, boolean alt) {
		release3d(s, alt);
	}

	@Override
	public void drawOverlay(PainterState s, Gfx g, MapView view, double mouseX, double mouseY) {
		if (!view.contains(mouseX, mouseY)) {
			return;
		}
		int cx = (int) Math.floor(view.toWorldX(mouseX));
		int cz = (int) Math.floor(view.toWorldZ(mouseY));
		Brush b = new Brush();
		b.radius = s.sculpt3dMode == PainterState.Sculpt3DMode.ISLAND ? s.islandSize / 2 : radius(s);
		MapDraw.brush(g, view, b, cx, cz, 0xFFD0A0FF);
	}
}
