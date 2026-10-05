package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.data.BetaBiomes;
import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.PaintWorld;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

/**
 * Painted biomes in Beta's biome source. Beta stores, next to the biome of every block column,
 * the temperature and rainfall it was picked from; grass and leaf colors, snow, ice and the shape of
 * the terrain all come from those two values. So where a biome is painted, the biome is replaced and
 * the temperature and rainfall are moved towards values that select that biome. Towards the edge of
 * a painted area they are blended with the natural ones, so colors and hills change smoothly
 * (the biome itself stays exactly as painted).
 */
public final class BiomePaint {
	/** Blocks around a column that count for blending temperature and rainfall. */
	private static final int BLEND = 4;
	private static final int SAMPLES = (2 * BLEND + 1) * (2 * BLEND + 1);

	private BiomePaint() {
	}

	/**
	 * After Beta filled an area ({@code x}, {@code z} is its corner, {@code width} along X and
	 * {@code depth} along Z; arrays are indexed {@code dx * depth + dz}).
	 */
	public static void apply(World world, Biome[] biomes, double[] temperature, double[] rainfall, int x, int z, int width, int depth) {
		GenPaint paint = PaintBindings.get(world);
		if (paint == null || biomes == null || !touchesPaint(paint.world, x, z, width, depth)) {
			return;
		}
		double[] sum = new double[3];
		for (int dx = 0; dx < width; dx++) {
			for (int dz = 0; dz < depth; dz++) {
				int i = dx * depth + dz;
				int bx = x + dx, bz = z + dz;
				String id = biomeAt(paint.world, bx, bz);
				if (id != null && i < biomes.length) {
					Biome b = paint.biome(id);
					if (b != null) {
						biomes[i] = b;
					}
				}
				if (blend(paint, bx, bz, sum)) {
					double f = sum[0];
					if (temperature != null && i < temperature.length) {
						temperature[i] = temperature[i] * (1 - f) + sum[1] * f;
					}
					if (rainfall != null && i < rainfall.length) {
						rainfall[i] = rainfall[i] * (1 - f) + sum[2] * f;
					}
				}
			}
		}
	}

	/** Beta's temperature-only lookup (snow and ice placement). */
	public static void applyTemperatures(World world, double[] temperature, int x, int z, int width, int depth) {
		GenPaint paint = PaintBindings.get(world);
		if (paint == null || temperature == null || !touchesPaint(paint.world, x, z, width, depth)) {
			return;
		}
		double[] sum = new double[3];
		for (int dx = 0; dx < width; dx++) {
			for (int dz = 0; dz < depth; dz++) {
				int i = dx * depth + dz;
				if (i < temperature.length && blend(paint, x + dx, z + dz, sum)) {
					temperature[i] = temperature[i] * (1 - sum[0]) + sum[1] * sum[0];
				}
			}
		}
	}

	/**
	 * Share of painted columns around a block and their average temperature and rainfall, written to
	 * {@code out} (share, temperature, rainfall). False if nothing around is painted.
	 */
	private static boolean blend(GenPaint paint, int x, int z, double[] out) {
		int n = 0;
		double t = 0, r = 0;
		for (int dz = -BLEND; dz <= BLEND; dz++) {
			for (int dx = -BLEND; dx <= BLEND; dx++) {
				String id = biomeAt(paint.world, x + dx, z + dz);
				if (id == null) {
					continue;
				}
				BetaBiomes.Entry e = paint.climate(id);
				if (e == null) {
					continue;
				}
				n++;
				t += e.temperature();
				r += e.rainfall();
			}
		}
		if (n == 0) {
			return false;
		}
		out[0] = n / (double) SAMPLES;
		out[1] = t / n;
		out[2] = r / n;
		return true;
	}

	private static String biomeAt(PaintWorld world, int x, int z) {
		PaintTile t = world.getForBlock(x, z);
		return t == null ? null : t.biomeAt(PaintTile.indexForBlock(x, z));
	}

	/** Quick test: is any tile with painted biomes near this area? (Most lookups are far from paint.) */
	private static boolean touchesPaint(PaintWorld world, int x, int z, int width, int depth) {
		int tx0 = (x - BLEND) >> PaintTile.SHIFT, tx1 = (x + width + BLEND) >> PaintTile.SHIFT;
		int tz0 = (z - BLEND) >> PaintTile.SHIFT, tz1 = (z + depth + BLEND) >> PaintTile.SHIFT;
		for (int tz = tz0; tz <= tz1; tz++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				PaintTile t = world.get(tx, tz);
				if (t != null && t.hasBiomes()) {
					return true;
				}
			}
		}
		return false;
	}
}
