package com.daniel.worldpainter.client.tools;

/**
 * A block pointed at in the 3D view (or the top of a map column), and the side of it that faces the
 * viewer: (nx, ny, nz) is that side's direction.
 */
public record Hit3D(int x, int y, int z, int nx, int ny, int nz) {
	/** The top face of a block. */
	public static Hit3D top(int x, int y, int z) {
		return new Hit3D(x, y, z, 0, 1, 0);
	}
}
