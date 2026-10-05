package com.daniel.worldpainter.client.map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Biome colors as the biomes look in the game, seen from above: the game's own grass and leaf
 * tints (as they appear on the blocks), how much of the ground trees cover, and the blocks that
 * cover the biome (sand, terracotta, snow, nylium, end stone, ...).
 *
 * <p>Biomes this table does not know (from newer game versions or mods) get their color from their
 * own biome data inside the game or mod: grass and leaf colors, temperature and rainfall.
 */
public final class BiomeColors {
	private static final Map<String, Integer> COLORS = new ConcurrentHashMap<>();
	private static final Map<String, String> DIMENSION = new LinkedHashMap<>();

	// Block colors (average color of the block's top texture).
	private static final int SAND = 0xDBD1A0;
	private static final int RED_SAND = 0xBE6A2E;
	private static final int TERRACOTTA = 0xA35E40;
	private static final int SNOW = 0xF0F4F6;
	private static final int PACKED_ICE = 0xA6C2EE;
	private static final int ICE = 0x95B6EC;
	private static final int STONE = 0x828284;
	private static final int CALCITE = 0xDEDFDA;
	private static final int GRAVEL = 0x857F7D;
	private static final int COARSE_DIRT = 0x77553B;
	private static final int PODZOL = 0x6A4A26;
	private static final int MUD = 0x3E3A3C;
	private static final int MYCELIUM = 0x7D6A72;
	private static final int RED_MUSHROOM = 0xC23A33;
	private static final int DRIPSTONE = 0x86695A;
	private static final int MOSS = 0x5A7A2C;
	private static final int SCULK = 0x0F2A30;
	private static final int NETHERRACK = 0x6F3634;
	private static final int CRIMSON_NYLIUM = 0x8A2021;
	private static final int WARPED_NYLIUM = 0x22786F;
	private static final int SOUL_SAND = 0x51402F;
	private static final int BASALT = 0x4A484E;
	private static final int END_STONE = 0xD9DB9F;
	private static final int CHORUS = 0x8C5F8C;
	private static final int VOID = 0x14121A;

	// Leaves that are not tinted by the biome.
	private static final int BIRCH_LEAVES = 0x80A755;
	private static final int SPRUCE_LEAVES = 0x619961;
	private static final int MANGROVE_LEAVES = 0x8DB127;
	private static final int CHERRY_LEAVES = 0xE7A9C2;
	private static final int PALE_OAK_LEAVES = 0xB9C0B4;
	private static final int BAMBOO = 0x5E9B1F;
	/** The dappled forest's orange canopy. */
	private static final int DAPPLED_CANOPY = 0xDB8A2E;

	// Brightness of the grass and leaf textures (the tint is multiplied with them in the game).
	private static final double GRASS_SHADE = 0.84;
	private static final double LEAF_SHADE = 0.72;

