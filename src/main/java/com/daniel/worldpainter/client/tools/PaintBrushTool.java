package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.util.MathUtil;

import com.daniel.worldpainter.client.edit.Ops;
import com.daniel.worldpainter.client.edit.PaintValue;
import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintTile;

/** Paints the selected layer value. Shift erases. On the height layer, soft edges blend towards the height. */
public final class PaintBrushTool extends BrushStrokeTool {
	@Override
	public String name() {
		return "Brush";
	}

	@Override
	public char hotkey() {
		return 'B';
	}

	@Override
	public String help() {
		return "Paint the chosen biome / height / surface / fluid. Hold Shift to erase.";
	}

	@Override
	protected void dab(PainterState s, int cx, int cz, boolean alt) {
		Brush b = s.brush;
		int r = b.radius;
		Layer layer = s.layer;
		if (alt) {
			Ops.forPixels(s.session, cx - r, cz - r, cx + r, cz + r, (t, i, x, z) -> {
				if (b.covers(x - cx, z - cz)) {
					PaintValue.clear(t, i, layer);
				}
			});
			return;
		}
		PaintValue v = s.currentValue();
		if (layer == Layer.HEIGHT) {
			int target = v.height();
			Ops.forPixels(s.session, cx - r, cz - r, cx + r, cz + r, (t, i, x, z) -> {
				float w = b.weight(x - cx, z - cz);
				if (w <= 0f) {
					return;
				}
				short painted = t.height.get(i);
				int cur = painted != PaintTile.NO_HEIGHT ? painted : s.unpaintedHeight(x, z);
				int nh = w >= 0.999f ? target : Math.round(cur + (target - cur) * w);
				t.height.set(i, (short) MathUtil.clamp(nh, PainterState.MIN_Y, PainterState.MAX_Y));
			});
		} else {
			Ops.forPixels(s.session, cx - r, cz - r, cx + r, cz + r, (t, i, x, z) -> {
				if (b.covers(x - cx, z - cz)) {
					v.write(t, i);
				}
			});
		}
	}
}
