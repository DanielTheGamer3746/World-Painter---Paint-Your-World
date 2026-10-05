package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.util.MathUtil;

import com.daniel.worldpainter.client.edit.Ops;
import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintTile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Smooths the edges between painted areas. For biomes, surface blocks and fluids each pixel takes
 * the most common value around it (removes jagged borders and single stray pixels); on the height
 * layer it blurs heights.
 */
public final class SmoothEdgesTool extends BrushStrokeTool {
	private static final long MAX_WORK = 60_000_000L;

	@Override
	public String name() {
		return "Smooth edges";
	}

	@Override
	public char hotkey() {
		return 'M';
	}

	@Override
	public String help() {
		return "Smooth borders between biomes / surfaces / fluids, or blur heights. Strength = how far it looks.";
	}

	@Override
	protected void dab(PainterState s, int cx, int cz, boolean alt) {
		int k = MathUtil.clamp(1 + s.brush.strength / 6, 1, 10);
		if (s.layer == Layer.HEIGHT) {
			HeightTool.smooth(s, cx, cz, k, 1f);
			return;
		}
		int r = s.brush.radius;
		smoothCategorical(s, s.layer, cx - r, cz - r, cx + r, cz + r, k, (x, z) -> s.brush.covers(x - cx, z - cz));
	}

	public interface Mask {
		boolean test(int x, int z);
	}

	/** Majority filter over a rectangle (inclusive bounds). Returns false if the area was too large. */
	public static boolean smoothCategorical(PainterState s, Layer layer, int minX, int minZ, int maxX, int maxZ, int k, Mask mask) {
		long w = maxX - minX + 1L, h = maxZ - minZ + 1L;
		while (k > 1 && w * h * (2L * k + 1) * (2L * k + 1) > MAX_WORK) {
			k--;
		}
		if (w * h * 9 > MAX_WORK * 3) {
			s.say("Area too large to smooth at once - smooth a smaller selection.");
			return false;
		}
		int x0 = minX - k, z0 = minZ - k;
		int bw = (int) w + 2 * k, bh = (int) h + 2 * k;
		int[] ids = new int[bw * bh];
		List<String> names = new ArrayList<>();
		Map<String, Integer> lookup = new HashMap<>();
		for (int z = 0; z < bh; z++) {
			for (int x = 0; x < bw; x++) {
				String v = read(s, layer, x0 + x, z0 + z);
				if (v == null) {
					ids[z * bw + x] = -1;
				} else {
					Integer id = lookup.get(v);
					if (id == null) {
						id = names.size();
						names.add(v);
						lookup.put(v, id);
					}
					ids[z * bw + x] = id;
				}
			}
		}
		if (names.size() < 2) {
			return true;
		}
		int[] counts = new int[names.size()];
		final int kk = k;
		Ops.forPixels(s.session, minX, minZ, maxX, maxZ, (t, i, x, z) -> {
			if (!mask.test(x, z)) {
				return;
			}
			int lx = x - x0, lz = z - z0;
			int self = ids[lz * bw + lx];
			if (self < 0) {
				return;
			}
			java.util.Arrays.fill(counts, 0);
			int best = self, bestCount = 0;
			for (int dz = -kk; dz <= kk; dz++) {
				int row = (lz + dz) * bw;
				for (int dx = -kk; dx <= kk; dx++) {
					int id = ids[row + lx + dx];
					if (id >= 0) {
						int c = ++counts[id];
						if (c > bestCount || (c == bestCount && id == self)) {
							best = id;
							bestCount = c;
						}
					}
				}
			}
			if (best != self) {
				write(t, i, layer, names.get(best));
			}
		});
		return true;
	}

	private static String read(PainterState s, Layer layer, int x, int z) {
		return switch (layer) {
			case BIOME -> s.paintedBiome(x, z);
			case SURFACE -> s.paintedSurface(x, z);
			case FLUID -> {
				short f = s.paintedFluid(x, z);
				yield f == FluidType.NONE ? null : Short.toString(f);
			}
			case HEIGHT -> {
				short h = s.paintedHeight(x, z);
				yield h == PaintTile.NO_HEIGHT ? null : Short.toString(h);
			}
		};
	}

	private static void write(PaintTile t, int i, Layer layer, String value) {
		switch (layer) {
			case BIOME -> t.biome.set(i, t.biomePalette.indexOf(value));
			case SURFACE -> t.surface.set(i, t.surfacePalette.indexOf(value));
			case FLUID -> t.fluid.set(i, Short.parseShort(value));
			case HEIGHT -> t.height.set(i, Short.parseShort(value));
		}
	}
}
