package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintWorld;
import com.daniel.worldpainter.storage.WorldPaths;
import net.minecraft.world.World;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Finds the paint data of a world: the design in the world's folder, opened the first time
 * generation asks for it. Beta generates the singleplayer world on the game thread, the map
 * preview reads region files on its own threads, so this is simply synchronized.
 */
public final class PaintBindings {
	private static final int MAX_LOADED_TILES = 1024;
	private static final Object NONE = new Object();
	private static final Map<World, Object> BY_WORLD = new WeakHashMap<>();
	private static final Map<Path, GenPaint> BY_FOLDER = new HashMap<>();
	/** While the painter is open on the running world with live changes: its design, as it is being painted. */
	private static Path liveRoot;
	private static PaintWorld liveDesign;

	private PaintBindings() {
	}

	/** The design for a world's generation, or null (no design, another dimension, multiplayer). */
	public static synchronized GenPaint get(World world) {
		if (world == null) {
			return null;
		}
		Object cached = BY_WORLD.get(world);
		if (cached == null) {
			cached = find(world);
			BY_WORLD.put(world, cached);
		}
		return cached == NONE ? null : (GenPaint) cached;
	}

	private static Object find(World world) {
		// Dimension 0 is the Overworld (-1 the Nether); before the dimension is set nothing is painted.
		if (world.dimension == null || world.dimension.id != 0) {
			return NONE;
		}
		Path root = WorldPainter.worldRoot(world);
		if (root == null) {
			return NONE;
		}
		GenPaint paint = BY_FOLDER.get(root);
		if (paint == null && liveDesign != null && root.equals(liveRoot)) {
			paint = new GenPaint(liveDesign, PaintDimension.OVERWORLD);
			BY_FOLDER.put(root, paint);
		}
		if (paint == null) {
			PaintDimension d = PaintDimension.OVERWORLD;
			Path dir = d.dir(WorldPaths.paintDir(root));
			if (!PaintWorld.hasPaint(dir)) {
				return NONE;
			}
			paint = new GenPaint(PaintWorld.open(dir, MAX_LOADED_TILES), d);
			BY_FOLDER.put(root, paint);
			WorldPainter.LOGGER.info("World Painter design active for {}", root.getFileName());
		}
		return paint;
	}

	/**
	 * Generation of this world uses the painter's design directly (every stroke counts at once, before
	 * it is saved). {@code null} goes back to the saved design.
	 */
	public static synchronized void useDesign(Path root, PaintWorld design) {
		liveRoot = design == null ? null : root;
		liveDesign = design;
		BY_WORLD.clear();
		BY_FOLDER.clear();
	}

	/** Forgets every opened design, so the next lookup reads the saved design again. */
	public static synchronized void clear() {
		BY_WORLD.clear();
		BY_FOLDER.clear();
	}
}
