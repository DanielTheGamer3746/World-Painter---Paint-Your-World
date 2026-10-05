package com.daniel.worldpainter.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Blocks of Beta 1.7.3 by their numeric id (Beta has no block names in saves), the blocks that can
 * be painted as the surface, and a map color for every block.
 */
public final class BetaBlocks {
	public static final int AIR = 0;
	public static final int STONE = 1;
	public static final int GRASS = 2;
	public static final int DIRT = 3;
	public static final int COBBLESTONE = 4;
	public static final int BEDROCK = 7;
	public static final int FLOWING_WATER = 8;
	public static final int WATER = 9;
	public static final int FLOWING_LAVA = 10;
	public static final int LAVA = 11;
	public static final int SAND = 12;
	public static final int GRAVEL = 13;
	public static final int LEAVES = 18;
	public static final int MOSSY_COBBLESTONE = 48;
	public static final int SPAWNER = 52;
	public static final int CHEST = 54;
	public static final int SNOW_LAYER = 78;
	public static final int ICE = 79;
	public static final int SNOW_BLOCK = 80;

	/** A block that can be painted as the top block of the ground. */
	public record Surface(String id, String name, int block, int meta, int color) {
	}

	public static final List<Surface> SURFACES;
	private static final int[] COLORS = new int[256];

