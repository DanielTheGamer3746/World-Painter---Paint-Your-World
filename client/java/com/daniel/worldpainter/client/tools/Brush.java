package com.daniel.worldpainter.client.tools;

/** Size, shape and softness of the brush. Every pixel of the map is one block. */
public final class Brush {
	public static final int MAX_RADIUS = 1024;

	public int radius = 16;
	public boolean square = false;
	/** 0 = very soft edge, 1 = hard edge. */
	public float hardness = 0.6f;
	/** How strong height tools act per dab (blocks), or how much smoothing is applied. */
	public int strength = 4;

	/** Weight 0..1 of a pixel at offset (dx, dz) from the brush center. */
	public float weight(int dx, int dz) {
		double r = radius + 0.5;
		double d = square ? Math.max(Math.abs(dx), Math.abs(dz)) : Math.sqrt((double) dx * dx + (double) dz * dz);
		if (d > r) {
			return 0f;
		}
		double t = d / r;
		if (t <= hardness || hardness >= 1f) {
			return 1f;
		}
		double u = 1.0 - (t - hardness) / (1.0 - hardness);
		return (float) (u * u * (3 - 2 * u));
	}

	/** For layers that are either painted or not (biome, surface): paint where the weight reaches half. */
	public boolean covers(int dx, int dz) {
		double r = radius + 0.5;
		if (square) {
			return Math.abs(dx) <= radius && Math.abs(dz) <= radius;
		}
		return (double) dx * dx + (double) dz * dz <= r * r;
	}
}
