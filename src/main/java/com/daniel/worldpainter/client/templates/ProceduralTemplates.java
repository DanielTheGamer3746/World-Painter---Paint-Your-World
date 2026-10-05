package com.daniel.worldpainter.client.templates;

import com.daniel.worldpainter.util.MathUtil;

import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.data.FluidType;

import java.util.List;

/**
 * Built-in generated templates: islands, continents, mountains, volcanoes, lakes, rivers... Sized for
 * Beta 1.7.3: the world is 128 blocks high with the sea at Y 63, and its biomes are Beta's.
 */
public final class ProceduralTemplates {
	public static final int MIN_SIZE = 32;
	public static final int MAX_SIZE = 2048;
	private static final int SEA = PainterState.SEA_LEVEL;

	private ProceduralTemplates() {
	}

	@FunctionalInterface
	private interface Gen {
		void generate(TemplateData d, int size, Noise n, Noise climate, Noise wet, double tempBias);
	}

	private record Procedural(String name, String description, Gen gen) implements Template {
		@Override
		public boolean resizable() {
			return true;
		}

		@Override
		public TemplateData create(int size, long seed) {
			int s = MathUtil.clamp(size, MIN_SIZE, MAX_SIZE);
			TemplateData d = new TemplateData(s, s);
			double bias = (new java.util.Random(seed).nextDouble() - 0.5) * 0.6;
			gen.generate(d, s, new Noise(seed), new Noise(seed * 31 + 7), new Noise(seed * 17 + 3), bias);
			return d;
		}

		@Override
		public int width(int size) {
			return MathUtil.clamp(size, MIN_SIZE, MAX_SIZE);
		}

		@Override
		public int depth(int size) {
			return width(size);
		}
	}

	public static final List<Template> ALL = List.of(
			new Procedural("Island", "A round island in the ocean", ProceduralTemplates::island),
			new Procedural("Continent", "A large landmass with mountains and coasts", ProceduralTemplates::continent),
			new Procedural("Archipelago", "Many small islands", ProceduralTemplates::archipelago),
			new Procedural("Mountain range", "A diagonal chain of peaks through lowlands", ProceduralTemplates::mountains),
			new Procedural("Volcano", "A volcanic island with a lava crater", ProceduralTemplates::volcano),
			new Procedural("Lake", "Grassland around a lake", ProceduralTemplates::lake),
			new Procedural("River valley", "A winding river through green land", ProceduralTemplates::river),
			new Procedural("Canyon", "Badlands mesa cut by a deep canyon", ProceduralTemplates::canyon),
			new Procedural("Flat plains", "Completely flat grassland", ProceduralTemplates::flat)
	);

	// ---- generators ----

