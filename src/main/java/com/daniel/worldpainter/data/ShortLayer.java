package com.daniel.worldpainter.data;

import java.util.Arrays;

/**
 * One 256x256 layer of shorts. A layer that holds the same value everywhere (very common:
 * unpainted, or a whole tile filled with one biome) is stored as a single value, and only
 * gets a real array once a single pixel differs.
 */
public final class ShortLayer {
	private short[] data;
	private short uniform;

	public ShortLayer(short none) {
		this.uniform = none;
	}

	public short get(int i) {
		short[] d = data;
		return d != null ? d[i] : uniform;
	}

	public void set(int i, short v) {
		if (data == null) {
			if (v == uniform) {
				return;
			}
			data = new short[PaintTile.AREA];
			Arrays.fill(data, uniform);
		}
		data[i] = v;
	}

	public void fill(short v) {
		data = null;
		uniform = v;
	}

	public boolean isUniform() {
		return data == null;
	}

	public short uniformValue() {
		return uniform;
	}

	public boolean isAll(short v) {
		if (data == null) {
			return uniform == v;
		}
		for (short s : data) {
			if (s != v) {
				return false;
			}
		}
		return true;
	}

	/** Collapses the array back to a single value when possible (saves memory and disk). */
	public void compact() {
		if (data != null) {
			short first = data[0];
			for (short s : data) {
				if (s != first) {
					return;
				}
			}
			data = null;
			uniform = first;
		}
	}

	public short[] rawData() {
		return data;
	}

	public void setRaw(short[] d, short u) {
		data = d;
		uniform = u;
	}

	public ShortLayer copy() {
		ShortLayer c = new ShortLayer(uniform);
		if (data != null) {
			c.data = data.clone();
		}
		return c;
	}

	public long memoryBytes() {
		return data != null ? data.length * 2L : 0L;
	}
}
