package com.daniel.worldpainter.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The biomes of Beta 1.7.3. Beta picks a biome from two noise maps, temperature and rainfall
 * ({@code Biome.getBiome(temperature, rainfall)}), and the same two values also decide grass and
 * leaf colors, snow and ice, and how hilly the terrain is. A painted biome therefore comes with a
 * temperature and rainfall that make Minecraft choose exactly that biome.
 */
public final class BetaBiomes {
	/**
	 * One biome. {@code temperature} and {@code rainfall} are values (0..1, as Beta stores them)
	 * that select this biome; {@code natural} is false for the biome Beta defines but never generates.
	 */
	public record Entry(String id, String name, double temperature, double rainfall, int color, boolean natural, String about) {
	}

	public static final List<Entry> ALL;

	static {
		List<Entry> l = new ArrayList<>();
		l.add(new Entry("rainforest", "Rainforest", 1.0, 0.95, 0xFF3FBF2F, true, "Hot and wet: lush grass, many trees (some big), lots of tall grass and ferns"));
		l.add(new Entry("swampland", "Swampland", 0.6, 1.0, 0xFF4F7A45, true, "Wet: oak trees, flat land, water pools"));
		l.add(new Entry("seasonal_forest", "Seasonal Forest", 1.0, 0.65, 0xFF7FB238, true, "Warm: forest with flowers"));
		l.add(new Entry("forest", "Forest", 0.8, 0.7, 0xFF4C9A2E, true, "Oak and birch trees"));
		l.add(new Entry("savanna", "Savanna", 0.7, 0.1, 0xFFBDB25F, true, "Dry grass, few trees"));
		l.add(new Entry("shrubland", "Shrubland", 0.8, 0.35, 0xFF95A85A, true, "Grass with scattered bushes and trees"));
		l.add(new Entry("taiga", "Taiga", 0.3, 0.8, 0xFF5E8C73, true, "Cold: spruce trees, snow and frozen water"));
		l.add(new Entry("desert", "Desert", 1.0, 0.1, 0xFFE3D59B, true, "Sand, sandstone, cactus and dead bushes, no rain"));
		l.add(new Entry("plains", "Plains", 1.0, 0.3, 0xFF8DC25A, true, "Open grass land with flowers"));
		l.add(new Entry("tundra", "Tundra", 0.05, 0.5, 0xFFDDEEF5, true, "Frozen: snow everywhere, ice on water"));
		l.add(new Entry("ice_desert", "Ice Desert", 0.05, 0.0, 0xFFE8E8D8, false, "Unused in Beta's own worlds: snowy sand, no rain"));
		ALL = Collections.unmodifiableList(l);
	}

	private BetaBiomes() {
	}

	public static Entry get(String id) {
		if (id == null) {
			return null;
		}
		String key = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		for (Entry e : ALL) {
			if (e.id().equals(key)) {
				return e;
			}
		}
		return null;
	}

	public static List<String> ids() {
		List<String> out = new ArrayList<>();
		for (Entry e : ALL) {
			out.add(e.id());
		}
		return out;
	}

	/**
	 * Beta's own biome rule ({@code Biome.locateBiome}), on the 64 x 64 grid {@code Biome.getBiome}
	 * looks values up in. Used to check the painted temperature/rainfall really select the biome.
	 */
	public static String vanillaChoice(double temperature, double rainfall) {
		float t = (int) (temperature * 63.0) / 63.0F;
		float r = (int) (rainfall * 63.0) / 63.0F;
		r *= t;
		if (t < 0.1F) {
			return "tundra";
		}
		if (r < 0.2F) {
			return t < 0.5F ? "tundra" : t < 0.95F ? "savanna" : "desert";
		}
		if (r > 0.5F && t < 0.7F) {
			return "swampland";
		}
		if (t < 0.5F) {
			return "taiga";
		}
		if (t < 0.97F) {
			return r < 0.35F ? "shrubland" : "forest";
		}
		return r < 0.45F ? "plains" : r < 0.9F ? "seasonal_forest" : "rainforest";
	}
}
