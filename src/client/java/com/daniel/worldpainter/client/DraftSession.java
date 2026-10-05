package com.daniel.worldpainter.client;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintWorld;
import com.daniel.worldpainter.storage.IoUtil;
import com.daniel.worldpainter.storage.WorldPaths;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The painter can be opened from the Create World screen before the world exists. The design is
 * saved as a draft, and moved into the new world's folder the moment the world is created.
 */
public final class DraftSession {
	private static boolean armed;

	private DraftSession() {
	}

	public static Path draftDir() {
		return WorldPaths.draftDir(Minecraft.getInstance().gameDirectory.toPath());
	}

	/** The player painted on the Create World screen: the next created world gets the draft. */
	public static void arm() {
		armed = true;
	}

	/** The player left the Create World screen without creating a world. */
	public static void disarm() {
		armed = false;
	}

	public static boolean isArmed() {
		return armed;
	}

	/** Called while a new world is being created, before it first loads. */
	public static void attachTo(Path worldRoot) {
		if (!armed) {
			return;
		}
		armed = false;
		Path draft = draftDir();
		if (!PaintDimension.anyPaint(draft)) {
			return;
		}
		Path target = WorldPaths.paintDir(worldRoot);
		try {
			IoUtil.copyRecursively(draft, target);
			// A brand-new world has no chunks to regenerate.
			for (PaintDimension d : PaintDimension.values()) {
				Files.deleteIfExists(d.dir(target).resolve(PaintWorld.PENDING_REGEN));
			}
			IoUtil.deleteRecursively(draft);
			WorldPainter.LOGGER.info("Attached painted design to new world {}", worldRoot.getFileName());
		} catch (IOException e) {
			WorldPainter.LOGGER.error("Could not copy the painted design into the new world", e);
		}
	}
}
