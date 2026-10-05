package com.daniel.worldpainter;

import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.gen.Dimensions;
import com.daniel.worldpainter.gen.PaintBindings;
import com.daniel.worldpainter.regen.RegionRegenerator;
import com.daniel.worldpainter.storage.WorldPaths;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

@Mod(WorldPainter.MOD_ID)
public class WorldPainter {
	public static final String MOD_ID = "worldpainter";
	public static final Logger LOGGER = LoggerFactory.getLogger("World Painter");

	public WorldPainter() {
		// Runs before any level is loaded: chunks that were painted over in an existing
		// world are backed up and removed from the region files so they generate again.
		ServerAboutToStartEvent.BUS.addListener(event -> {
			MinecraftServer server = event.getServer();
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
		// Fires for each level as the server creates it, before any of its chunks generate.
		LevelEvent.Load.BUS.addListener(event -> {
			if (event.getLevel() instanceof ServerLevel level) {
				PaintDimension d = Dimensions.of(level.dimension());
				if (d != null) {
					bind(level.getServer(), level, d);
				}
			}
		});

		ServerStoppedEvent.BUS.addListener(event -> PaintBindings.clear());

		com.daniel.worldpainter.editor.EditorSessions.register();

		// The painter, keys and buttons only exist in the game client (never on a dedicated server).
		if (FMLEnvironment.dist == Dist.CLIENT) {
			com.daniel.worldpainter.client.WorldPainterClient.init();
		}
	}

	public static Path worldRoot(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
	}

	/** Connects a dimension's paint data (if any) to that level's generator. */
	public static void bind(MinecraftServer server, ServerLevel level, PaintDimension dimension) {
		Path paintDir = dimension.dir(WorldPaths.paintDir(worldRoot(server)));
		PaintBindings.bindLevel(server, level, paintDir, dimension);
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
