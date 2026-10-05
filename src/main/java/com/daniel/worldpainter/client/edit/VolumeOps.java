package com.daniel.worldpainter.client.edit;

import com.daniel.worldpainter.client.templates.Noise;
import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.VolumeLayer;

/**
 * Changes to the 3D part of the design: sculpting and carving with a ball-shaped brush, restoring
 * the 2D design, and floating islands. Edits are only stored where they differ from the 2D terrain
 * (adding ground where there is air, carving where there is ground), so they stay small.
 */
public final class VolumeOps {
	public enum Action {
		/** Make the blocks solid ground. */
		ADD,
		/** Make the blocks air (water and lava are left alone). */
		CARVE,
		/** Remove 3D edits: back to what the 2D design makes there. */
		RESTORE
	}

	private VolumeOps() {
	}

	/**
	 * Applies an action inside a ball. Soft brushes (hardness below 1) leave a ragged, natural edge.
	 *
	 * @return the number of blocks inside the ball
	 */
	public static long ball(PainterState s, double cx, double cy, double cz, double radius, float hardness, Action action, int salt) {
		double r = Math.max(0.5, radius);
		int minX = (int) Math.floor(cx - r), maxX = (int) Math.floor(cx + r);
		int minZ = (int) Math.floor(cz - r), maxZ = (int) Math.floor(cz + r);
		int minY = Math.max(VolumeLayer.MIN_Y, (int) Math.floor(cy - r));
		int maxY = Math.min(VolumeLayer.MAX_Y, (int) Math.floor(cy + r));
		if (minY > maxY) {
			return 0;
		}
		double r2 = r * r;
		long[] count = new long[1];
		Ops.forTiles(s.session, minX, minZ, maxX, maxZ, (tile, x0, z0, x1, z1) -> {
			for (int z = z0; z <= z1; z++) {
				double ddz = z + 0.5 - cz;
				for (int x = x0; x <= x1; x++) {
					double ddx = x + 0.5 - cx;
					double h2 = ddx * ddx + ddz * ddz;
					if (h2 > r2) {
						continue;
					}
					int ground = groundAt(s, tile, x, z);
					for (int y = minY; y <= maxY; y++) {
						double ddy = y + 0.5 - cy;
						double d2 = h2 + ddy * ddy;
						if (d2 > r2) {
							continue;
						}
						double d = Math.sqrt(d2) / r;
						if (d > hardness && hardness < 1f) {
							double u = (d - hardness) / (1 - hardness);
							if (hash01(x, y, z, salt) < u) {
								continue;
							}
						}
						count[0]++;
						set(tile, x, y, z, ground, action);
					}
				}
			}
		});
		return count[0];
	}

