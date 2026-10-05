package com.daniel.worldpainter.client.map;

import com.daniel.worldpainter.data.PaintDimension;

import java.util.ArrayList;
import java.util.List;

/** Structure ids (Minecraft 26.3) for the structure picker, and a color per kind of structure. */
public final class StructureInfo {
	public static final List<String> VANILLA = List.of(
			"village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga",
			"pillager_outpost", "mansion", "desert_pyramid", "jungle_pyramid", "swamp_hut", "igloo",
			"stronghold", "mineshaft", "mineshaft_mesa", "ancient_city", "trial_chambers", "trail_ruins",
			"monument", "ocean_ruin_cold", "ocean_ruin_warm", "shipwreck", "shipwreck_beached", "buried_treasure",
			"ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_mountain",
			"ruined_portal_ocean", "ruined_portal_swamp", "ruined_portal_nether",
			"abandoned_camp_forest", "abandoned_camp_birch_forest", "abandoned_camp_old_growth_birch_forest",
			"abandoned_camp_dappled_forest", "abandoned_camp_flower_forest", "abandoned_camp_cherry_grove",
			"abandoned_camp_meadow", "abandoned_camp_pale_garden", "abandoned_camp_savanna", "abandoned_camp_swamp",
			"abandoned_camp_taiga", "abandoned_camp_snowy_taiga", "abandoned_camp_old_growth_pine_taiga",
			"abandoned_camp_old_growth_spruce_taiga", "abandoned_camp_windswept_forest", "abandoned_camp_wooded_badlands",
			"abandoned_camp_bamboo_jungle", "abandoned_camp_sparse_jungle",
			"fortress", "bastion_remnant", "nether_fossil", "end_city");

	private StructureInfo() {
	}

	public static List<String> vanillaIds() {
		List<String> out = new ArrayList<>();
		for (String s : VANILLA) {
			out.add("minecraft:" + s);
		}
		return out;
	}

	public static int color(String id) {
		String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		if (p.startsWith("village") || p.startsWith("abandoned_camp")) {
			return 0xFF5CCB5F;
		}
		if (p.startsWith("ruined_portal") || p.equals("fortress") || p.equals("bastion_remnant") || p.equals("nether_fossil")) {
			return 0xFFE0503A;
		}
		if (p.startsWith("ocean") || p.startsWith("shipwreck") || p.equals("monument") || p.equals("buried_treasure")) {
			return 0xFF4FA8E8;
		}
		if (p.contains("pyramid") || p.equals("swamp_hut") || p.equals("igloo") || p.equals("mansion") || p.equals("pillager_outpost")) {
			return 0xFFE8C04F;
		}
		if (p.equals("end_city")) {
			return 0xFFC08CF0;
		}
		return 0xFFB0B0B8;
	}

	/** "Village Plains" style name, without the mod's name for structures from mods. */
	public static String pretty(String id) {
		return BiomeColors.words(id);
	}

	/** The dimension a structure belongs to. Others can be placed, but only generate properly in their own dimension. */
	public static PaintDimension dimension(String id) {
		String p = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		if (p.equals("fortress") || p.equals("bastion_remnant") || p.equals("nether_fossil") || p.equals("ruined_portal_nether")) {
			return PaintDimension.NETHER;
		}
		if (p.equals("end_city")) {
			return PaintDimension.END;
		}
		return PaintDimension.OVERWORLD;
	}
}
