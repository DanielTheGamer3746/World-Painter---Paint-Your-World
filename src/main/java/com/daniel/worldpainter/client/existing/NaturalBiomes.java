package com.daniel.worldpainter.client.existing;

import com.daniel.worldpainter.gen.GenPaint;
import net.minecraft.util.math.noise.OctaveSimplexNoiseSampler;
import net.minecraft.world.biome.Biome;

import java.util.Random;

/**
 * The biomes Beta 1.7.3 generates for a seed, computed exactly like its biome source does (Beta
 * does not save biomes in its chunks). Used to show the biomes of land that is not painted.
 */
public final class NaturalBiomes {
	private final OctaveSimplexNoiseSampler temperatureNoise;
	private final OctaveSimplexNoiseSampler rainfallNoise;
	private final OctaveSimplexNoiseSampler detailNoise;
	private double[] temperature = new double[256];
	private double[] rainfall = new double[256];
	private double[] detail = new double[256];

	public NaturalBiomes(long seed) {
		temperatureNoise = new OctaveSimplexNoiseSampler(new Random(seed * 9871L), 4);
		rainfallNoise = new OctaveSimplexNoiseSampler(new Random(seed * 39811L), 4);
		detailNoise = new OctaveSimplexNoiseSampler(new Random(seed * 543321L), 2);
	}

	/**
	 * Biome ids of a 16 x 16 area starting at block (x, z), indexed {@code dx * 16 + dz}
	 * (the order Beta uses).
	 */
	public synchronized String[] area(int x, int z, String[] out) {
		temperature = temperatureNoise.sample(temperature, x, z, 16, 16, 0.02500000037252903, 0.02500000037252903, 0.25);
		rainfall = rainfallNoise.sample(rainfall, x, z, 16, 16, 0.05000000074505806, 0.05000000074505806, 0.3333333333333333);
		detail = detailNoise.sample(detail, x, z, 16, 16, 0.25, 0.25, 0.5882352941176471);
		for (int i = 0; i < 256; i++) {
			out[i] = GenPaint.idOf(biome(temperature[i], rainfall[i], detail[i]));
		}
		return out;
	}

	/** Beta's biome source math for one column. */
	private static Biome biome(double t, double r, double detail) {
		double d = detail * 1.1 + 0.5;
		double temp = (t * 0.15 + 0.7) * (1.0 - 0.01) + d * 0.01;
		double rain = (r * 0.15 + 0.5) * (1.0 - 0.002) + d * 0.002;
		temp = 1.0 - (1.0 - temp) * (1.0 - temp);
		temp = Math.max(0.0, Math.min(1.0, temp));
		rain = Math.max(0.0, Math.min(1.0, rain));
		return Biome.getBiome(temp, rain);
	}

	/** Reads the seed from a world's level.dat, or null. */
	public static Long seedOf(java.nio.file.Path worldRoot) {
		java.nio.file.Path file = worldRoot.resolve("level.dat");
		if (!java.nio.file.Files.isRegularFile(file)) {
			return null;
		}
		try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
				new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(file))))) {
			java.util.Map<String, Object> root = MiniNbt.readRoot(in);
			java.util.Map<String, Object> data = MiniNbt.compound(root, "Data");
			return data == null ? null : MiniNbt.longValue(data, "RandomSeed");
		} catch (java.io.IOException | RuntimeException e) {
			return null;
		}
	}
}
