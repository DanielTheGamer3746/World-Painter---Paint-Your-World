package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.data.PaintDimension;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Links World Painter's dimensions to Minecraft's level keys. */
public final class Dimensions {
	private Dimensions() {
	}

	/** The paintable dimension for a level, or null for dimensions World Painter does not paint (modded ones). */
	public static PaintDimension of(ResourceKey<Level> key) {
		if (Level.OVERWORLD.equals(key)) {
			return PaintDimension.OVERWORLD;
		}
		if (Level.NETHER.equals(key)) {
			return PaintDimension.NETHER;
		}
		if (Level.END.equals(key)) {
			return PaintDimension.END;
		}
		return null;
	}

	public static ResourceKey<Level> key(PaintDimension dimension) {
		return switch (dimension) {
			case OVERWORLD -> Level.OVERWORLD;
			case NETHER -> Level.NETHER;
			case END -> Level.END;
		};
	}
}
