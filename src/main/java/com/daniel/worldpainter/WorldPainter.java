package com.daniel.worldpainter;

import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.gen.PaintBindings;
import com.daniel.worldpainter.regen.RegionRegenerator;
import com.daniel.worldpainter.storage.WorldPaths;
import com.daniel.worldpainter.util.Log;
import net.fabricmc.api.ModInitializer;
import net.minecraft.world.World;

import java.nio.file.Path;

public class WorldPainter implements ModInitializer {
	public static final String MOD_ID = "worldpainter";
	public static final Log LOGGER = new Log("World Painter");
	/** Folder of the singleplayer world that was started last (Beta plays one world at a time). */
	private static volatile Path currentWorldRoot;

	@Override
	public void onInitialize() {
		LOGGER.info("World Painter for Beta 1.7.3 loaded");
	}

	/**
	 * Runs before a singleplayer world loads (also a brand-new one): painted-over chunks of an
	 * existing world are backed up and removed from its region files so they generate again.
	 */
	public static void beforeWorldLoads(Path worldRoot) {
		currentWorldRoot = worldRoot;
		Path paintRoot = WorldPaths.paintDir(worldRoot);
		PaintDimension d = PaintDimension.OVERWORLD;
		try {
			int removed = RegionRegenerator.processPending(worldRoot, d.dir(paintRoot));
			if (removed > 0) {
				LOGGER.info("Removed {} painted-over chunks so they regenerate with the painted design", removed);
			}
		} catch (Exception e) {
			LOGGER.error("Failed to prepare painted chunks for regeneration", e);
		}
		PaintBindings.clear();
	}

	/**
	 * The folder of a singleplayer world, or null (multiplayer). Beta's client has no method that
	 * returns a world's folder, so it is remembered when the world is started.
	 */
	public static Path worldRoot(World world) {
		if (world == null || world.isRemote) {
			return null;
		}
		return currentWorldRoot;
	}

	/** Called after the painter saved, so generation picks up the new design. */
	public static void rebind() {
		PaintBindings.clear();
	}
}
