package com.daniel.worldpainter.data;

public final class TileKey {
	private TileKey() {
	}

	public static long of(int tx, int tz) {
		return ((long) tx << 32) | (tz & 0xFFFFFFFFL);
	}

	public static int x(long key) {
		return (int) (key >> 32);
	}

	public static int z(long key) {
		return (int) key;
	}

	public static int tileOf(int block) {
		return block >> PaintTile.SHIFT;
	}

	public static long forBlock(int x, int z) {
		return of(x >> PaintTile.SHIFT, z >> PaintTile.SHIFT);
	}
}
