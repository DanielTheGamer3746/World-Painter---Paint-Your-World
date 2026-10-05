package com.daniel.worldpainter.client.existing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunks saved by StationAPI (its "flattening" saves blocks by name, in 16 block high sections
 * with a palette, like modern Minecraft) turned back into Beta block ids for the map.
 */
final class StationChunks {
	static final String SECTIONS = "stationapi:sections";
	private static final Map<String, Integer> IDS = new HashMap<>();

	static {
		String[] names = {
				"air", "stone", "grass_block", "dirt", "cobblestone", "oak_planks", "sapling", "bedrock", "flowing_water", "water",
				"flowing_lava", "lava", "sand", "gravel", "gold_ore", "iron_ore", "coal_ore", "log", "leaves", "sponge", "glass",
				"lapis_ore", "lapis_block", "dispenser", "sandstone", "note_block", "red_bed", "powered_rail", "detector_rail",
				"sticky_piston", "cobweb", "grass", "dead_bush", "piston", "piston_head", "wool", "moving_piston", "dandelion", "rose",
				"brown_mushroom", "red_mushroom", "gold_block", "iron_block", "double_slab", "slab", "bricks", "tnt", "bookshelf",
				"mossy_cobblestone", "obsidian", "torch", "fire", "spawner", "oak_stairs", "chest", "redstone_wire", "diamond_ore",
				"diamond_block", "crafting_table", "wheat", "farmland", "furnace", "furnace_lit", "oak_sign", "oak_door", "ladder",
				"rail", "cobblestone_stairs", "oak_wall_sign", "lever", "stone_pressure_plate", "iron_door", "oak_pressure_plate",
				"redstone_ore", "redstone_ore_lit", "redstone_torch", "redstone_torch_lit", "stone_button", "snow", "ice",
				"snow_block", "cactus", "clay", "sugar_cane", "jukebox", "oak_fence", "carved_pumpkin", "netherrack", "soul_sand",
				"glowstone", "nether_portal", "jack_o_lantern", "cake", "repeater", "repeater_lit", "locked_chest", "oak_trapdoor"};
		for (int i = 0; i < names.length; i++) {
			IDS.put("minecraft:" + names[i], i);
		}
	}

	private StationChunks() {
	}

	/** Beta block id for a StationAPI block name (blocks from other mods count as stone). */
	static int id(String name) {
		if (name == null) {
			return 0;
		}
		Integer id = IDS.get(name.contains(":") ? name : "minecraft:" + name);
		return id != null ? id : 1;
	}

	/**
	 * The chunk's blocks as Beta ids in Beta's layout ({@code x << 11 | z << 7 | y}), or null if the
	 * chunk has no StationAPI sections.
	 */
	static byte[] blocks(Map<String, Object> level) {
		List<Object> sections = MiniNbt.list(level, SECTIONS);
		if (sections == null) {
			return null;
		}
		byte[] out = new byte[32768];
		for (Object o : sections) {
			if (!(o instanceof Map<?, ?> raw)) {
				continue;
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> section = (Map<String, Object>) raw;
			Integer sy = MiniNbt.integer(section, "y");
			if (sy == null) {
				sy = MiniNbt.integer(section, "Y");
			}
			Map<String, Object> states = MiniNbt.compound(section, "block_states");
			if (sy == null || sy < 0 || sy > 7 || states == null) {
				continue;
			}
			List<Object> palette = MiniNbt.list(states, "palette");
			if (palette == null || palette.isEmpty()) {
				continue;
			}
			int[] paletteIds = new int[palette.size()];
			for (int i = 0; i < paletteIds.length; i++) {
				Object entry = palette.get(i);
				paletteIds[i] = entry instanceof Map<?, ?> m && m.get("Name") instanceof String n ? id(n) : 0;
			}
			long[] data = MiniNbt.longs(states, "data");
			int bits = palette.size() <= 1 || data == null ? 0 : Math.max(4, ceilLog2(palette.size()));
			int perLong = bits == 0 ? 0 : 64 / bits;
			long mask = bits == 0 ? 0 : (1L << bits) - 1;
			for (int i = 0; i < 4096; i++) {
				int p = 0;
				if (bits != 0) {
					int li = i / perLong;
					if (li < data.length) {
						p = (int) ((data[li] >>> ((i % perLong) * bits)) & mask);
					}
				}
				int block = p < paletteIds.length ? paletteIds[p] : 0;
				if (block == 0) {
					continue;
				}
				int x = i & 15, z = (i >> 4) & 15, y = (sy << 4) | (i >> 8);
				out[x << 11 | z << 7 | y] = (byte) block;
			}
		}
		return out;
	}

	private static int ceilLog2(int v) {
		return v <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(v - 1);
	}
}
