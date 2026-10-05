package com.daniel.worldpainter.client.edit;

import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.PaintWorld;

/** Low-level helpers for changing many pixels efficiently, one tile at a time. */
public final class Ops {
	private Ops() {
	}

	@FunctionalInterface
	public interface PixelOp {
		/** @param i pixel index in the tile, x/z the block position */
		void apply(PaintTile tile, int i, int x, int z);
	}

	@FunctionalInterface
	public interface TileOp {
		/** Called per tile with the part of the rectangle inside that tile (inclusive block bounds). */
		void apply(PaintTile tile, int x0, int z0, int x1, int z1);
	}

	/** Runs {@code op} for each tile overlapping the rectangle (inclusive), clamped to the world. */
	public static void forTiles(EditSession session, int minX, int minZ, int maxX, int maxZ, TileOp op) {
		int x0 = Math.max(minX, -PaintWorld.WORLD_LIMIT);
		int z0 = Math.max(minZ, -PaintWorld.WORLD_LIMIT);
		int x1 = Math.min(maxX, PaintWorld.WORLD_LIMIT - 1);
		int z1 = Math.min(maxZ, PaintWorld.WORLD_LIMIT - 1);
		if (x0 > x1 || z0 > z1) {
			return;
		}
		int tx0 = x0 >> PaintTile.SHIFT, tx1 = x1 >> PaintTile.SHIFT;
		int tz0 = z0 >> PaintTile.SHIFT, tz1 = z1 >> PaintTile.SHIFT;
		for (int tz = tz0; tz <= tz1; tz++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				PaintTile tile = session.write(tx, tz);
				int bx = tx << PaintTile.SHIFT, bz = tz << PaintTile.SHIFT;
				op.apply(tile, Math.max(x0, bx), Math.max(z0, bz), Math.min(x1, bx + PaintTile.MASK), Math.min(z1, bz + PaintTile.MASK));
			}
		}
		session.changed(x0, z0, x1, z1);
	}

	/** Runs {@code op} for every pixel in the rectangle (inclusive). */
	public static void forPixels(EditSession session, int minX, int minZ, int maxX, int maxZ, PixelOp op) {
		forTiles(session, minX, minZ, maxX, maxZ, (tile, x0, z0, x1, z1) -> {
			for (int z = z0; z <= z1; z++) {
				int row = (z & PaintTile.MASK) << PaintTile.SHIFT;
				for (int x = x0; x <= x1; x++) {
					op.apply(tile, row | (x & PaintTile.MASK), x, z);
				}
			}
		});
	}

	/** Fills a rectangle with one value; whole tiles are filled in one step. */
	public static void fillRect(EditSession session, int minX, int minZ, int maxX, int maxZ, PaintValue value) {
		forTiles(session, minX, minZ, maxX, maxZ, (tile, x0, z0, x1, z1) -> {
			if (x1 - x0 == PaintTile.MASK && z1 - z0 == PaintTile.MASK) {
				value.fillTile(tile);
				return;
			}
			for (int z = z0; z <= z1; z++) {
				int row = (z & PaintTile.MASK) << PaintTile.SHIFT;
				for (int x = x0; x <= x1; x++) {
					value.write(tile, row | (x & PaintTile.MASK));
				}
			}
		});
	}

	/** Clears one layer in a rectangle. */
	public static void clearRect(EditSession session, int minX, int minZ, int maxX, int maxZ, com.daniel.worldpainter.data.Layer layer) {
		forTiles(session, minX, minZ, maxX, maxZ, (tile, x0, z0, x1, z1) -> {
			if (x1 - x0 == PaintTile.MASK && z1 - z0 == PaintTile.MASK) {
				tile.layer(layer).fill(layer.none());
				return;
			}
			for (int z = z0; z <= z1; z++) {
				int row = (z & PaintTile.MASK) << PaintTile.SHIFT;
				for (int x = x0; x <= x1; x++) {
					tile.layer(layer).set(row | (x & PaintTile.MASK), layer.none());
				}
			}
		});
	}
}
