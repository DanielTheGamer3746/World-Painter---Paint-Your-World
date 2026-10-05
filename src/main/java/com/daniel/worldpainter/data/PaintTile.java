package com.daniel.worldpainter.data;

/**
 * A 256x256 block piece of the painted map. Every pixel is one block column.
 * Layers that are never painted cost (almost) nothing.
 */
public final class PaintTile {
	public static final int SHIFT = 8;
	public static final int SIZE = 1 << SHIFT;
	public static final int MASK = SIZE - 1;
	public static final int AREA = SIZE * SIZE;
	public static final short NO_HEIGHT = Short.MIN_VALUE;

	public final int tx;
	public final int tz;

	public final ShortLayer biome = new ShortLayer((short) 0);
	public final Palette biomePalette = new Palette();
	public final ShortLayer height = new ShortLayer(NO_HEIGHT);
	public final ShortLayer surface = new ShortLayer((short) 0);
	public final Palette surfacePalette = new Palette();
	public final ShortLayer fluid = new ShortLayer(FluidType.NONE);
	/** Blocks sculpted solid or carved in 3D (caves, overhangs, floating islands). */
	public final VolumeLayer volume = new VolumeLayer();

	/** Bumped on every change; used by the editor to know when cached colors are stale. */
	public volatile int revision;
	/** Cached average color for zoomed-out views (editor only). */
	public volatile int summaryColor;
	public volatile int summaryRevision = -1;
	/** Last time this tile was used, for unloading tiles that have not been needed for a while. */
	public volatile long lastUse;

	public PaintTile(int tx, int tz) {
		this.tx = tx;
		this.tz = tz;
	}

	public static int index(int localX, int localZ) {
		return (localZ << SHIFT) | localX;
	}

	public static int indexForBlock(int blockX, int blockZ) {
		return ((blockZ & MASK) << SHIFT) | (blockX & MASK);
	}

	public int minBlockX() {
		return tx << SHIFT;
	}

	public int minBlockZ() {
		return tz << SHIFT;
	}

	public ShortLayer layer(Layer layer) {
		return switch (layer) {
			case BIOME -> biome;
			case HEIGHT -> height;
			case SURFACE -> surface;
			case FLUID -> fluid;
		};
	}

	public String biomeAt(int i) {
		return biomePalette.get(biome.get(i));
	}

	public String surfaceAt(int i) {
		return surfacePalette.get(surface.get(i));
	}

	private volatile int biomeCheckRevision = -1;
	private volatile boolean biomeCheck;

	/** True if any biome is painted in this tile (cached until the tile changes). */
	public boolean hasBiomes() {
		int r = revision;
		if (biomeCheckRevision != r) {
			biomeCheck = !biome.isAll((short) 0);
			biomeCheckRevision = r;
		}
		return biomeCheck;
	}

	public boolean hasTerrainLayers() {
		return !height.isAll(NO_HEIGHT) || !fluid.isAll(FluidType.NONE);
	}

	public boolean hasVolume() {
		return !volume.isEmpty();
	}

	public boolean isEmpty() {
		return biome.isAll((short) 0) && height.isAll(NO_HEIGHT) && surface.isAll((short) 0) && fluid.isAll(FluidType.NONE)
				&& volume.isEmpty();
	}

	public void touch() {
		revision++;
	}

	public void compact() {
		biome.compact();
		height.compact();
		surface.compact();
		fluid.compact();
		volume.compact();
	}

	public PaintTile copy() {
		PaintTile c = new PaintTile(tx, tz);
		c.copyFrom(this);
		return c;
	}

	/** Replaces all content of this tile with the content of another (same position). */
	public void copyFrom(PaintTile o) {
		ShortLayer b = o.biome.copy();
		biome.setRaw(b.rawData(), b.uniformValue());
		biomePalette.load(o.biomePalette.snapshot());
		ShortLayer h = o.height.copy();
		height.setRaw(h.rawData(), h.uniformValue());
		ShortLayer s = o.surface.copy();
		surface.setRaw(s.rawData(), s.uniformValue());
		surfacePalette.load(o.surfacePalette.snapshot());
		ShortLayer f = o.fluid.copy();
		fluid.setRaw(f.rawData(), f.uniformValue());
		volume.setFrom(o.volume);
		touch();
	}

	/**
	 * Whether this tile and another copy of it differ anywhere in one 16x16 chunk of the tile
	 * ({@code lcx}, {@code lcz} are 0-15). Used to regenerate only the chunks an undo changes.
	 */
	public boolean differsInChunk(PaintTile o, int lcx, int lcz) {
		int x0 = lcx << 4, z0 = lcz << 4;
		for (int z = z0; z < z0 + 16; z++) {
			for (int x = x0; x < x0 + 16; x++) {
				int i = index(x, z);
				if (height.get(i) != o.height.get(i) || fluid.get(i) != o.fluid.get(i)
						|| !java.util.Objects.equals(biomeAt(i), o.biomeAt(i))
						|| !java.util.Objects.equals(surfaceAt(i), o.surfaceAt(i))) {
					return true;
				}
			}
		}
		for (int sy = 0; sy < VolumeLayer.SECTIONS_Y; sy++) {
			if (!java.util.Arrays.equals(volume.section(lcx, sy, lcz), o.volume.section(lcx, sy, lcz))) {
				return true;
			}
		}
		return false;
	}

	public void clearAll() {
		biome.fill((short) 0);
		height.fill(NO_HEIGHT);
		surface.fill((short) 0);
		fluid.fill(FluidType.NONE);
		volume.clear();
		touch();
	}

	public long memoryBytes() {
		return 256 + biome.memoryBytes() + height.memoryBytes() + surface.memoryBytes() + fluid.memoryBytes() + volume.memoryBytes();
	}
}