	private static void island(TemplateData d, int size, Noise n, Noise climate, Noise wet, double bias) {
		double c = size / 2.0, f = 4.0 / size;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				double dist = radial(x, z, c) + n.fbm(x * f, z * f, 3) * 0.18;
				double mask = 1 - smooth(0.35, 0.95, dist);
				double e = mask * (0.55 + 0.45 * n.fbm(x * f * 1.5 + 50, z * f * 1.5, 5));
				int h = (int) Math.round(SEA - 20 + e * 58);
				finish(d, x, z, h, climate, wet, f, bias);
			}
		}
	}

	private static void continent(TemplateData d, int size, Noise n, Noise climate, Noise wet, double bias) {
		double c = size / 2.0, f = 3.0 / size;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				double dist = radial(x, z, c) + n.fbm(x * f, z * f, 4) * 0.3;
				double mask = 1 - smooth(0.5, 1.0, dist);
				double base = 0.45 + 0.35 * n.fbm(x * f * 2 + 70, z * f * 2, 5);
				double ridges = n.ridged(x * f * 3 + 11, z * f * 3 - 7, 5);
				double e = mask * base + mask * mask * Math.max(0, ridges - 0.45) * 0.9;
				int h = (int) Math.round(SEA - 24 + e * 62);
				finish(d, x, z, h, climate, wet, f, bias);
			}
		}
	}

	private static void archipelago(TemplateData d, int size, Noise n, Noise climate, Noise wet, double bias) {
		double c = size / 2.0, f = 10.0 / size;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				double fade = 1 - smooth(0.65, 1.0, radial(x, z, c));
				double v = (n.fbm(x * f, z * f, 5) * 0.5 + 0.5) * fade;
				int h = (int) Math.round(SEA - 18 + v * 40);
				finish(d, x, z, h, climate, wet, f / 3, bias);
			}
		}
	}

	private static void mountains(TemplateData d, int size, Noise n, Noise climate, Noise wet, double bias) {
		double f = 3.0 / size;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				double nx = x / (double) size, nz = z / (double) size;
				// distance to the diagonal from (0.15, 0.85) to (0.85, 0.15)
				double lineDist = Math.abs(nx + nz - 1.0) / Math.sqrt(2) + n.fbm(x * f, z * f, 3) * 0.08;
				double edge = 1 - smooth(0.3, 0.5, Math.max(Math.abs(nx - 0.5), Math.abs(nz - 0.5)));
				double band = (1 - smooth(0.0, 0.3, lineDist)) * edge;
				double r = n.ridged(x * f * 2.5, z * f * 2.5, 5);
				int base = SEA + 4 + (int) Math.round(n.fbm(x * f * 4, z * f * 4, 3) * 3);
				int h = Math.min(122, (int) Math.round(base + band * (18 + r * 38)));
				finish(d, x, z, h, climate, wet, f, bias);
			}
		}
	}

	private static void volcano(TemplateData d, int size, Noise n, Noise climate, Noise wet, double bias) {
		double c = size / 2.0, f = 6.0 / size;
		int peak = SEA + 52;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				double dist = radial(x, z, c) + n.fbm(x * f, z * f, 3) * 0.05;
				double cone = Math.pow(Math.max(0, 1 - dist), 1.5);
				int h = (int) Math.round(SEA - 12 + cone * (peak - SEA + 12) + n.fbm(x * f * 3, z * f * 3, 4) * 6);
				if (dist < 0.12) {
					int rim = (int) Math.round(SEA - 12 + Math.pow(1 - 0.12, 1.5) * (peak - SEA + 12));
					h = rim - (int) Math.round((1 - dist / 0.12) * 14);
					if (dist < 0.09) {
						d.setFluid(x, z, FluidType.LAVA, rim - 10);
					}
				}
				d.setHeight(x, z, h);
				if (h < SEA - 1) {
					d.setBiome(x, z, "rainforest");
				} else if (dist < 0.3) {
					d.setBiome(x, z, "savanna");
					d.setSurface(x, z, dist < 0.16 ? "obsidian" : dist < 0.22 ? "stone" : "gravel");
				} else if (h <= SEA + 2) {
					d.setBiome(x, z, "rainforest");
					d.setSurface(x, z, "sand");
				} else {
					d.setBiome(x, z, dist < 0.45 ? "shrubland" : "rainforest");
				}
			}
		}
	}

	private static void lake(TemplateData d, int size, Noise n, Noise climate, Noise wet, double bias) {
		double c = size / 2.0, f = 5.0 / size;
		int land = SEA + 6;
		int water = land - 2;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				double dist = radial(x, z, c) + n.fbm(x * f, z * f, 4) * 0.15;
				int ground = land + (int) Math.round(n.fbm(x * f * 2 + 40, z * f * 2, 4) * 4);
				if (dist < 0.5) {
					int depth = (int) Math.round(Math.pow(1 - dist / 0.5, 0.7) * 10) + 1;
					d.setHeight(x, z, water - depth);
					d.setFluid(x, z, FluidType.WATER, water);
					d.setBiome(x, z, lowland(climate, wet, x * f / 3, z * f / 3, bias));
					d.setSurface(x, z, dist > 0.4 ? "sand" : "clay");
				} else {
					d.setHeight(x, z, Math.max(ground, water + 1));
					d.setBiome(x, z, lowland(climate, wet, x * f / 3, z * f / 3, bias));
					if (dist < 0.56) {
						d.setSurface(x, z, "sand");
					}
				}
			}
		}
	}

	private static void river(TemplateData d, int size, Noise n, Noise climate, Noise wet, double bias) {
		double f = 3.0 / size;
		double halfWidth = Math.max(4, size * 0.025);
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				double center = size / 2.0 + Math.sin(x * f * 2.2) * size * 0.18 + n.fbm(x * f, 3.3, 3) * size * 0.1;
				double dist = Math.abs(z - center);
				if (dist < halfWidth) {
					d.setHeight(x, z, SEA - 3 - (int) Math.round((1 - dist / halfWidth) * 4));
					d.setBiome(x, z, lowland(climate, wet, x * f, z * f, bias));
					d.setSurface(x, z, n.fbm(x * f * 8, z * f * 8, 2) > 0.2 ? "gravel" : "sand");
				} else {
					double rise = Math.min(1, (dist - halfWidth) / (size * 0.2));
					int h = SEA + 1 + (int) Math.round(rise * 14 + n.fbm(x * f * 4, z * f * 4, 4) * 4 * rise);
					d.setHeight(x, z, h);
					d.setBiome(x, z, lowland(climate, wet, x * f, z * f, bias));
					if (dist < halfWidth + 3) {
						d.setSurface(x, z, "sand");
					}
				}
			}
		}
	}

	private static void canyon(TemplateData d, int size, Noise n, Noise climate, Noise wet, double bias) {
		double f = 3.0 / size;
		double halfWidth = Math.max(6, size * 0.05);
		int mesa = SEA + 34;
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				double center = size / 2.0 + Math.sin(z * f * 2.0) * size * 0.2 + n.fbm(1.7, z * f, 3) * size * 0.1;
				double dist = Math.abs(x - center);
				int top = mesa + (int) Math.round(n.fbm(x * f * 3, z * f * 3, 4) * 6);
				if (dist < halfWidth) {
					double wall = Math.pow(dist / halfWidth, 3);
					int floor = SEA + 5;
					d.setHeight(x, z, (int) Math.round(floor + (top - floor) * wall));
					if (dist < halfWidth * 0.3) {
						d.setFluid(x, z, FluidType.WATER, floor + 1);
					}
					d.setBiome(x, z, "desert");
					d.setSurface(x, z, wall > 0.3 ? "sandstone" : "sand");
				} else {
					d.setHeight(x, z, top);
					d.setBiome(x, z, n.fbm(x * f * 2, z * f * 2, 2) > 0.2 ? "savanna" : "desert");
				}
			}
		}
	}

	private static void flat(TemplateData d, int size, Noise n, Noise climate, Noise wet, double bias) {
		for (int z = 0; z < size; z++) {
			for (int x = 0; x < size; x++) {
				d.setHeight(x, z, SEA + 1);
				d.setBiome(x, z, "plains");
			}
		}
	}

	// ---- helpers ----

	private static void finish(TemplateData d, int x, int z, int h, Noise climate, Noise wet, double f, double bias) {
		d.setHeight(x, z, h);
		double t = climate.fbm(x * f * 0.7 + 300, z * f * 0.7, 3) * 0.9 + bias;
		double m = wet.fbm(x * f * 0.9 - 200, z * f * 0.9, 3) * 0.9;
		d.setBiome(x, z, biomeFor(h, t, m));
		if (h >= SEA - 4 && h <= SEA + 1) {
			// Beaches: Beta has no beach biome, its beaches are sand at sea level.
			d.setSurface(x, z, "sand");
		}
	}

	private static String lowland(Noise climate, Noise wet, double x, double z, double bias) {
		double t = climate.fbm(x + 300, z, 3) * 0.9 + bias;
		double m = wet.fbm(x - 200, z, 3) * 0.9;
		return biomeFor(SEA + 10, t, m);
	}

	/**
	 * Picks a sensible Beta biome from height, temperature (-1..1) and moisture (-1..1). Beta has no
	 * ocean, beach or mountain biomes: water and peaks take the biome of their climate, high land is colder.
	 */
	public static String biomeFor(int h, double t, double m) {
		if (h > SEA + 45) {
			return t > 0.45 ? "shrubland" : "tundra";
		}
		if (h > SEA + 28) {
			return t < 0 ? "taiga" : m > 0.1 ? "forest" : "shrubland";
		}
		if (t > 0.55) {
			return m < -0.2 ? "desert" : m < 0.25 ? "savanna" : "rainforest";
		}
		if (t > 0.2) {
			return m < -0.3 ? "plains" : m < 0.1 ? "forest" : m < 0.35 ? "seasonal_forest" : "swampland";
		}
		if (t > -0.3) {
			return m < -0.25 ? "plains" : m < 0.2 ? "shrubland" : "taiga";
		}
		return m < 0 ? "tundra" : "taiga";
	}

	private static double radial(int x, int z, double c) {
		double nx = (x - c) / c, nz = (z - c) / c;
		return Math.sqrt(nx * nx + nz * nz);
	}

	private static double smooth(double e0, double e1, double v) {
		double t = MathUtil.clamp((v - e0) / (e1 - e0), 0.0, 1.0);
		return t * t * (3 - 2 * t);
	}
}
