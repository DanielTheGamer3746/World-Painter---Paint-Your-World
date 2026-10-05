package com.daniel.worldpainter.client.preview;

import com.daniel.worldpainter.data.VolumeLayer;

/** Turns a column with 3D edits into the run list the preview draws (same rules as world generation). */
public final class ColumnRuns {
	private ColumnRuns() {
	}

	/**
	 * @param ground    top solid block of the 2D terrain
	 * @param fluidTop  top water/lava block, or {@link SceneTile#NONE}
	 * @param fluidKind {@link SceneTile#WATER} or {@link SceneTile#LAVA}
	 * @param edits     the column's 3D edits ({@link VolumeLayer#HEIGHT} values, index 0 = {@link VolumeLayer#MIN_Y})
	 * @param types     scratch array of {@link VolumeLayer#HEIGHT} bytes
	 */
	public static short[] build(int ground, int fluidTop, byte fluidKind, byte[] edits, byte[] types) {
		for (int i = 0; i < VolumeLayer.HEIGHT; i++) {
			int y = VolumeLayer.MIN_Y + i;
			byte type = y <= ground ? SceneTile.SOLID : (fluidTop != SceneTile.NONE && y <= fluidTop) ? fluidKind : 0;
			byte e = edits[i];
			if (e == VolumeLayer.SOLID) {
				type = SceneTile.SOLID;
			} else if (e == VolumeLayer.AIR && type == SceneTile.SOLID) {
				// Carving removes ground; water and lava stay.
				type = 0;
			}
			types[i] = type;
		}
		int count = 0;
		for (int i = 0; i < VolumeLayer.HEIGHT; i++) {
			if (types[i] != 0 && (i == 0 || types[i - 1] != types[i])) {
				count++;
			}
		}
		// Ground below the lowest sculptable block is always solid.
		boolean groundBelow = ground >= VolumeLayer.MIN_Y;
		boolean extendFirst = groundBelow && types[0] == SceneTile.SOLID;
		if (groundBelow && !extendFirst) {
			count++;
		}
		short[] runs = new short[count * 3];
		int k = 0;
		if (groundBelow && !extendFirst) {
			runs[k++] = SceneTile.BOTTOM;
			runs[k++] = (short) VolumeLayer.MIN_Y;
			runs[k++] = SceneTile.SOLID;
		}
		int i = 0;
		while (i < VolumeLayer.HEIGHT) {
			byte type = types[i];
			if (type == 0) {
				i++;
				continue;
			}
			int start = i;
			while (i < VolumeLayer.HEIGHT && types[i] == type) {
				i++;
			}
			runs[k++] = (short) (start == 0 && extendFirst ? SceneTile.BOTTOM : VolumeLayer.MIN_Y + start);
			runs[k++] = (short) (VolumeLayer.MIN_Y + i);
			runs[k++] = type;
		}
		return runs;
	}
}
