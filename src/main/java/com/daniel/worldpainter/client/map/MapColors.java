package com.daniel.worldpainter.client.map;

import com.daniel.worldpainter.util.MathUtil;

import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.PaintTile;

/** Color math shared by the map renderer and the zoomed-out tile index. */
public final class MapColors {
	public static final int WATER = 0xFF3F76E4;
	public static final int LAVA = 0xFFD65A1A;
	public static final int DRY = 0xFFC8B47A;
	public static final int OUTSIDE_WORLD = 0xFF2A0C0C;
	public static final int EXPLORED = 0xFF3D3D47;
	public static final int LAND_NO_BIOME = 0xFF7A9A5A;
	/** Beta's sea: water up to Y 63. */
	public static final int SEA_LEVEL = 64;

	private MapColors() {
	}

	public static int mix(int a, int b, double t) {
		t = MathUtil.clamp(t, 0.0, 1.0);
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	public static int scale(int c, double f) {
		int r = (int) MathUtil.clamp(((c >> 16) & 255) * f, 0, 255);
		int g = (int) MathUtil.clamp(((c >> 8) & 255) * f, 0, 255);
		int b = (int) MathUtil.clamp((c & 255) * f, 0, 255);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	public static int background(int x, int z, double blocksPerPixel) {
		int cell = blocksPerPixel <= 2 ? 4 : blocksPerPixel <= 64 ? 9 : 14;
		boolean odd = (((x >> cell) ^ (z >> cell)) & 1) != 0;
		return odd ? 0xFF1F1F25 : 0xFF25252C;
	}

	public static int heightGray(int h) {
		int v = (int) MathUtil.clamp(30 + h * (225.0 / 128.0), 20, 255);
		return 0xFF000000 | (v << 16) | (v << 8) | v;
	}

	/** Water/lava overlay for a column with ground at {@code ground} and fluid up to {@code top}. */
	public static int fluid(int base, boolean lava, int depth) {
		return mix(base, lava ? LAVA : WATER, lava ? 0.85 : MathUtil.clamp(0.45 + depth * 0.03, 0.45, 0.88));
	}

	/** Highest fluid block for a painted column, or Integer.MIN_VALUE for none (mirrors world generation). */
	public static int fluidTop(short packedFluid, short height) {
		if (packedFluid != FluidType.NONE) {
			return FluidType.type(packedFluid) == FluidType.DRY ? Integer.MIN_VALUE : FluidType.level(packedFluid);
		}
		if (height != PaintTile.NO_HEIGHT && height < SEA_LEVEL - 1) {
			return SEA_LEVEL - 1;
		}
		return Integer.MIN_VALUE;
	}

	/** Average color of a tile for far zoom levels (samples a 16x16 grid). */
	public static int summarize(PaintTile t) {
		long r = 0, g = 0, b = 0;
		int n = 0;
		for (int z = 8; z < PaintTile.SIZE; z += 16) {
			for (int x = 8; x < PaintTile.SIZE; x += 16) {
				int i = PaintTile.index(x, z);
				int c = pixel(t, i);
				if (c == 0) {
					continue;
				}
				r += (c >> 16) & 255;
				g += (c >> 8) & 255;
				b += c & 255;
				n++;
			}
		}
		if (n == 0) {
			return 0;
		}
		return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
	}

	/** Flat composite color of one painted pixel without shading, or 0 if nothing is painted. */
	public static int pixel(PaintTile t, int i) {
		String biome = t.biomeAt(i);
		short h = t.height.get(i);
		String surface = t.surfaceAt(i);
		short f = t.fluid.get(i);
		if (biome == null && h == PaintTile.NO_HEIGHT && surface == null && f == FluidType.NONE) {
			return 0;
		}
		int c = biome != null ? BiomeColors.color(biome) : LAND_NO_BIOME;
		if (surface != null) {
			c = mix(c, SurfaceColors.color(surface), 0.6);
		}
		int top = fluidTop(f, h);
		if (top != Integer.MIN_VALUE && (h == PaintTile.NO_HEIGHT || top > h)) {
			boolean lava = f != FluidType.NONE && FluidType.type(f) == FluidType.LAVA;
			c = fluid(c, lava, h == PaintTile.NO_HEIGHT ? 4 : top - h);
		}
		return c;
	}
}
