package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.client.edit.Ops;
import com.daniel.worldpainter.client.edit.PaintValue;
import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintTile;

/** Removes paint from the selected layer (Shift: from all layers), so vanilla generation shows through. */
public final class EraseTool extends BrushStrokeTool {
	@Override
	public String name() {
		return "Eraser";
	}

	@Override
	public char hotkey() {
		return 'E';
	}

	@Override
	public String help() {
		return "Erase paint on the chosen layer (Shift: all layers and 3D edits). Unpainted land generates normally.";
	}

	@Override
	protected void dab(PainterState s, int cx, int cz, boolean alt) {
		Brush b = s.brush;
		int r = b.radius;
		Layer layer = s.layer;
		Ops.forPixels(s.session, cx - r, cz - r, cx + r, cz + r, (t, i, x, z) -> {
			if (!b.covers(x - cx, z - cz)) {
				return;
			}
			if (alt) {
				for (Layer l : Layer.values()) {
					PaintValue.clear(t, i, l);
				}
				t.volume.clearColumn(x & PaintTile.MASK, z & PaintTile.MASK);
			} else {
				PaintValue.clear(t, i, layer);
			}
		});
	}
}