	/**
	 * Builds a floating island: a flat, slightly hilly top at {@code topY} and a rough, cone-shaped
	 * underside, about {@code size} blocks across.
	 */
	public static void island(PainterState s, int cx, int topY, int cz, int size, long seed) {
		double r = Math.max(4, size / 2.0);
		Noise noise = new Noise(seed);
		double depthScale = r * 0.85 + 6;
		int reach = (int) Math.ceil(r * 1.3);
		Ops.forTiles(s.session, cx - reach, cz - reach, cx + reach, cz + reach, (tile, x0, z0, x1, z1) -> {
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
					double angle = Math.atan2(dz, dx);
					// Wobbly outline
					double edge = r * (0.82 + 0.28 * noise.fbm(Math.cos(angle) * 1.7 + 11.3, Math.sin(angle) * 1.7 - 4.1, 3));
					double d = Math.sqrt(dx * dx + dz * dz) / edge;
					if (d >= 1) {
						continue;
					}
					double hill = noise.fbm(x * 0.045, z * 0.045, 3);
					int top = topY + (int) Math.round((1 - d * d) * (2 + hill * 4));
					double under = Math.pow(1 - d, 1.25) * depthScale * (0.75 + 0.4 * (noise.fbm(x * 0.09 + 50, z * 0.09 - 50, 3) + 1) / 2);
					int bottom = topY - (int) Math.round(Math.max(1, under));
					int ground = groundAt(s, tile, x, z);
					for (int y = Math.max(bottom, VolumeLayer.MIN_Y); y <= Math.min(top, VolumeLayer.MAX_Y); y++) {
						set(tile, x, y, z, ground, Action.ADD);
					}
				}
			}
		});
	}

	/** Removes every 3D edit in a rectangle (inclusive bounds). */
	public static void clearRect(EditSession session, int minX, int minZ, int maxX, int maxZ) {
		Ops.forTiles(session, minX, minZ, maxX, maxZ, (tile, x0, z0, x1, z1) -> {
			if (tile.volume.isEmpty()) {
				return;
			}
			if (x1 - x0 == PaintTile.MASK && z1 - z0 == PaintTile.MASK) {
				tile.volume.clear();
				return;
			}
			for (int z = z0; z <= z1; z++) {
				for (int x = x0; x <= x1; x++) {
					tile.volume.clearColumn(x & PaintTile.MASK, z & PaintTile.MASK);
				}
			}
		});
	}

	/** Highest solid block of a column with the 3D edits applied (as the preview and generation see it). */
	public static int topSolid(PainterState s, int x, int z) {
		PaintTile t = s.tileAt(x, z);
		int ground = s.effectiveHeight(x, z);
		if (t == null || t.volume.isEmpty()) {
			return ground;
		}
		int lx = x & PaintTile.MASK, lz = z & PaintTile.MASK;
		if (!t.volume.columnHasEdits(lx, lz)) {
			return ground;
		}
		int top = Math.max(ground, t.volume.highest(lx, lz, VolumeLayer.SOLID));
		while (top >= VolumeLayer.MIN_Y && t.volume.get(lx, top, lz) == VolumeLayer.AIR) {
			top--;
		}
		return top;
	}

	/** Sculpted (+) and carved (-) blocks in a column: {added, carved}. */
	public static int[] columnCounts(PainterState s, int x, int z) {
		PaintTile t = s.tileAt(x, z);
		if (t == null || !t.volume.columnHasEdits(x & PaintTile.MASK, z & PaintTile.MASK)) {
			return null;
		}
		byte[] col = new byte[VolumeLayer.HEIGHT];
		t.volume.column(x & PaintTile.MASK, z & PaintTile.MASK, col);
		int added = 0, carved = 0;
		for (byte b : col) {
			if (b == VolumeLayer.SOLID) {
				added++;
			} else if (b == VolumeLayer.AIR) {
				carved++;
			}
		}
		return new int[]{added, carved};
	}

	/** No reliable ground height (new land Minecraft has not generated yet, the Nether, the End's void). */
	private static final int UNKNOWN_GROUND = Integer.MIN_VALUE;

	/** Ground height of the 2D design in a column of a tile that is being written, if it is known. */
	private static int groundAt(PainterState s, PaintTile tile, int x, int z) {
		short h = tile.height.get(PaintTile.indexForBlock(x, z));
		if (h != PaintTile.NO_HEIGHT && s.dimension.allows(com.daniel.worldpainter.data.Layer.HEIGHT)) {
			return h;
		}
		return s.knownHeight(x, z);
	}

	/**
	 * Where the ground is known, stores only what differs from it (ground up to {@code ground}, air
	 * above). Where it is not known, stores the edit as it is, so it applies to whatever generates there.
	 */
	private static void set(PaintTile tile, int x, int y, int z, int ground, Action action) {
		byte value;
		if (ground == UNKNOWN_GROUND) {
			value = switch (action) {
				case ADD -> VolumeLayer.SOLID;
				case CARVE -> VolumeLayer.AIR;
				case RESTORE -> VolumeLayer.KEEP;
			};
		} else {
			boolean solidBelow = y <= ground;
			value = switch (action) {
				case ADD -> solidBelow ? VolumeLayer.KEEP : VolumeLayer.SOLID;
				case CARVE -> solidBelow ? VolumeLayer.AIR : VolumeLayer.KEEP;
				case RESTORE -> VolumeLayer.KEEP;
			};
		}
		tile.volume.set(x & PaintTile.MASK, y, z & PaintTile.MASK, value);
	}

	private static double hash01(int x, int y, int z, int salt) {
		long h = x * 0x9E3779B97F4A7C15L ^ y * 0x632BE59BD9B4E019L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return (h >>> 11) * 0x1.0p-53;
	}
}
