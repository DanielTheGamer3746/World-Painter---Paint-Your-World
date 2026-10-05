package com.daniel.worldpainter.data;

public enum Layer {
	BIOME("Biome"),
	HEIGHT("Height"),
	SURFACE("Surface"),
	FLUID("Water/Lava");

	public final String displayName;

	Layer(String displayName) {
		this.displayName = displayName;
	}

	public short none() {
		return switch (this) {
			case BIOME, SURFACE -> 0;
			case HEIGHT -> PaintTile.NO_HEIGHT;
			case FLUID -> FluidType.NONE;
		};
	}
}
