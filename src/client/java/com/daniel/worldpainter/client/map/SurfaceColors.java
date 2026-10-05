package com.daniel.worldpainter.client.map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Map colors for surface blocks, taken from the block's own map color. */
public final class SurfaceColors {
	private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();

	/** Blocks offered first in the surface palette. Any block can be searched for. */
	public static final List<String> COMMON = List.of(
			"minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:podzol", "minecraft:mycelium",
			"minecraft:rooted_dirt", "minecraft:mud", "minecraft:moss_block", "minecraft:pale_moss_block",
			"minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:clay", "minecraft:stone",
			"minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:andesite", "minecraft:diorite",
			"minecraft:granite", "minecraft:tuff", "minecraft:calcite", "minecraft:deepslate", "minecraft:sandstone",
			"minecraft:red_sandstone", "minecraft:terracotta", "minecraft:snow_block", "minecraft:powder_snow",
			"minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:obsidian", "minecraft:magma_block",
			"minecraft:netherrack", "minecraft:soul_sand", "minecraft:end_stone", "minecraft:dirt_path",
			"minecraft:stone_bricks", "minecraft:oak_planks", "minecraft:white_concrete", "minecraft:black_concrete");

	private SurfaceColors() {
	}

	public static int color(String id) {
		if (id == null) {
			return 0;
		}
		return CACHE.computeIfAbsent(id, SurfaceColors::lookup);
	}

	private static int lookup(String id) {
		Identifier rl = Identifier.tryParse(id);
		if (rl != null) {
			Block block = BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
			if (block != null) {
				int col = block.defaultMapColor().col;
				if (col != 0) {
					return 0xFF000000 | col;
				}
			}
		}
		int h = id.hashCode();
		return 0xFF000000 | (h & 0x7F7F7F) | 0x404040;
	}

	public static boolean exists(String id) {
		Identifier rl = Identifier.tryParse(id);
		return rl != null && BuiltInRegistries.BLOCK.getOptional(rl).isPresent();
	}

	/** All block ids containing {@code query}, common blocks first. */
	public static List<String> search(String query, int max) {
		String q = query.trim().toLowerCase();
		List<String> out = new ArrayList<>();
		for (String id : COMMON) {
			if (q.isEmpty() || id.contains(q)) {
				out.add(id);
			}
		}
		if (!q.isEmpty()) {
			for (Identifier rl : BuiltInRegistries.BLOCK.keySet()) {
				String id = rl.toString();
				if (id.contains(q) && !out.contains(id)) {
					out.add(id);
					if (out.size() >= max) {
						break;
					}
				}
			}
		}
		return out.size() > max ? out.subList(0, max) : out;
	}
}
