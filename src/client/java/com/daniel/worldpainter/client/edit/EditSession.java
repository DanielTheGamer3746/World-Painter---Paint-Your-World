package com.daniel.worldpainter.client.edit;

import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.PaintWorld;
import com.daniel.worldpainter.data.StructurePlan;
import com.daniel.worldpainter.data.TileKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every change to the painted world goes through here: it records undo information (a copy of each
 * tile before the first change in a stroke), marks tiles as modified and marks chunks that must be
 * regenerated in existing worlds.
 */
public final class EditSession {
	private static final long MAX_UNDO_BYTES = 384L * 1024 * 1024;
	private static final int MAX_UNDO_STEPS = 100;

	public final PaintWorld world;
	private final boolean trackRegen;
	private final Deque<Step> undo = new ArrayDeque<>();
	private final Deque<Step> redo = new ArrayDeque<>();
	private Step current;
	private int changeCount;

	/** Something outside the design that a step also changed (blocks in the world), undone with it. */
	public interface Undoable {
		void undo();

		void redo();
	}

	private static final class Step {
		final String name;
		/** Tile content before the step; a tile that did not exist before is stored as an empty tile. */
		final Map<Long, PaintTile> before = new HashMap<>();
		/** Structure plan before the step, if the step changed structures. */
		StructurePlan planBefore;
		/** Changes made in the world itself during the step. */
		final List<Undoable> attached = new ArrayList<>();
		long bytes;

		boolean isEmpty() {
			return before.isEmpty() && planBefore == null && attached.isEmpty();
		}

		Step(String name) {
			this.name = name;
		}
	}

	public EditSession(PaintWorld world, boolean trackRegen) {
		this.world = world;
		this.trackRegen = trackRegen;
	}

	/** Increases whenever something changed; the map uses it to know when to redraw. */
	public int changeCount() {
		return changeCount;
	}

	public void begin(String name) {
		if (current != null) {
			end();
		}
		current = new Step(name);
	}

	public void end() {
		if (current == null) {
			return;
		}
		if (!current.isEmpty()) {
			undo.push(current);
			redo.clear();
			trimUndo();
		}
		current = null;
	}

	public boolean inStroke() {
		return current != null;
	}

	/** Returns a tile ready to be changed (records undo data the first time in this step). */
	public PaintTile write(int tx, int tz) {
		PaintTile tile = world.getOrCreate(tx, tz);
		long key = TileKey.of(tx, tz);
		if (current != null && !current.before.containsKey(key)) {
			PaintTile copy = tile.copy();
			current.before.put(key, copy);
			current.bytes += copy.memoryBytes();
		}
		world.markModified(tile);
		changeCount++;
		return tile;
	}

	/**
	 * Changes the structure plan: {@code change} edits a copy, which then replaces the plan.
	 * Records undo information like tile edits do.
	 */
	public void editStructures(java.util.function.Consumer<StructurePlan> change) {
		StructurePlan now = world.structures();
		if (current != null && current.planBefore == null) {
			current.planBefore = now.copy();
		}
		StructurePlan next = now.copy();
		change.accept(next);
		world.setStructures(next);
		changeCount++;
	}

	/** Adds a change made in the world to the current step, so undo/redo also reverts it. */
	public void attach(Undoable change) {
		if (current != null) {
			current.attached.add(change);
		}
	}

	/** Marks whole chunks for regeneration (existing worlds only). */
	public void regenChunks(int minCx, int minCz, int maxCx, int maxCz) {
		if (trackRegen) {
			for (int cz = minCz; cz <= maxCz; cz++) {
				for (int cx = minCx; cx <= maxCx; cx++) {
					world.markRegen(cx, cz);
				}
			}
		}
		changeCount++;
	}

	public boolean tracksRegen() {
		return trackRegen;
	}

	/** Marks the chunks under a changed block rectangle for regeneration (existing worlds only). */
	public void changed(int minX, int minZ, int maxX, int maxZ) {
		if (trackRegen) {
			world.markRegenRect(minX, minZ, maxX, maxZ);
		}
		changeCount++;
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}

	public String undo() {
		end();
		Step step = undo.poll();
		if (step == null) {
			return null;
		}
		redo.push(swap(step));
		for (int i = step.attached.size() - 1; i >= 0; i--) {
			step.attached.get(i).undo();
		}
		return step.name;
	}

	public String redo() {
		end();
		Step step = redo.poll();
		if (step == null) {
			return null;
		}
		undo.push(swap(step));
		for (Undoable u : step.attached) {
			u.redo();
		}
		return step.name;
	}

	/** Restores the saved tiles and returns a step that restores the current state again. */
	private Step swap(Step step) {
		Step inverse = new Step(step.name);
		inverse.attached.addAll(step.attached);
		for (Map.Entry<Long, PaintTile> e : step.before.entrySet()) {
			int tx = TileKey.x(e.getKey());
			int tz = TileKey.z(e.getKey());
			PaintTile tile = world.getOrCreate(tx, tz);
			PaintTile now = tile.copy();
			inverse.before.put(e.getKey(), now);
			inverse.bytes += now.memoryBytes();
			tile.copyFrom(e.getValue());
			world.markModified(tile);
			if (trackRegen) {
				// Only the chunks the undo actually changes (live changes regenerate these right away).
				for (int lcz = 0; lcz < 16; lcz++) {
					for (int lcx = 0; lcx < 16; lcx++) {
						if (now.differsInChunk(tile, lcx, lcz)) {
							world.markRegen((tx << 4) | lcx, (tz << 4) | lcz);
						}
					}
				}
			}
		}
		if (step.planBefore != null) {
			inverse.planBefore = world.structures().copy();
			world.setStructures(step.planBefore.copy());
		}
		changeCount++;
		return inverse;
	}

	private void trimUndo() {
		long total = 0;
		int count = 0;
		for (Step s : undo) {
			total += s.bytes;
			count++;
		}
		while ((total > MAX_UNDO_BYTES || count > MAX_UNDO_STEPS) && undo.size() > 1) {
			Step oldest = undo.pollLast();
			total -= oldest.bytes;
			count--;
		}
	}

	/** Forgets all history (after "clear everything"). */
	public void resetHistory() {
		undo.clear();
		redo.clear();
		current = null;
		changeCount++;
	}
}
