package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.data.PaintTile;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;

/**
 * Puts painted biomes in front of the vanilla biome source.
 *
 * <p>Structure placement is the exception: while a structure decides whether it may start somewhere
 * (and while stronghold rings are laid out) the original, unpainted biomes are used. That way painting
 * a biome never removes a structure that would be there (a village under painted forest stays) and
 * never adds one that would not (painting desert does not create desert temples).
 */
public final class BiomeOverride {
	private static final ThreadLocal<int[]> VANILLA_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

	private BiomeOverride() {
	}

	/**
	 * Wraps a biome source's resolver. The design is looked up on every call rather than when the
	 * resolver is made: some resolvers are made once, before the level's design is attached (or the
	 * design is replaced after the in-game painter saves), and must still see the current design.
	 */
	public static BiomeResolver wrap(Object biomeSource, BiomeResolver original) {
		if (original instanceof Painted) {
			return original;
		}
		return new Painted(biomeSource, original);
	}

	/** Start of a section in which biome lookups on this thread ignore paint. Always pair with {@link #exitVanilla()}. */
	public static void enterVanilla() {
		VANILLA_DEPTH.get()[0]++;
	}

	public static void exitVanilla() {
		int[] d = VANILLA_DEPTH.get();
		if (d[0] > 0) {
			d[0]--;
		}
	}

	private static boolean vanillaOnly() {
		return VANILLA_DEPTH.get()[0] > 0;
	}

	/**
	 * For biome sources that are not wrapped as a whole (the End's): the painted biome of a 4x4x4
	 * cell, or {@code original} where nothing is painted.
	 */
	public static Holder<Biome> paint(Object biomeSource, int quartX, int quartY, int quartZ, Holder<Biome> original) {
		GenPaint paint = PaintBindings.get(biomeSource);
		if (paint == null || vanillaOnly()) {
			return original;
		}
		Holder<Biome> painted = painted(paint, quartX, quartZ);
		if (painted == null) {
			return original;
		}
		if ((quartY << 2) < GenPaint.KEEP_CAVE_BIOMES_BELOW_Y && paint.isCaveBiome(original)) {
			return original;
		}
		return painted;
	}

	/** The painted biome at a cell, or null. Biomes are stored per 4x4x4 cell; the pixel at the cell's center is used. */
	private static Holder<Biome> painted(GenPaint paint, int quartX, int quartZ) {
		int bx = (quartX << 2) + 2;
		int bz = (quartZ << 2) + 2;
		PaintTile tile = paint.world.getForBlock(bx, bz);
		if (tile == null) {
			return null;
		}
		String id = tile.biomeAt(PaintTile.indexForBlock(bx, bz));
		return id == null ? null : paint.biome(id);
	}

	private record Painted(Object biomeSource, BiomeResolver original) implements BiomeResolver {
		@Override
		public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
			GenPaint paint = PaintBindings.get(biomeSource);
			if (paint == null || vanillaOnly()) {
				return original.getNoiseBiome(quartX, quartY, quartZ);
			}
			Holder<Biome> painted = painted(paint, quartX, quartZ);
			if (painted == null) {
				return original.getNoiseBiome(quartX, quartY, quartZ);
			}
			if ((quartY << 2) < GenPaint.KEEP_CAVE_BIOMES_BELOW_Y) {
				Holder<Biome> vanilla = original.getNoiseBiome(quartX, quartY, quartZ);
				if (paint.isCaveBiome(vanilla)) {
					return vanilla;
				}
			}
			return painted;
		}
	}
}