	static {
		// Overworld. land(grass tint, leaf tint, share of the ground covered by trees)
		ow("plains", land(0x91BD59, 0x77AB2F, 0.04));
		ow("sunflower_plains", mix(land(0x91BD59, 0x77AB2F, 0.04), 0xE8C52E, 0.12));
		ow("meadow", mix(land(0x83BB6D, 0x63A948, 0.02), 0xC9A9E0, 0.08));
		ow("forest", land(0x79C05A, 0x59AE30, 0.78));
		ow("flower_forest", mix(land(0x79C05A, 0x59AE30, 0.55), 0xD87FB0, 0.1));
		ow("birch_forest", land(0x88BB67, BIRCH_LEAVES, 0.72));
		ow("old_growth_birch_forest", land(0x88BB67, BIRCH_LEAVES, 0.8));
		ow("dark_forest", land(0x507A32, 0x59AE30, 0.95));
		ow("pale_garden", canopy(0x778272, PALE_OAK_LEAVES, 0.85));
		ow("dappled_forest", canopy(0x9AB24A, DAPPLED_CANOPY, 0.88));
		ow("cherry_grove", canopy(0xB6DB61, CHERRY_LEAVES, 0.75));
		ow("taiga", land(0x86B783, SPRUCE_LEAVES, 0.7));
		ow("old_growth_pine_taiga", mix(land(0x86B87F, SPRUCE_LEAVES, 0.65), PODZOL, 0.22));
		ow("old_growth_spruce_taiga", mix(land(0x86B783, SPRUCE_LEAVES, 0.8), PODZOL, 0.15));
		ow("snowy_taiga", mix(SNOW, leaves(SPRUCE_LEAVES), 0.42));
		ow("snowy_plains", SNOW);
		ow("ice_spikes", mix(SNOW, PACKED_ICE, 0.55));
		ow("grove", mix(SNOW, leaves(SPRUCE_LEAVES), 0.3));
		ow("snowy_slopes", mix(SNOW, STONE, 0.1));
		ow("frozen_peaks", mix(SNOW, PACKED_ICE, 0.35));
		ow("jagged_peaks", mix(SNOW, STONE, 0.18));
		ow("stony_peaks", mix(STONE, CALCITE, 0.25));
		ow("windswept_hills", mix(land(0x8AB689, 0x6DA36B, 0.12), STONE, 0.35));
		ow("windswept_gravelly_hills", mix(GRAVEL, grass(0x8AB689), 0.25));
		ow("windswept_forest", land(0x8AB689, SPRUCE_LEAVES, 0.6));
		ow("windswept_savanna", mix(land(0xBFB755, 0xAEA42A, 0.12), COARSE_DIRT, 0.25));
		ow("savanna", land(0xBFB755, 0xAEA42A, 0.2));
		ow("savanna_plateau", land(0xBFB755, 0xAEA42A, 0.25));
		ow("desert", SAND);
		ow("badlands", mix(RED_SAND, TERRACOTTA, 0.45));
		ow("eroded_badlands", mix(RED_SAND, TERRACOTTA, 0.6));
		ow("wooded_badlands", mix(land(0x90814D, 0x9E814D, 0.35), COARSE_DIRT, 0.25));
		ow("jungle", land(0x59C93C, 0x30BB0B, 0.92));
		ow("sparse_jungle", land(0x64C73F, 0x3EB80F, 0.4));
		ow("bamboo_jungle", mix(land(0x59C93C, 0x30BB0B, 0.5), BAMBOO, 0.4));
		ow("swamp", mix(land(0x6A7039, 0x6A7039, 0.35), 0x617B64, 0.35));
		ow("mangrove_swamp", mix(land(0x4C763C, MANGROVE_LEAVES, 0.6), MUD, 0.2));
		ow("mushroom_fields", mix(MYCELIUM, RED_MUSHROOM, 0.1));
		ow("river", water(0x3F76E4, false));
		ow("frozen_river", ICE);
		ow("beach", mix(SAND, 0xFFFFFF, 0.08));
		ow("snowy_beach", mix(SNOW, SAND, 0.15));
		ow("stony_shore", STONE);
		ow("warm_ocean", water(0x43D5EE, false));
		ow("lukewarm_ocean", water(0x45ADF2, false));
		ow("deep_lukewarm_ocean", water(0x45ADF2, true));
		ow("ocean", water(0x3F76E4, false));
		ow("deep_ocean", water(0x3F76E4, true));
		ow("cold_ocean", water(0x3D57D6, false));
		ow("deep_cold_ocean", water(0x3D57D6, true));
		ow("frozen_ocean", mix(ICE, water(0x3938C9, false), 0.25));
		ow("deep_frozen_ocean", mix(ICE, water(0x3938C9, true), 0.35));
		ow("dripstone_caves", DRIPSTONE);
		ow("lush_caves", MOSS);
		ow("deep_dark", SCULK);
		ow("sulfur_caves", 0xC2B93A);
		// Nether
		other("nether_wastes", NETHERRACK, "Nether");
		other("crimson_forest", mix(CRIMSON_NYLIUM, 0x742A2A, 0.3), "Nether");
		other("warped_forest", mix(WARPED_NYLIUM, 0x167E86, 0.3), "Nether");
		other("soul_sand_valley", SOUL_SAND, "Nether");
		other("basalt_deltas", BASALT, "Nether");
		// End
		other("the_end", END_STONE, "End");
		other("end_highlands", mix(END_STONE, CHORUS, 0.15), "End");
		other("end_midlands", mix(END_STONE, 0x000000, 0.04), "End");
		other("end_barrens", mix(END_STONE, 0x000000, 0.1), "End");
		other("small_end_islands", mix(VOID, END_STONE, 0.25), "End");
		other("the_void", VOID, "Other");
	}

