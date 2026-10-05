package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.data.PaintTile;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes painted biomes straight into a chunk right after its terrain is filled, in every dimension.
 *
 * <p>The biome hooks on the biome sources are not always enough: the End has its own kind of biome
 * source, and mods like TerraBlender hand chunk generation a fresh copy of the biome source for every
 * chunk, which the design is not attached to. Writing the painted biomes here (found through the chunk
 * generator, which those mods leave alone) makes them stick whatever chose the biomes before; the
 * surface, decorations and mobs that come afterwards then follow the painted biome.
 *
 * <p>The chunk's own "fill biomes" method is found by name when first needed, so this keeps working
 * when its exact parameters change between game versions.
 */
public final class ChunkBiomes {
	private static volatile Method fill;
	private static volatile Method read;
	private static volatile boolean unavailable;
	private static volatile boolean announced;

	private ChunkBiomes() {
	}

	/** Puts the painted biomes of {@code tile} into the chunk (which lies inside that tile). */
	public static void paint(GenPaint paint, ChunkAccess chunk, PaintTile tile, int bx0, int bz0) {
		if (unavailable || tile.biome.isAll((short) 0) || !anyPainted(tile, bx0, bz0)) {
			return;
		}
		BiomeResolver resolver = (quartX, quartY, quartZ) -> {
			Holder<Biome> current = read(chunk, quartX, quartY, quartZ);
			int bx = (quartX << 2) + 2, bz = (quartZ << 2) + 2;
			String id = tile.biomeAt(PaintTile.indexForBlock(bx, bz));
			Holder<Biome> painted = id == null ? null : paint.biome(id);
			if (painted == null) {
				return current;
			}
			// Lush caves, dripstone caves, the deep dark... stay underground where they generated.
			if ((quartY << 2) < GenPaint.KEEP_CAVE_BIOMES_BELOW_Y && paint.isCaveBiome(current)) {
				return current;
			}
			return painted;
		};
		if (fill(chunk, resolver) && !announced) {
			announced = true;
			WorldPainter.LOGGER.info("Writing painted biomes into generated {} chunks", paint.dimension.displayName);
		}
	}

	/**
	 * Refills every biome cell of {@code chunk} from {@code resolver} (quart coordinates).
	 *
	 * @return false if the game's chunk class has no usable method for it
	 */
	public static boolean fill(ChunkAccess chunk, BiomeResolver resolver) {
		if (!ready(chunk)) {
			return false;
		}
		Method f = fill;
		Object[] args = new Object[f.getParameterCount()];
		args[0] = resolver;
		try {
			f.invoke(chunk, args);
			return true;
		} catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
			unavailable = true;
			WorldPainter.LOGGER.warn("World Painter could not write biomes into a chunk", e);
			return false;
		}
	}

	/** The biome a chunk currently has in a 4x4x4 cell (quart coordinates). */
	@SuppressWarnings("unchecked")
	public static Holder<Biome> read(ChunkAccess chunk, int quartX, int quartY, int quartZ) {
		if (!ready(chunk)) {
			throw new IllegalStateException("Chunk biomes cannot be read");
		}
		try {
			return (Holder<Biome>) read.invoke(chunk, quartX, quartY, quartZ);
		} catch (IllegalAccessException | InvocationTargetException e) {
			throw new IllegalStateException("Could not read a chunk biome", e);
		}
	}

	private static boolean ready(ChunkAccess chunk) {
		if (unavailable) {
			return false;
		}
		if (fill != null && read != null) {
			return true;
		}
		// Looked up on ChunkAccess itself, so the same methods work on every kind of chunk
		// (generating, loaded) and still reach their overrides.
		Method f = find(ChunkAccess.class, "fillBiomesFromNoise", true);
		Method r = find(ChunkAccess.class, "getNoiseBiome", false);
		if (f == null || r == null) {
			unavailable = true;
			WorldPainter.LOGGER.warn("World Painter cannot write biomes into chunks: no usable fillBiomesFromNoise/getNoiseBiome (found: {})",
					names(ChunkAccess.class));
			return false;
		}
		fill = f;
		read = r;
		return true;
	}

	private static boolean anyPainted(PaintTile tile, int bx0, int bz0) {
		for (int z = 2; z < 16; z += 4) {
			for (int x = 2; x < 16; x += 4) {
				if (tile.biomeAt(PaintTile.indexForBlock(bx0 + x, bz0 + z)) != null) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * fillBiomesFromNoise: first parameter a biome resolver, any others objects (passed as null).
	 * getNoiseBiome: three ints.
	 */
	private static Method find(Class<?> cls, String name, boolean filler) {
		for (Method m : cls.getMethods()) {
			if (!m.getName().equals(name)) {
				continue;
			}
			Class<?>[] p = m.getParameterTypes();
			if (filler) {
				if (p.length >= 1 && p[0] == BiomeResolver.class && noPrimitives(p)) {
					return m;
				}
			} else if (p.length == 3 && p[0] == int.class && p[1] == int.class && p[2] == int.class) {
				return m;
			}
		}
		return null;
	}

	private static boolean noPrimitives(Class<?>[] p) {
		for (int i = 1; i < p.length; i++) {
			if (p[i].isPrimitive()) {
				return false;
			}
		}
		return true;
	}

	private static List<String> names(Class<?> cls) {
		List<String> l = new ArrayList<>();
		for (Method m : cls.getMethods()) {
			if (m.getName().toLowerCase().contains("biome")) {
				l.add(m.toString());
			}
		}
		return l;
	}
}
