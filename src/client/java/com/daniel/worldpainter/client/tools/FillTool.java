package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.edit.Ops;
import com.daniel.worldpainter.client.edit.PaintValue;
import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintTile;

import java.util.Arrays;
import java.util.BitSet;

/** Bucket fill: replaces the connected area of the same value (Shift: erases it). */
public final class FillTool implements Tool {
	private static final int MAX_REACH = 4096;
	private static final int MAX_PIXELS = 8_000_000;

	@Override
	public String name() {
		return "Fill";
	}

	@Override
	public char hotkey() {
		return 'G';
	}

	@Override
	public String help() {
		return "Fill the connected area that has the same value (up to 4096 blocks away). Shift erases it.";
	}

	@Override
	public boolean usesBrush() {
		return false;
	}

	@Override
	public void press(PainterState s, int x, int z, boolean alt) {
		if (!PainterState.inWorld(x, z)) {
			return;
		}
		Layer layer = s.layer;
		PaintValue value = s.currentValue();
		long start = s.valueKey(x, z, layer);
		if (!alt) {
			PaintTile t = s.tileAt(x, z);
			if (t != null && value.matches(t, PaintTile.indexForBlock(x, z))) {
				s.say("That area already has this value.");
				return;
			}
		} else if (start == Long.MIN_VALUE) {
			return;
		}

		final int size = MAX_REACH * 2 + 1;
		final int x0 = x - MAX_REACH, z0 = z - MAX_REACH;
		BitSet visited = new BitSet(size * size);
		int[] queue = new int[1 << 16];
		int head = 0, tail = 0;
		int minX = x, maxX = x, minZ = z, maxZ = z;
		boolean hitEdge = false;

		int first = MAX_REACH * size + MAX_REACH;
		queue[tail++] = first;
		visited.set(first);
		while (head < tail) {
			int idx = queue[head++];
			int lx = idx % size, lz = idx / size;
			int wx = x0 + lx, wz = z0 + lz;
			minX = Math.min(minX, wx);
			maxX = Math.max(maxX, wx);
			minZ = Math.min(minZ, wz);
			maxZ = Math.max(maxZ, wz);
			for (int dir = 0; dir < 4; dir++) {
				int nx = lx + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
				int nz = lz + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
				if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
					hitEdge = true;
					continue;
				}
				int n = nz * size + nx;
				if (visited.get(n)) {
					continue;
				}
				int px = x0 + nx, pz = z0 + nz;
				if (!PainterState.inWorld(px, pz) || s.valueKey(px, pz, layer) != start) {
					continue;
				}
				visited.set(n);
				if (tail == queue.length) {
					queue = Arrays.copyOf(queue, queue.length * 2);
				}
				queue[tail++] = n;
			}
			if (tail > MAX_PIXELS) {
				s.say("Area too big to fill (over 8 million blocks). Use the rectangle tool or a selection instead.");
				return;
			}
		}

		s.session.begin(name());
		Ops.forPixels(s.session, minX, minZ, maxX, maxZ, (t, i, px, pz) -> {
			if (!visited.get((pz - z0) * size + (px - x0))) {
				return;
			}
			if (alt) {
				PaintValue.clear(t, i, layer);
			} else {
				value.write(t, i);
			}
		});
		s.session.end();
		s.say((alt ? "Erased " : "Filled ") + String.format("%,d", tail) + " blocks"
				+ (hitEdge ? " (stopped 4096 blocks from where you clicked)" : ""));
	}
}
