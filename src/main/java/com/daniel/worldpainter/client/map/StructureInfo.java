package com.daniel.worldpainter.client.map;

import com.daniel.worldpainter.data.BetaStructures;
import com.daniel.worldpainter.data.PaintDimension;

import java.util.List;

/** Beta 1.7.3's structures for the structure picker (dungeons, and lakes), with a color each. */
public final class StructureInfo {
	private StructureInfo() {
	}

	public static List<String> vanillaIds() {
		return BetaStructures.ids();
	}

	public static int color(String id) {
		BetaStructures.Entry e = BetaStructures.get(id);
		return e == null ? 0xFFB0B0B8 : e.color();
	}

	public static String pretty(String id) {
		BetaStructures.Entry e = BetaStructures.get(id);
		return e != null ? e.name() : BiomeColors.words(id);
	}

	public static String about(String id) {
		BetaStructures.Entry e = BetaStructures.get(id);
		return e == null ? "" : e.about();
	}

	/** The dimension a structure belongs to (Beta's are all in the Overworld). */
	public static PaintDimension dimension(String id) {
		return PaintDimension.OVERWORLD;
	}
}
