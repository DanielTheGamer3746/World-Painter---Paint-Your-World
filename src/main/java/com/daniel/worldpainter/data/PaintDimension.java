package com.daniel.worldpainter.data;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

/**
 * The dimensions World Painter can paint. Every dimension has its own design (its own tiles,
 * structure plan and regeneration list), and only the layers that make sense there:
 * the Nether has a bedrock roof, so "height", "surface block" and "water" mean nothing there.
 */
public enum PaintDimension {
	OVERWORLD("Overworld", "minecraft", "overworld", "minecraft:plains", 64, 63,
			EnumSet.allOf(Layer.class), true, false),
	NETHER("Nether", "minecraft", "the_nether", "minecraft:crimson_forest", 32, 32,
			EnumSet.of(Layer.BIOME), false, false),
	END("End", "minecraft", "the_end", "minecraft:end_highlands", 56, 0,
			EnumSet.of(Layer.BIOME, Layer.SURFACE), true, true);

	public final String displayName;
	public final String namespace;
	public final String path;
	/** Biome picked when the painter opens in this dimension. */
	public final String defaultBiome;
	/** Where the 3D view and the sculpt tools assume the ground is when nothing else is known. */
	public final int baseHeight;
	public final int seaLevel;
	private final Set<Layer> layers;
	/** Existing worlds' saved ground heights are meaningful (not the case under the Nether's roof). */
	public final boolean existingHeights;
	/** Land that is neither painted nor generated yet is empty space (the End is mostly void). */
	public final boolean voidByDefault;

	PaintDimension(String displayName, String namespace, String path, String defaultBiome, int baseHeight, int seaLevel,
				   Set<Layer> layers, boolean existingHeights, boolean voidByDefault) {
		this.displayName = displayName;
		this.namespace = namespace;
		this.path = path;
		this.defaultBiome = defaultBiome;
		this.baseHeight = baseHeight;
		this.seaLevel = seaLevel;
		this.layers = layers;
		this.existingHeights = existingHeights;
		this.voidByDefault = voidByDefault;
	}

	public String id() {
		return namespace + ":" + path;
	}

	/** Whether this layer can be painted (and is applied by world generation) in this dimension. */
	public boolean allows(Layer layer) {
		return layers.contains(layer);
	}

	/**
	 * The folder with this dimension's design inside a world's (or draft's) paint folder. The
	 * Overworld uses the paint folder itself, so designs made before dimensions existed still load.
	 */
	public Path dir(Path paintRoot) {
		if (this == OVERWORLD) {
			return paintRoot;
		}
		return paintRoot.resolve("dimensions").resolve(namespace).resolve(path);
	}

	public PaintDimension next() {
		PaintDimension[] all = values();
		return all[(ordinal() + 1) % all.length];
	}

	/** The dimension with this id ("minecraft:the_nether"), or null. */
	public static PaintDimension byId(String id) {
		for (PaintDimension d : values()) {
			if (d.id().equals(id)) {
				return d;
			}
		}
		return null;
	}

	/** True if any dimension of a world (or draft) has a design. */
	public static boolean anyPaint(Path paintRoot) {
		for (PaintDimension d : values()) {
			if (PaintWorld.hasPaint(d.dir(paintRoot))) {
				return true;
			}
		}
		return false;
	}
}
