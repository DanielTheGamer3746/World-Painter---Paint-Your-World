package com.daniel.worldpainter.client.tools;

import com.daniel.worldpainter.util.MathUtil;

import com.daniel.worldpainter.client.edit.Ops;
import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.data.PaintTile;

/** Sculpts terrain height: raise, lower, smooth, flatten or roughen. */
public final class HeightTool extends BrushStrokeTool {
	private int flattenTarget;

	@Override
	public String name() {
		return "Sculpt";
	}

	@Override
	public char hotkey() {
		return 'H';
	}

	@Override
	public String help() {
		return "Sculpt height (mode in the side panel). Hold to keep raising. Shift inverts raise/lower; Shift+Flatten uses the height value.";
	}

	@Override
	protected void startStroke(PainterState s, int x, int z, boolean alt) {
		flattenTarget = alt ? s.height : s.effectiveHeight(x, z);
	}

	@Override
	public void hold(PainterState s, int x, int z, boolean alt) {
		if (s.heightMode == PainterState.HeightMode.RAISE || s.heightMode == PainterState.HeightMode.LOWER) {
			dabCounter++;
			dab(s, x, z, alt);
		}
	}

	@Override
	protected void dab(PainterState s, int cx, int cz, boolean alt) {
		Brush b = s.brush;
		int r = b.radius;
		int amount = Math.max(1, b.strength);
		int salt = dabCounter;
		switch (s.heightMode) {
			case RAISE, LOWER -> {
				int sign = (s.heightMode == PainterState.HeightMode.RAISE) != alt ? 1 : -1;
				Ops.forPixels(s.session, cx - r, cz - r, cx + r, cz + r, (t, i, x, z) -> {
					float w = b.weight(x - cx, z - cz);
					if (w <= 0f) {
						return;
					}
					int cur = current(s, t, i, x, z);
					int d = (int) Math.floor(amount * w + hash01(x, z, salt));
					if (d != 0) {
						set(t, i, cur + sign * d);
					}
				});
			}
			case NOISE -> Ops.forPixels(s.session, cx - r, cz - r, cx + r, cz + r, (t, i, x, z) -> {
				float w = b.weight(x - cx, z - cz);
				if (w <= 0f) {
					return;
				}
				int cur = current(s, t, i, x, z);
				double n = hash01(x, z, salt) * 2 - 1;
				set(t, i, cur + (int) Math.round(n * amount * w * 0.75));
			});
			case FLATTEN -> {
				float factor = Math.min(1f, amount / 8f);
				int target = flattenTarget;
				Ops.forPixels(s.session, cx - r, cz - r, cx + r, cz + r, (t, i, x, z) -> {
					float w = b.weight(x - cx, z - cz);
					if (w <= 0f) {
						return;
					}
					int cur = current(s, t, i, x, z);
					set(t, i, Math.round(cur + (target - cur) * w * factor));
				});
			}
			case SMOOTH -> smooth(s, cx, cz, 1 + amount / 8, Math.min(1f, 0.25f + amount / 16f));
		}
	}

	private static int current(PainterState s, PaintTile t, int i, int x, int z) {
		short painted = t.height.get(i);
		return painted != PaintTile.NO_HEIGHT ? painted : s.unpaintedHeight(x, z);
	}

	private static void set(PaintTile t, int i, int h) {
		t.height.set(i, (short) MathUtil.clamp(h, PainterState.MIN_Y, PainterState.MAX_Y));
	}

	/**
	 * Blurs heights under the brush with a box of radius {@code k}. Only pixels that are painted,
	 * or have painted neighbours, change, so smoothing never flattens untouched vanilla terrain.
	 */
	public static void smooth(PainterState s, int cx, int cz, int k, float amount) {
		Brush b = s.brush;
		int r = b.radius;
		smoothRect(s, cx - r, cz - r, cx + r, cz + r, k, amount, (x, z) -> b.weight(x - cx, z - cz));
	}

	public interface Weight {
		float at(int x, int z);
	}

	/** Height blur over a rectangle (inclusive bounds) with a per-pixel weight. */
	public static void smoothRect(PainterState s, int minX, int minZ, int maxX, int maxZ, int k, float amount, Weight weight) {
		int x0 = minX - k;
		int z0 = minZ - k;
		int sizeX = maxX - minX + 1 + 2 * k;
		int sizeZ = maxZ - minZ + 1 + 2 * k;
		int stride = sizeX + 1;
		long[] sum = new long[stride * (sizeZ + 1)];
		int[] painted = new int[stride * (sizeZ + 1)];
		for (int z = 0; z < sizeZ; z++) {
			long rowSum = 0;
			int rowPainted = 0;
			for (int x = 0; x < sizeX; x++) {
				int wx = x0 + x, wz = z0 + z;
				short ph = s.paintedHeight(wx, wz);
				int h = ph != PaintTile.NO_HEIGHT ? ph : s.unpaintedHeight(wx, wz);
				rowSum += h;
				rowPainted += ph != PaintTile.NO_HEIGHT ? 1 : 0;
				int idx = (z + 1) * stride + (x + 1);
				sum[idx] = sum[idx - stride] + rowSum;
				painted[idx] = painted[idx - stride] + rowPainted;
			}
		}
		Ops.forPixels(s.session, minX, minZ, maxX, maxZ, (t, i, x, z) -> {
			float w = weight.at(x, z);
			if (w <= 0f) {
				return;
			}
			int lx = x - x0, lz = z - z0;
			int ax = lx - k, az = lz - k, bx = lx + k + 1, bz = lz + k + 1;
			int count = (bx - ax) * (bz - az);
			int p = painted[bz * stride + bx] - painted[az * stride + bx] - painted[bz * stride + ax] + painted[az * stride + ax];
			if (p == 0) {
				return;
			}
			long total = sum[bz * stride + bx] - sum[az * stride + bx] - sum[bz * stride + ax] + sum[az * stride + ax];
			double avg = (double) total / count;
			int cur = current(s, t, i, x, z);
			set(t, i, (int) Math.round(cur + (avg - cur) * w * amount));
		});
	}
}
