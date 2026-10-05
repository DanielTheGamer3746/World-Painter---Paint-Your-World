package com.daniel.worldpainter.client.existing;

import com.daniel.worldpainter.data.BetaBiomes;
import com.daniel.worldpainter.data.BetaBlocks;

/**
 * What the map shows of one block column of an existing chunk, the same way whatever the chunk
 * comes from (a Beta region file, a StationAPI region file, or a chunk in memory).
 */
final class ColumnScan {
	/** Block id at a height of the column being scanned. */
	interface Blocks {
		int id(int y);
	}

	/** First block seen from above (water included), or -1 if the column is empty. */
	int surface;
	/** First block that is not water, lava, ice or snow. */
	int ground;
	/** Map color of the first block that is not a fluid (grass and leaves in their biome's color). */
	int color;

	/** Scans down from {@code fromY}. */
	boolean scan(Blocks blocks, int fromY, String biome) {
		int top = -1, ground = -1, seen = -1;
		for (int y = Math.min(127, fromY); y >= 0; y--) {
			int b = blocks.id(y);
			if (BetaBlocks.seeThrough(b)) {
				continue;
			}
			if (top < 0) {
				top = y;
			}
			if (seen < 0 && !BetaBlocks.isFluid(b)) {
				seen = b;
			}
			if (!BetaBlocks.isFluid(b) && b != BetaBlocks.ICE && b != BetaBlocks.SNOW_LAYER) {
				ground = y;
				break;
			}
		}
		if (top < 0) {
			return false;
		}
		surface = top;
		this.ground = ground < 0 ? top : ground;
		int c = BetaBlocks.color(Math.max(0, seen));
		BetaBiomes.Entry climate = BetaBiomes.get(biome);
		if (climate != null && (seen == BetaBlocks.GRASS || seen == BetaBlocks.LEAVES)) {
			// Grass and leaves take their biome's color in Beta.
			c = mix(c, climate.color());
		}
		color = c;
		return true;
	}

	private static int mix(int a, int b) {
		int r = (((a >> 16) & 255) + ((b >> 16) & 255)) / 2;
		int g = (((a >> 8) & 255) + ((b >> 8) & 255)) / 2;
		int bl = ((a & 255) + (b & 255)) / 2;
		return 0xFF000000 | r << 16 | g << 8 | bl;
	}
}
