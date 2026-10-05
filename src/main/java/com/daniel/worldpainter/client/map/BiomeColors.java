package com.daniel.worldpainter.client.map;

import com.daniel.worldpainter.data.BetaBiomes;

import java.util.List;
import java.util.Locale;

/** The biomes of Beta 1.7.3 for the biome picker, with a map color and a readable name each. */
public final class BiomeColors {
	private BiomeColors() {
	}

	/** Every Beta Overworld biome, in the order of the picker. */
	public static List<String> vanillaIds() {
		return BetaBiomes.ids();
	}

	/** Map color of a biome, or 0 for null / unknown ids. */
	public static int color(String id) {
		BetaBiomes.Entry e = BetaBiomes.get(id);
		return e == null ? (id == null ? 0 : 0xFF8A8A8A) : e.color();
	}

	/** "Seasonal Forest" style name. */
	public static String pretty(String id) {
		BetaBiomes.Entry e = BetaBiomes.get(id);
		return e != null ? e.name() : words(id);
	}

	/** The dimension a biome belongs to (all of Beta's paintable biomes are Overworld biomes). */
	public static String dimension(String id) {
		return "Overworld";
	}

	/** Title-cased words of an id, without a namespace ("minecraft:ice_desert" becomes "Ice Desert"). */
	public static String words(String id) {
		if (id == null) {
			return "";
		}
		String path = id.substring(id.indexOf(':') + 1);
		StringBuilder sb = new StringBuilder();
		for (String w : path.split("[_/]")) {
			if (w.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
		}
		return sb.toString();
	}
}
