package com.daniel.worldpainter.client.map;

import com.daniel.worldpainter.util.MathUtil;

import com.daniel.worldpainter.data.PaintWorld;

/**
 * The 2D camera: which block is in the middle of the map and how zoomed in it is.
 * Zoom is a power of two: level 0 = 1 block per screen pixel, negative = zoomed in
 * (level -4 = 16 pixels per block), positive = zoomed out (level 16 shows the whole
 * 60,000,000 block world on a normal screen).
 */
public final class MapView {
	public static final int MIN_ZOOM = -5;
	public static final int MAX_ZOOM = 17;

	public double centerX;
	public double centerZ;
	public int zoom = 0;

	/** Map rectangle on screen, in GUI coordinates. */
	public int left, top, width, height;
	/** GUI scale (physical pixels per GUI pixel). */
	public double guiScale = 1;

	/** Blocks per physical screen pixel. */
	public double blocksPerPixel() {
		return Math.scalb(1.0, zoom);
	}

	/** Blocks per GUI pixel. */
	public double blocksPerGuiPixel() {
		return blocksPerPixel() * guiScale;
	}

	public double toWorldX(double guiX) {
		return centerX + (guiX - (left + width / 2.0)) * blocksPerGuiPixel();
	}

	public double toWorldZ(double guiY) {
		return centerZ + (guiY - (top + height / 2.0)) * blocksPerGuiPixel();
	}

	public double toGuiX(double worldX) {
		return left + width / 2.0 + (worldX - centerX) / blocksPerGuiPixel();
	}

	public double toGuiY(double worldZ) {
		return top + height / 2.0 + (worldZ - centerZ) / blocksPerGuiPixel();
	}

	public boolean contains(double guiX, double guiY) {
		return guiX >= left && guiX < left + width && guiY >= top && guiY < top + height;
	}

	/** Zooms by {@code steps} levels, keeping the block under the given screen point in place. */
	public void zoomAt(double guiX, double guiY, int steps) {
		double wx = toWorldX(guiX);
		double wz = toWorldZ(guiY);
		zoom = MathUtil.clamp(zoom + steps, MIN_ZOOM, MAX_ZOOM);
		centerX = wx - (guiX - (left + width / 2.0)) * blocksPerGuiPixel();
		centerZ = wz - (guiY - (top + height / 2.0)) * blocksPerGuiPixel();
		clamp();
	}

	public void panGui(double dx, double dy) {
		centerX -= dx * blocksPerGuiPixel();
		centerZ -= dy * blocksPerGuiPixel();
		clamp();
	}

	public void clamp() {
		double lim = PaintWorld.WORLD_LIMIT + 1000.0;
		centerX = MathUtil.clamp(centerX, -lim, lim);
		centerZ = MathUtil.clamp(centerZ, -lim, lim);
	}

	/** Human readable scale, e.g. "1:1", "4px/block", "1px = 4096 blocks". */
	public String scaleText() {
		if (zoom < 0) {
			return (1 << -zoom) + " px per block";
		}
		long blocks = 1L << zoom;
		return blocks == 1 ? "1 block per px" : blocks + " blocks per px";
	}
}
