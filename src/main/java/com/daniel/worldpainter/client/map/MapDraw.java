package com.daniel.worldpainter.client.map;

import com.daniel.worldpainter.util.MathUtil;

import com.daniel.worldpainter.client.tools.Brush;
import com.daniel.worldpainter.client.gui.Gfx;

/** Helpers to draw outlines on the map in block coordinates. */
public final class MapDraw {
	private MapDraw() {
	}

	/** Outline of a block rectangle (inclusive bounds). Edges outside the map are not drawn. */
	public static void rect(Gfx g, MapView v, int minX, int minZ, int maxX, int maxZ, int color) {
		double gx0 = v.toGuiX(minX);
		double gy0 = v.toGuiY(minZ);
		double gx1 = v.toGuiX(maxX + 1.0);
		double gy1 = v.toGuiY(maxZ + 1.0);
		int left = v.left, top = v.top, right = v.left + v.width, bottom = v.top + v.height;
		if (gx1 < left || gx0 > right || gy1 < top || gy0 > bottom) {
			return;
		}
		int x0 = (int) Math.floor(gx0), x1 = Math.max(x0 + 1, (int) Math.ceil(gx1));
		int y0 = (int) Math.floor(gy0), y1 = Math.max(y0 + 1, (int) Math.ceil(gy1));
		int cx0 = Math.max(x0, left), cx1 = Math.min(x1, right);
		int cy0 = Math.max(y0, top), cy1 = Math.min(y1, bottom);
		if (y0 >= top && y0 < bottom) {
			g.fill(cx0, y0, cx1, y0 + 1, color);
		}
		if (y1 - 1 >= top && y1 - 1 < bottom) {
			g.fill(cx0, y1 - 1, cx1, y1, color);
		}
		if (x0 >= left && x0 < right) {
			g.fill(x0, cy0, x0 + 1, cy1, color);
		}
		if (x1 - 1 >= left && x1 - 1 < right) {
			g.fill(x1 - 1, cy0, x1, cy1, color);
		}
	}

	public static void brush(Gfx g, MapView v, Brush b, int cx, int cz, int color) {
		if (b.square) {
			rect(g, v, cx - b.radius, cz - b.radius, cx + b.radius, cz + b.radius, color);
			return;
		}
		double r = (b.radius + 0.5) / v.blocksPerGuiPixel();
		double gx = v.toGuiX(cx + 0.5);
		double gy = v.toGuiY(cz + 0.5);
		if (r < 2) {
			g.fill((int) gx - 1, (int) gy - 1, (int) gx + 2, (int) gy + 2, color);
			return;
		}
		int points = (int) MathUtil.clamp(r * 4, 24, 720);
		for (int i = 0; i < points; i++) {
			double a = i * Math.PI * 2 / points;
			int px = (int) Math.round(gx + Math.cos(a) * r);
			int py = (int) Math.round(gy + Math.sin(a) * r);
			if (v.contains(px, py)) {
				g.fill(px, py, px + 1, py + 1, color);
			}
		}
	}

	/** A small cross at a block position (only if on the map). */
	public static void cross(Gfx g, MapView v, double x, double z, int color) {
		double gxd = v.toGuiX(x);
		double gyd = v.toGuiY(z);
		if (!v.contains(gxd, gyd)) {
			return;
		}
		int gx = (int) Math.round(gxd);
		int gy = (int) Math.round(gyd);
		g.fill(gx - 4, gy, gx + 5, gy + 1, color);
		g.fill(gx, gy - 4, gx + 1, gy + 5, color);
	}
}
