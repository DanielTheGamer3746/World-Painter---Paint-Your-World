package com.daniel.worldpainter.client;

import com.daniel.worldpainter.data.PaintDimension;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.Path;

/**
 * What the painter is editing.
 *
 * @param kind        where it was opened from
 * @param paintRoot   the world's (or draft's) paint folder; each dimension has its design inside it
 * @param dimension   which dimension's design is edited
 * @param worldRoot   world folder for existing worlds (null for a draft)
 * @param levelId     world folder name (null for a draft)
 * @param displayName shown in the title bar
 * @param parent      screen to return to
 */
public record PainterTarget(Kind kind, Path paintRoot, PaintDimension dimension, Path worldRoot, String levelId,
							String displayName, Screen parent) {
	public enum Kind {
		/** A world that is being created on the Create World screen. */
		NEW_WORLD,
		/** An existing world opened from the world list (not running). */
		SAVED_WORLD,
		/** The world currently being played in singleplayer. */
		RUNNING_WORLD
	}

	/** Folder with this dimension's design. */
	public Path paintDir() {
		return dimension.dir(paintRoot);
	}

	/** The same world, another dimension. */
	public PainterTarget withDimension(PaintDimension d) {
		return new PainterTarget(kind, paintRoot, d, worldRoot, levelId, displayName, parent);
	}

	/** Existing worlds have chunks that must be regenerated where paint changes. */
	public boolean existingWorld() {
		return kind != Kind.NEW_WORLD;
	}
}
