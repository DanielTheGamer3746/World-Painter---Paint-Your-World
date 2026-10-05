package com.daniel.worldpainter.client.preview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tiles the 3D preview currently shows: everything within the view distance of the camera's
 * pivot. Tiles are (re)built from a {@link Source} when their data changes, nearest first and within a
 * time budget per frame, so painting while the preview is open stays smooth.
 */
public final class PreviewScene {
	/** Where the preview gets its data from (the painted design, the existing world, ...). */
	public interface Source {
		/** Changes whenever the data behind a tile changes. */
		long version(int tx, int tz);

		/** Builds the tile from the current data. */
		SceneTile build(int tx, int tz, long version);

		/** Sky and rock colors of the dimension. */
		default Environment environment() {
			return Environment.OVERWORLD;
		}
	}

	/** Colors that depend on the dimension: the sky (and fog) and the rock inside the ground. */
	public record Environment(int skyTop, int skyHorizon, int rock, int deepRock) {
		public static final Environment OVERWORLD = new Environment(0xFF6D9EE0, 0xFFB9D3EE, 0xFF7F7F84, 0xFF4E4E58);
		public static final Environment NETHER = new Environment(0xFF1C0606, 0xFF4A1612, 0xFF6F3634, 0xFF5A2A28);
		public static final Environment END = new Environment(0xFF07050B, 0xFF1D1626, 0xFFD6D89C, 0xFFD6D89C);
	}

	/** An immutable view of the scene for one frame (safe to use from render threads). */
	public record Snapshot(SceneTile[] grid, int gridX0, int gridZ0, int gridSize, int top,
						   double centerX, double centerZ, double radius, Environment env) {
		public SceneTile tile(int tx, int tz) {
			int ix = tx - gridX0, iz = tz - gridZ0;
			if (ix < 0 || iz < 0 || ix >= gridSize || iz >= gridSize) {
				return null;
			}
			return grid[iz * gridSize + ix];
		}

		/** Highest solid top (exclusive) at a block column, or {@link SceneTile#NONE}. */
		public int solidTop(int x, int z) {
			SceneTile t = tile(x >> SceneTile.SHIFT, z >> SceneTile.SHIFT);
			return t == null ? SceneTile.NONE : t.solidTop(SceneTile.index(x & SceneTile.MASK, z & SceneTile.MASK));
		}

		/** Highest top (exclusive) of anything, water included, or {@link SceneTile#NONE}. */
		public int columnTop(int x, int z) {
			SceneTile t = tile(x >> SceneTile.SHIFT, z >> SceneTile.SHIFT);
			return t == null ? SceneTile.NONE : t.columnTop(SceneTile.index(x & SceneTile.MASK, z & SceneTile.MASK));
		}
	}

	private final Map<Long, SceneTile> tiles = new HashMap<>();
	private Snapshot snapshot;
	private boolean pending;

	private static long key(int tx, int tz) {
		return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
	}

	/**
	 * Brings the tiles around ({@code centerX, centerZ}) up to date. Builds the nearest out-of-date
	 * tiles first, at least one, until {@code budgetNanos} is used up.
	 *
	 * @return true if the scene changed (the image must be drawn again)
	 */
	public boolean update(Source source, double centerX, double centerZ, double radius, long budgetNanos) {
		long start = System.nanoTime();
		int tx0 = (int) Math.floor((centerX - radius) / SceneTile.SIZE);
		int tz0 = (int) Math.floor((centerZ - radius) / SceneTile.SIZE);
		int tx1 = (int) Math.floor((centerX + radius) / SceneTile.SIZE);
		int tz1 = (int) Math.floor((centerZ + radius) / SceneTile.SIZE);
		int size = Math.max(tx1 - tx0, tz1 - tz0) + 1;

		record Need(int tx, int tz, long version, double dist) {
		}
		List<Need> stale = new ArrayList<>();
		Set<Long> needed = new HashSet<>();
		for (int tz = tz0; tz < tz0 + size; tz++) {
			for (int tx = tx0; tx < tx0 + size; tx++) {
				long k = key(tx, tz);
				needed.add(k);
				long version = source.version(tx, tz);
				SceneTile t = tiles.get(k);
				if (t == null || t.version != version) {
					double mx = (tx + 0.5) * SceneTile.SIZE - centerX, mz = (tz + 0.5) * SceneTile.SIZE - centerZ;
					stale.add(new Need(tx, tz, version, mx * mx + mz * mz));
				}
			}
		}
		stale.sort((a, b) -> Double.compare(a.dist, b.dist));
		boolean changed = false;
		int built = 0;
		for (Need n : stale) {
			if (built > 0 && System.nanoTime() - start > budgetNanos) {
				break;
			}
			tiles.put(key(n.tx, n.tz), source.build(n.tx, n.tz, n.version));
			built++;
			changed = true;
		}
		pending = built < stale.size();

		if (tiles.size() > needed.size() + 8) {
			tiles.keySet().removeIf(k -> !needed.contains(k));
		}

		Snapshot old = snapshot;
		if (changed || old == null || old.gridX0 != tx0 || old.gridZ0 != tz0 || old.gridSize != size
				|| old.centerX != centerX || old.centerZ != centerZ || old.radius != radius) {
			SceneTile[] grid = new SceneTile[size * size];
			int top = SceneTile.NONE;
			for (int iz = 0; iz < size; iz++) {
				for (int ix = 0; ix < size; ix++) {
					SceneTile t = tiles.get(key(tx0 + ix, tz0 + iz));
					grid[iz * size + ix] = t;
					if (t != null && t.tileTop > top) {
						top = t.tileTop;
					}
				}
			}
			snapshot = new Snapshot(grid, tx0, tz0, size, top, centerX, centerZ, radius, source.environment());
			changed = true;
		}
		return changed;
	}

	/** True while some tiles still wait to be built or rebuilt. */
	public boolean pending() {
		return pending;
	}

	public Snapshot snapshot() {
		return snapshot;
	}

	/** Forgets every tile (they are rebuilt on the next update). */
	public void clear() {
		tiles.clear();
		snapshot = null;
	}
}