	private BiomeColors() {
	}

	private static void ow(String path, int color) {
		COLORS.put("minecraft:" + path, color | 0xFF000000);
		DIMENSION.put("minecraft:" + path, "Overworld");
	}

	private static void other(String path, int color, String dim) {
		COLORS.put("minecraft:" + path, color | 0xFF000000);
		DIMENSION.put("minecraft:" + path, dim);
	}

	// ---- how tints look on the blocks ----

	private static int grass(int tint) {
		return scale(tint, GRASS_SHADE);
	}

	private static int leaves(int tint) {
		return scale(tint, LEAF_SHADE);
	}

	/** Grassland with trees covering {@code treeCover} (0..1) of the ground. */
	private static int land(int grassTint, int leafTint, double treeCover) {
		return mix(grass(grassTint), leaves(leafTint), treeCover);
	}

	/** The same with leaves that keep their own color (cherry, pale oak, ...) instead of a biome tint. */
	private static int canopy(int grassTint, int leafColor, double treeCover) {
		return mix(grass(grassTint), leafColor, treeCover);
	}

	/** Water as it looks from above; deep water is darker. */
	private static int water(int waterColor, boolean deep) {
		return scale(waterColor, deep ? 0.72 : 0.88);
	}

	private static int mix(int a, int b, double t) {
		int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static int scale(int c, double f) {
		int r = (int) Math.min(255, Math.round(((c >> 16) & 255) * f));
		int g = (int) Math.min(255, Math.round(((c >> 8) & 255) * f));
		int b = (int) Math.min(255, Math.round((c & 255) * f));
		return (r << 16) | (g << 8) | b;
	}

	// ---- public ----

	public static int color(String id) {
		if (id == null) {
			return 0;
		}
		return COLORS.computeIfAbsent(id, BiomeColors::derive);
	}

	public static String dimension(String id) {
		return DIMENSION.getOrDefault(id, "Modded");
	}

	/** Known vanilla biome ids, overworld first. */
	public static List<String> vanillaIds() {
		List<String> l = new ArrayList<>();
		for (Map.Entry<String, String> e : DIMENSION.entrySet()) {
			l.add(e.getKey());
		}
		return Collections.unmodifiableList(l);
	}

	public static boolean isOcean(String id) {
		return id != null && (id.endsWith("ocean") || id.endsWith("river"));
	}

	/**
	 * A biome's name as the game shows it: its translation when the game or the mod has one
	 * ("biomesoplenty:lavender_field" is "Lavender Field"), otherwise its id without the mod's name.
	 */
	public static String pretty(String id) {
		if (id == null) {
			return "(none)";
		}
		int colon = id.indexOf(':');
		String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
		String key = "biome." + namespace + "." + id.substring(colon + 1).replace('/', '.');
		try {
			// Without a translation the game gives the key back.
			String name = net.minecraft.client.resources.language.I18n.get(key);
			if (name != null && !name.equals(key)) {
				return name;
			}
		} catch (RuntimeException | LinkageError e) {
			// No game language loaded (tools, tests): the id is used.
		}
		return words(id);
	}

	/** An id as words, without the mod's name: "minecraft:village_plains" is "Village Plains". */
	public static String words(String id) {
		if (id == null) {
			return "(none)";
		}
		String path = id.substring(id.indexOf(':') + 1);
		StringBuilder sb = new StringBuilder();
		boolean up = true;
		for (char c : path.toCharArray()) {
			if (c == '_' || c == '/') {
				sb.append(' ');
				up = true;
			} else {
				sb.append(up ? Character.toUpperCase(c) : c);
				up = false;
			}
		}
		return sb.toString();
	}

	// ---- biomes this table does not know ----

	/**
	 * Works out a color from the biome's own data file ({@code data/<namespace>/worldgen/biome/<name>.json},
	 * in the game or the mod that adds it). Falls back to a stable color made from the id.
	 */
	private static int derive(String id) {
		try {
			BiomeData data = BiomeData.load(id);
			if (data != null) {
				return 0xFF000000 | data.color(id);
			}
		} catch (RuntimeException ignored) {
			// Unreadable data: use the fallback below.
		}
		int h = id.hashCode();
		int r = 64 + ((h >> 16) & 0x7F);
		int g = 64 + ((h >> 8) & 0x7F);
		int b = 64 + (h & 0x7F);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** The parts of a biome's data file that decide how it looks. */
	record BiomeData(double temperature, double downfall, Integer grassColor, Integer foliageColor, Integer waterColor,
					 String grassModifier) {
		// Corners of the game's grass and foliage color maps (hot and wet, hot and dry, cold).
		private static final int[] GRASS_MAP = {0x47CD33, 0xBFB755, 0x80B497};
		private static final int[] FOLIAGE_MAP = {0x1ABF00, 0xAEA42A, 0x60A17B};

		static BiomeData load(String id) {
			int colon = id.indexOf(':');
			String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
			String path = colon < 0 ? id : id.substring(colon + 1);
			String resource = "data/" + ns + "/worldgen/biome/" + path + ".json";
			ClassLoader loader = BiomeColors.class.getClassLoader();
			try (InputStream in = loader == null ? null : loader.getResourceAsStream(resource)) {
				if (in == null) {
					return null;
				}
				JsonElement root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
				return fromJson(root);
			} catch (java.io.IOException e) {
				return null;
			}
		}

		static BiomeData fromJson(JsonElement root) {
			Double temperature = number(root, "temperature");
			Double downfall = number(root, "downfall");
			return new BiomeData(temperature == null ? 0.8 : temperature, downfall == null ? 0.4 : downfall,
					color(root, "grass_color"), color(root, "foliage_color"), color(root, "water_color"),
					string(root, "grass_color_modifier"));
		}

		/** How the biome looks from above, guessed from its name and its colors. */
		int color(String id) {
			String name = id.substring(id.indexOf(':') + 1).toLowerCase();
			int grass = grassTint();
			int foliage = foliageColor != null ? foliageColor : colorMap(FOLIAGE_MAP);
			int water = waterColor != null ? waterColor : 0x3F76E4;
			if (has(name, "ocean", "sea", "river", "lake", "reef", "lagoon")) {
				boolean frozen = has(name, "frozen", "ice", "icy");
				int w = BiomeColors.water(water, name.contains("deep"));
				return frozen ? mix(ICE, w, 0.3) : w;
			}
			if (has(name, "nether", "crimson", "warped", "soul", "basalt", "ash", "inferno")) {
				return has(name, "warped") ? WARPED_NYLIUM : has(name, "crimson") ? CRIMSON_NYLIUM
						: has(name, "soul") ? SOUL_SAND : has(name, "basalt", "ash") ? BASALT : NETHERRACK;
			}
			if (has(name, "end_", "chorus", "void") || name.startsWith("end")) {
				return has(name, "void") ? VOID : END_STONE;
			}
			if (has(name, "glacier", "frozen", "snow", "ice", "tundra", "arctic", "alpine") || temperature < 0.05) {
				return mix(SNOW, leaves(foliage), has(name, "forest", "taiga", "wood", "grove") ? 0.35 : 0.1);
			}
			if (has(name, "badland", "mesa", "canyon")) {
				return mix(RED_SAND, TERRACOTTA, 0.45);
			}
			if (has(name, "desert", "dune", "sand", "beach", "shore")) {
				return has(name, "red") ? RED_SAND : SAND;
			}
			if (has(name, "peak", "mountain", "cliff", "crag", "rock", "stone")) {
				return mix(STONE, grass(grass), 0.3);
			}
			if (has(name, "mushroom", "fungal", "mycel")) {
				return MYCELIUM;
			}
			if (has(name, "cave", "cavern", "depths", "underground")) {
				return STONE;
			}
			if (has(name, "swamp", "marsh", "bog", "fen", "bayou", "wetland")) {
				return mix(land(grass, foliage, 0.35), water, 0.3);
			}
			if (has(name, "forest", "wood", "jungle", "taiga", "grove", "rainforest", "orchard", "thicket", "garden")) {
				return land(grass, foliage, 0.75);
			}
			return land(grass, foliage, 0.15);
		}

		private int grassTint() {
			int g = grassColor != null ? grassColor : colorMap(GRASS_MAP);
			if ("dark_forest".equals(grassModifier) || "minecraft:dark_forest".equals(grassModifier)) {
				return (((g & 0xFEFEFE) + 0x28340A) >> 1);
			}
			if ("swamp".equals(grassModifier) || "minecraft:swamp".equals(grassModifier)) {
				return 0x6A7039;
			}
			return g;
		}

		/** The game's color map lookup (a triangle between hot/wet, hot/dry and cold corners). */
		private int colorMap(int[] corners) {
			double t = Math.clamp(temperature, 0.0, 1.0);
			double d = Math.clamp(downfall, 0.0, 1.0) * t;
			double wet = d, dry = t - d, cold = 1 - t;
			int r = 0, g = 0, b = 0;
			double[] w = {wet, dry, cold};
			for (int i = 0; i < 3; i++) {
				r += (int) Math.round(((corners[i] >> 16) & 255) * w[i]);
				g += (int) Math.round(((corners[i] >> 8) & 255) * w[i]);
				b += (int) Math.round((corners[i] & 255) * w[i]);
			}
			return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
		}

		private static boolean has(String name, String... parts) {
			for (String p : parts) {
				if (name.contains(p)) {
					return true;
				}
			}
			return false;
		}

		// The data format moves fields around between versions, so keys are searched anywhere in the file.

		private static JsonElement find(JsonElement e, String key) {
			if (e instanceof JsonObject o) {
				for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
					String k = entry.getKey();
					if (k.equals(key) || k.endsWith("/" + key) || k.endsWith(":" + key)) {
						return entry.getValue();
					}
				}
				for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
					// Feature and spawn lists never hold these keys; skip them (they are big).
					if (entry.getKey().equals("features") || entry.getKey().equals("spawners") || entry.getKey().equals("carvers")) {
						continue;
					}
					JsonElement found = find(entry.getValue(), key);
					if (found != null) {
						return found;
					}
				}
			}
			return null;
		}

		private static Double number(JsonElement root, String key) {
			JsonElement e = find(root, key);
			return e instanceof JsonPrimitive p && p.isNumber() ? p.getAsDouble() : null;
		}

		private static String string(JsonElement root, String key) {
			JsonElement e = find(root, key);
			return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
		}

		/** A color written as a number or as "#RRGGBB". */
		private static Integer color(JsonElement root, String key) {
			JsonElement e = find(root, key);
			if (!(e instanceof JsonPrimitive p)) {
				return null;
			}
			if (p.isNumber()) {
				return p.getAsInt() & 0xFFFFFF;
			}
			if (p.isString()) {
				String s = p.getAsString().trim();
				if (s.startsWith("#")) {
					s = s.substring(1);
				} else if (s.startsWith("0x") || s.startsWith("0X")) {
					s = s.substring(2);
				}
				try {
					return (int) (Long.parseLong(s, 16) & 0xFFFFFF);
				} catch (NumberFormatException ex) {
					return null;
				}
			}
			return null;
		}
	}
}
