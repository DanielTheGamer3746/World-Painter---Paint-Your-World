package com.daniel.worldpainter.storage;

import java.nio.file.Path;

/** Where World Painter keeps its files. */
public final class WorldPaths {
	public static final String PAINT_DIR_NAME = "worldpainter";

	private WorldPaths() {
	}

	/** Paint data that belongs to a world lives inside the world folder, so it travels with the save. */
	public static Path paintDir(Path worldRoot) {
		return worldRoot.resolve(PAINT_DIR_NAME);
	}

	/** Paint data for a world that has not been created yet (used from the Create World screen). */
	public static Path draftDir(Path gameDir) {
		return gameDir.resolve(PAINT_DIR_NAME).resolve("draft");
	}

	/** User-made templates are shared between all worlds. */
	public static Path templatesDir(Path gameDir) {
		return gameDir.resolve("config").resolve(PAINT_DIR_NAME).resolve("templates");
	}
}
