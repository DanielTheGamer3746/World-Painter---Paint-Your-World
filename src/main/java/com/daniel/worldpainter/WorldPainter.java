package com.daniel.worldpainter;

import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintWorld;
import com.daniel.worldpainter.gen.Dimensions;
import com.daniel.worldpainter.gen.PaintBindings;
import com.daniel.worldpainter.regen.RegionRegenerator;
import com.daniel.worldpainter.storage.WorldPaths;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class WorldPainter implements ModInitializer {
	public static final String MOD_ID = "worldpainter";
	public static final Logger LOGGER = LoggerFactory.getLogger("World Painter");

	@Override
	public void onInitialize() {
		// Runs before any level is loaded: chunks that were painted over in an existing
		// world are backed up and removed from the region files so they generate again.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			Path root = worldRoot(server);
			Path paintRoot = WorldPaths.paintDir(root);
			for (PaintDimension d : PaintDimension.values()) {
				try {
					int removed = RegionRegenerator.processPending(root, d.dir(paintRoot), Dimensions.key(d));
					if (removed > 0) {
						LOGGER.info("Removed {} painted-over chunks in the {} so they regenerate with the painted design", removed, d.displayName);
					}
				} catch (Exception e) {
					LOGGER.error("Failed to prepare painted chunks in the {} for regeneration", d.displayName, e);
				}
			}
		});

		// Every dimension (Overworld, Nether, End) generates with its own design.
		ServerLevelEvents.LOAD.register((server, level) -> {
			PaintDimension d = Dimensions.of(level.dimension());
			if (d != null) {
				bind(server, level, d);
			}
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PaintBindings.clear());

		com.daniel.worldpainter.editor.EditorSessions.register();
	}

	public static Path worldRoot(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
	}

	/** Connects a dimension's paint data (if any) to that level's generator. */
	public static void bind(MinecraftServer server, ServerLevel level, PaintDimension dimension) {
		Path paintDir = dimension.dir(WorldPaths.paintDir(worldRoot(server)));
		PaintBindings.bindLevel(server, level, paintDir, dimension);
	}

	/**
	 * Server thread: while the painter is open with live changes, a dimension generates with the
	 * painter's design as it is painted; {@code design} null goes back to the saved design.
	 */
	public static void useLiveDesign(MinecraftServer server, PaintDimension dimension, PaintWorld design) {
		ServerLevel level = server.getLevel(Dimensions.key(dimension));
		if (level == null) {
			return;
		}
		if (design == null) {
			bind(server, level, dimension);
		} else {
			PaintBindings.useDesign(server, level, dimension, design);
		}
	}

	/** Called (on the server thread) after the in-game painter saved, so generation picks up the new design. */
	public static void rebind(MinecraftServer server) {
		for (PaintDimension d : PaintDimension.values()) {
			ServerLevel level = server.getLevel(Dimensions.key(d));
			if (level != null) {
				bind(server, level, d);
			}
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
