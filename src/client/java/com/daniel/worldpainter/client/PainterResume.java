package com.daniel.worldpainter.client;

import com.daniel.worldpainter.data.PaintDimension;

/**
 * Brings the painter back after the world was (re)opened from it: "Save & Reload" in the 3D view,
 * or opening a saved world in 3D from the world list. The painter opens again in the 3D view with
 * the same camera as soon as the player is in the world.
 */
public final class PainterResume {
	/**
	 * @param pivotY NaN when the ground height there is not known yet (it is looked up when the view opens)
	 */
	public record State(String levelId, PaintDimension dimension, double pivotX, double pivotY, double pivotZ,
						float yaw, float pitch, double distance, int mapZoom, long createdMillis) {
	}

	private static final long MAX_AGE_MILLIS = 10 * 60 * 1000L;
	private static State pending;

	private PainterResume() {
	}

	public static void set(State state) {
		pending = state;
	}

	public static boolean pending() {
		return pending != null;
	}

	/** The waiting state for this world (and forgets it), or null. A state for another world is dropped. */
	public static State take(String levelId) {
		State s = pending;
		pending = null;
		if (s == null || !s.levelId().equals(levelId) || System.currentTimeMillis() - s.createdMillis() > MAX_AGE_MILLIS) {
			return null;
		}
		return s;
	}
}
