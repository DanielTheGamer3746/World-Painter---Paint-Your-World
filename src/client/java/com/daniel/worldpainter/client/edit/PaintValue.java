package com.daniel.worldpainter.client.edit;

import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintTile;

/** One value of one layer, ready to be written into tiles. */
public record PaintValue(Layer layer, String id, short height, short fluid) {
	public static PaintValue biome(String id) {
		return new PaintValue(Layer.BIOME, id, PaintTile.NO_HEIGHT, FluidType.NONE);
	}

	public static PaintValue surface(String id) {
		return new PaintValue(Layer.SURFACE, id, PaintTile.NO_HEIGHT, FluidType.NONE);
	}

	public static PaintValue height(int h) {
		return new PaintValue(Layer.HEIGHT, null, (short) Math.clamp(h, -2032, 2031), FluidType.NONE);
	}

	public static PaintValue fluid(FluidType type, int level) {
		return new PaintValue(Layer.FLUID, null, PaintTile.NO_HEIGHT, FluidType.pack(type, level));
	}

	/** Writes this value into one pixel. */
	public void write(PaintTile t, int i) {
		switch (layer) {
			case BIOME -> t.biome.set(i, t.biomePalette.indexOf(id));
			case SURFACE -> t.surface.set(i, t.surfacePalette.indexOf(id));
			case HEIGHT -> t.height.set(i, height);
			case FLUID -> t.fluid.set(i, fluid);
		}
	}

	/** Writes this value into a whole tile at once (keeps the tile tiny on disk and in memory). */
	public void fillTile(PaintTile t) {
		switch (layer) {
			case BIOME -> t.biome.fill(t.biomePalette.indexOf(id));
			case SURFACE -> t.surface.fill(t.surfacePalette.indexOf(id));
			case HEIGHT -> t.height.fill(height);
			case FLUID -> t.fluid.fill(fluid);
		}
	}

	/** True if the pixel already holds this value. */
	public boolean matches(PaintTile t, int i) {
		return switch (layer) {
			case BIOME -> id.equals(t.biomeAt(i));
			case SURFACE -> id.equals(t.surfaceAt(i));
			case HEIGHT -> t.height.get(i) == height;
			case FLUID -> t.fluid.get(i) == fluid;
		};
	}

	public static void clear(PaintTile t, int i, Layer layer) {
		t.layer(layer).set(i, layer.none());
	}
}
