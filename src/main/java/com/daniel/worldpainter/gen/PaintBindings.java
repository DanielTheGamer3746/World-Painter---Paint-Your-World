package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Links a level's chunk generator and biome source to its paint data. The mixins look the
 * generator / biome source up here; lookups are lock-free because generation is multi-threaded.
 */
public final class PaintBindings {
	private static final int SERVER_MAX_LOADED_TILES = 1024;
	private static volatile Map<Object, GenPaint> bindings = Map.of();

	private PaintBindings() {
	}

	public static GenPaint get(Object generatorOrBiomeSource) {
		Map<Object, GenPaint> m = bindings;
		return m.isEmpty() ? null : m.get(generatorOrBiomeSource);
	}

	public static synchronized void bindLevel(MinecraftServer server, ServerLevel level, Path paintDir, PaintDimension dimension) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		IdentityHashMap<Object, GenPaint> next = new IdentityHashMap<>(bindings);
		next.remove(generator);
		next.remove(generator.getBiomeSource());
		if (PaintWorld.hasPaint(paintDir)) {
			GenPaint paint = new GenPaint(PaintWorld.open(paintDir, SERVER_MAX_LOADED_TILES), server.registryAccess(), dimension);
			next.put(generator, paint);
			next.put(generator.getBiomeSource(), paint);
			WorldPainter.LOGGER.info("World Painter design active for {}", level.dimension());
		}
		bindings = next;
	}

	/**
	 * While the painter is open on the running world with live changes: this dimension generates with
	 * the painter's design as it is being painted, so every stroke counts at once (before it is saved).
	 * The design's data is safe to read from generation threads while it is painted.
	 */
	public static synchronized void useDesign(MinecraftServer server, ServerLevel level, PaintDimension dimension, PaintWorld design) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		GenPaint now = bindings.get(generator);
		if (now != null && now.world == design) {
			return;
		}
		IdentityHashMap<Object, GenPaint> next = new IdentityHashMap<>(bindings);
		GenPaint paint = new GenPaint(design, server.registryAccess(), dimension);
		next.put(generator, paint);
		next.put(generator.getBiomeSource(), paint);
		bindings = next;
	}

	public static synchronized void clear() {
		bindings = Map.of();
	}
}
