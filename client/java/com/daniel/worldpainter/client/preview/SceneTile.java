package com.daniel.worldpainter.client.preview;

/**
 * What the 3D preview knows about one 256x256 block area: per column the ground height, water/lava,
 * colors, and (only for columns with 3D edits) the exact list of solid and fluid runs.
 *
 * <p>A plain column is solid from the bottom of the world up to {@link #ground}, then fluid up to
 * {@link #fluidTop}. A column with 3D edits has its runs in {@link #runs} instead.
 */
public final class SceneTile {
	public static final int SHIFT = 8;
	public static final int SIZE = 1 << SHIFT;
	public static final int MASK = SIZE - 1;
	public static final int AREA = SIZE * SIZE;
	public static final int CELL_SHIFT = 4;
	public static final int CELLS = SIZE >> CELL_SHIFT;
	public static final int QUAD_SHIFT = 2;
	public static final int QUADS = SIZE >> QUAD_SHIFT;

	/** No terrain at all (outside the world). */
	public static final short NONE = Short.MIN_VALUE;
	/** Bottom of every solid run that reaches the bottom of the world. */
	public static final short BOTTOM = -2048;

	// Run types
	public static final byte SOLID = 1;
	public static final byte WATER = 2;
	public static final byte LAVA = 3;

	// Column flags
	/** Something is painted in this column. */
	public static final byte PAINTED = 1;
	/** Height is a guess (not painted and not known from an existing world). */
	public static final byte GUESS = 2;
	/** The column has 3D edits (see {@link #runs}). */
	public static final byte EDITED = 4;

	public final int tx, tz;
	/** Changes whenever the source data changes; tiles are rebuilt when it differs. */
	public final long version;

	/** Top solid block of the 2D terrain (before 3D edits), or {@link #NONE}. */
	public final short[] ground = new short[AREA];
	/** Top water/lava block, or {@link #NONE}. */
	public final short[] fluidTop = new short[AREA];
	/** {@link #WATER} or {@link #LAVA} where {@link #fluidTop} is set. */
	public final byte[] fluidKind = new byte[AREA];
	/** Color of the top face (biome / surface block). */
	public final int[] topColor = new int[AREA];
	/** Color of the few blocks under the top (dirt, sand, ...). */
	public final int[] sideColor = new int[AREA];
	public final byte[] flags = new byte[AREA];
	/**
	 * Columns with 3D edits: (bottom, top exclusive, type) triples from low to high, or null.
	 * The array itself is only allocated when some column of the tile has edits.
	 */
	public short[][] runs;
	/** Per 16x16 cell, the highest top (exclusive) of anything solid or fluid; {@link #NONE} if empty. */
	public final short[] cellTop = new short[CELLS * CELLS];
	/** The same per 4x4 quad. */
	public final short[] quadTop = new short[QUADS * QUADS];
	public short tileTop = NONE;

	public SceneTile(int tx, int tz, long version) {
		this.tx = tx;
		this.tz = tz;
		this.version = version;
	}

	public static int index(int lx, int lz) {
		return (lz << SHIFT) | lx;
	}

	/** Sets a column with 3D edits from its final list of runs (see {@link #runs}). */
	public void setRuns(int i, short[] columnRuns) {
		if (runs == null) {
			runs = new short[AREA][];
		}
		runs[i] = columnRuns;
		flags[i] |= EDITED;
	}

	/** Highest top (exclusive) of a column, or {@link #NONE} if it is empty. */
	public int columnTop(int i) {
		short[] r = runs == null ? null : runs[i];
		if (r != null) {
			return r.length == 0 ? NONE : r[r.length - 2];
		}
		short g = ground[i];
		if (g == NONE) {
			return NONE;
		}
		short f = fluidTop[i];
		return Math.max(g, f == NONE ? g : f) + 1;
	}

	/** Highest top (exclusive) of solid ground in a column, or {@link #NONE}. */
	public int solidTop(int i) {
		short[] r = runs == null ? null : runs[i];
		if (r != null) {
			for (int k = r.length - 3; k >= 0; k -= 3) {
				if (r[k + 2] == SOLID) {
					return r[k + 1];
				}
			}
			return NONE;
		}
		short g = ground[i];
		return g == NONE ? NONE : g + 1;
	}

	/** Computes {@link #quadTop}, {@link #cellTop} and {@link #tileTop}; call once after filling the columns. */
	public void finish() {
		for (int qz = 0; qz < QUADS; qz++) {
			for (int qx = 0; qx < QUADS; qx++) {
				int top = NONE;
				for (int z = 0; z < 4; z++) {
					int row = ((qz << 2) + z) << SHIFT;
					for (int x = 0; x < 4; x++) {
						int t = columnTop(row | (qx << 2) | x);
						if (t > top) {
							top = t;
						}
					}
				}
				quadTop[qz * QUADS + qx] = (short) top;
			}
		}
		short best = NONE;
		for (int cz = 0; cz < CELLS; cz++) {
			for (int cx = 0; cx < CELLS; cx++) {
				short top = NONE;
				for (int qz = 0; qz < 4; qz++) {
					for (int qx = 0; qx < 4; qx++) {
						short t = quadTop[((cz << 2) + qz) * QUADS + (cx << 2) + qx];
						if (t > top) {
							top = t;
						}
					}
				}
				cellTop[cz * CELLS + cx] = top;
				if (top > best) {
					best = top;
				}
			}
		}
		tileTop = best;
	}
}
