package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.data.BetaBiomes;
import com.daniel.worldpainter.data.BetaBlocks;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintWorld;
import net.minecraft.world.biome.Biome;

import java.util.HashMap;
import java.util.Map;

/** A world's paint data plus lookups of painted ids to Beta's biomes and blocks, used during generation. */
public final class GenPaint {
	public final PaintWorld world;
	public final PaintDimension dimension;
	private final Map<String, Biome> biomes = new HashMap<>();
	private final Map<String, BetaBiomes.Entry> climates = new HashMap<>();

	public GenPaint(PaintWorld world, PaintDimension dimension) {
		this.world = world;
		this.dimension = dimension;
	}

	/** Beta's biome object for a painted id, or null if the id is unknown. */
	public synchronized Biome biome(String id) {
		if (!biomes.containsKey(id)) {
			biomes.put(id, byId(id));
		}
		return biomes.get(id);
	}

	/** Temperature and rainfall that make Beta pick the painted biome (null if unknown). */
	public synchronized BetaBiomes.Entry climate(String id) {
		if (!climates.containsKey(id)) {
			climates.put(id, BetaBiomes.get(id));
		}
		return climates.get(id);
	}

	/** The block for a painted surface id, or null if unknown. */
	public BetaBlocks.Surface block(String id) {
		return BetaBlocks.surface(id);
	}

	public static Biome byId(String id) {
		BetaBiomes.Entry e = BetaBiomes.get(id);
		if (e == null) {
			return null;
		}
		switch (e.id()) {
			case "rainforest": return Biome.RAINFOREST;
			case "swampland": return Biome.SWAMPLAND;
			case "seasonal_forest": return Biome.SEASONAL_FOREST;
			case "forest": return Biome.FOREST;
			case "savanna": return Biome.SAVANNA;
			case "shrubland": return Biome.SHRUBLAND;
			case "taiga": return Biome.TAIGA;
			case "desert": return Biome.DESERT;
			case "plains": return Biome.PLAINS;
			case "tundra": return Biome.TUNDRA;
			case "ice_desert": return Biome.ICE_DESERT;
			default: return null;
		}
	}

	/** The painter's id for one of Beta's biomes (null for the Nether's and the Sky's). */
	public static String idOf(Biome biome) {
		for (BetaBiomes.Entry e : BetaBiomes.ALL) {
			if (byId(e.id()) == biome) {
				return e.id();
			}
		}
		return null;
	}
}
