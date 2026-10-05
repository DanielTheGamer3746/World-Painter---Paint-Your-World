package com.daniel.worldpainter.client.map;

import com.daniel.worldpainter.data.BetaBlocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Map colors and search for the blocks that can be painted as the surface (Beta 1.7.3's blocks). */
public final class SurfaceColors {
	private SurfaceColors() {
	}

	public static int color(String id) {
		BetaBlocks.Surface s = BetaBlocks.surface(id);
		return s == null ? 0xFF8A8A8A : s.color();
	}

	public static String pretty(String id) {
		BetaBlocks.Surface s = BetaBlocks.surface(id);
		return s == null ? BiomeColors.words(id) : s.name();
	}

	/** Blocks whose id or name contains the query (all of them for an empty query). */
	public static List<String> search(String query, int limit) {
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<String> out = new ArrayList<>();
		for (BetaBlocks.Surface s : BetaBlocks.SURFACES) {
			if (q.isEmpty() || s.id().contains(q) || s.name().toLowerCase(Locale.ROOT).contains(q)) {
				out.add(s.id());
				if (out.size() >= limit) {
					break;
				}
			}
		}
		return out;
	}
}