	static {
		List<Surface> l = new ArrayList<>();
		l.add(new Surface("grass", "Grass", 2, 0, 0xFF6E9E3C));
		l.add(new Surface("dirt", "Dirt", 3, 0, 0xFF866043));
		l.add(new Surface("sand", "Sand", 12, 0, 0xFFDBD3A0));
		l.add(new Surface("sandstone", "Sandstone", 24, 0, 0xFFD8CB9B));
		l.add(new Surface("gravel", "Gravel", 13, 0, 0xFF857F7E));
		l.add(new Surface("clay", "Clay", 82, 0, 0xFF9EA4B0));
		l.add(new Surface("stone", "Stone", 1, 0, 0xFF7D7D7D));
		l.add(new Surface("cobblestone", "Cobblestone", 4, 0, 0xFF7A7A7A));
		l.add(new Surface("mossy_cobblestone", "Mossy Cobblestone", 48, 0, 0xFF627A5C));
		l.add(new Surface("snow_block", "Snow", 80, 0, 0xFFF0FBFB));
		l.add(new Surface("ice", "Ice", 79, 0, 0xFF91B4FE));
		l.add(new Surface("obsidian", "Obsidian", 49, 0, 0xFF14121D));
		l.add(new Surface("bedrock", "Bedrock", 7, 0, 0xFF555555));
		l.add(new Surface("netherrack", "Netherrack", 87, 0, 0xFF6F3634));
		l.add(new Surface("soul_sand", "Soul Sand", 88, 0, 0xFF513E32));
		l.add(new Surface("glowstone", "Glowstone", 89, 0, 0xFFF9D49C));
		l.add(new Surface("planks", "Wooden Planks", 5, 0, 0xFF9C7F4E));
		l.add(new Surface("log", "Wood", 17, 0, 0xFF66512F));
		l.add(new Surface("spruce_log", "Spruce Wood", 17, 1, 0xFF2E1D0E));
		l.add(new Surface("birch_log", "Birch Wood", 17, 2, 0xFFD5CDB4));
		l.add(new Surface("leaves", "Leaves", 18, 0, 0xFF3A7A1E));
		l.add(new Surface("spruce_leaves", "Spruce Leaves", 18, 1, 0xFF3D5C3D));
		l.add(new Surface("birch_leaves", "Birch Leaves", 18, 2, 0xFF5F8A3A));
		l.add(new Surface("bricks", "Bricks", 45, 0, 0xFF966455));
		l.add(new Surface("double_slab", "Double Stone Slab", 43, 0, 0xFFA8A8A8));
		l.add(new Surface("bookshelf", "Bookshelf", 47, 0, 0xFF6B5839));
		l.add(new Surface("glass", "Glass", 20, 0, 0xFFC0F5FE));
		l.add(new Surface("sponge", "Sponge", 19, 0, 0xFFC3C24E));
		l.add(new Surface("pumpkin", "Pumpkin", 86, 0, 0xFFE38A1D));
		l.add(new Surface("jack_o_lantern", "Jack 'o' Lantern", 91, 0, 0xFFE9B416));
		l.add(new Surface("tnt", "TNT", 46, 0, 0xFFDB441A));
		l.add(new Surface("gold_block", "Gold Block", 41, 0, 0xFFF9EC4E));
		l.add(new Surface("iron_block", "Iron Block", 42, 0, 0xFFDBDBDB));
		l.add(new Surface("diamond_block", "Diamond Block", 57, 0, 0xFF62DBD6));
		l.add(new Surface("lapis_block", "Lapis Lazuli Block", 22, 0, 0xFF1D47A6));
		l.add(new Surface("coal_ore", "Coal Ore", 16, 0, 0xFF5E5E5E));
		l.add(new Surface("iron_ore", "Iron Ore", 15, 0, 0xFF88827C));
		l.add(new Surface("gold_ore", "Gold Ore", 14, 0, 0xFF8F8B6C));
		l.add(new Surface("redstone_ore", "Redstone Ore", 73, 0, 0xFF845B5B));
		l.add(new Surface("lapis_ore", "Lapis Lazuli Ore", 21, 0, 0xFF667086));
		l.add(new Surface("diamond_ore", "Diamond Ore", 56, 0, 0xFF7D9192));
		String[] wool = {"White", "Orange", "Magenta", "Light Blue", "Yellow", "Lime", "Pink", "Gray", "Light Gray", "Cyan",
				"Purple", "Blue", "Brown", "Green", "Red", "Black"};
		int[] woolColors = {0xFFE9ECEC, 0xFFEA7E35, 0xFFBE49C9, 0xFF6689D3, 0xFFC2B51C, 0xFF3BBD30, 0xFFD98199, 0xFF434343,
				0xFF9EA6A6, 0xFF277596, 0xFF8136C4, 0xFF27329A, 0xFF56331C, 0xFF364B18, 0xFF9E2B27, 0xFF181414};
		for (int i = 0; i < 16; i++) {
			l.add(new Surface(wool[i].toLowerCase(Locale.ROOT).replace(' ', '_') + "_wool", wool[i] + " Wool", 35, i, woolColors[i]));
		}
		SURFACES = Collections.unmodifiableList(l);

		java.util.Arrays.fill(COLORS, 0xFF8A8A8A);
		COLORS[AIR] = 0;
		for (Surface s : SURFACES) {
			if (s.meta() == 0) {
				COLORS[s.block()] = s.color();
			}
		}
		COLORS[FLOWING_WATER] = COLORS[WATER] = 0xFF3F76E4;
		COLORS[FLOWING_LAVA] = COLORS[LAVA] = 0xFFD8571D;
		COLORS[SNOW_LAYER] = 0xFFF0FBFB;
		COLORS[81] = 0xFF0F7A1A; // cactus
		COLORS[60] = 0xFF5E3B1D; // farmland
		COLORS[59] = 0xFFA6B83A; // wheat
		COLORS[83] = 0xFF94C065; // sugar cane
		COLORS[53] = COLORS[85] = 0xFF9C7F4E; // wooden stairs, fence
		COLORS[67] = 0xFF7A7A7A; // cobblestone stairs
		COLORS[44] = 0xFFA8A8A8; // slab
		COLORS[35] = 0xFFE9ECEC; // white wool
	}

	private BetaBlocks() {
	}

	public static Surface surface(String id) {
		if (id == null) {
			return null;
		}
		String key = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		for (Surface s : SURFACES) {
			if (s.id().equals(key)) {
				return s;
			}
		}
		return null;
	}

	/** Map color of a block id (0 for air). */
	public static int color(int block) {
		return block < 0 || block >= COLORS.length ? 0xFF8A8A8A : COLORS[block];
	}

	public static boolean isFluid(int block) {
		return block == FLOWING_WATER || block == WATER || block == FLOWING_LAVA || block == LAVA;
	}

	/**
	 * Blocks a top-down map looks through: plants, torches, rails and the like. Leaves, cactus,
	 * snow and fluids are not "see-through" (they are what you see from above).
	 */
	public static boolean seeThrough(int block) {
		switch (block) {
			case AIR, 6, 30, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59, 63, 65, 66, 68, 69, 70, 72, 75, 76, 77, 83, 90, 93, 94:
				return true;
			default:
				return false;
		}
	}
}
