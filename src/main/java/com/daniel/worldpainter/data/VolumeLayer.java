package com.daniel.worldpainter.data;

import java.util.Arrays;

/**
 * The 3D part of a tile: blocks the player sculpted solid (overhangs, arches, floating islands) or
 * carved to air (caves, tunnels) on top of the 2D design. Values are absolute: a cave carved at Y 40
 * stays at Y 40 whatever height is painted later.
 *
 * <p>Stored in 16x16x16 sections that only exist where something was sculpted, so a tile without
 * 3D edits costs nothing and a floating island costs a few hundred kilobytes.
 */
public final class VolumeLayer {
	/** No 3D edit: the block is whatever the 2D design / Minecraft makes it. */
	public static final byte KEEP = 0;
	/** Carved: ground becomes air (water and lava are left alone). */
	public static final byte AIR = 1;
	/** Sculpted: becomes solid ground (stone, which the surface rules cover with grass, sand, ...). */
	public static final byte SOLID = 2;

	public static final int MIN_Y = -64;
	public static final int MAX_Y = 319;
	public static final int HEIGHT = MAX_Y - MIN_Y + 1;
	public static final int SECTIONS_Y = HEIGHT >> 4;
	/** Sections per tile: 16 x 16 columns of sections, each {@link #SECTIONS_Y} high. */
	public static final int SECTION_COUNT = 16 * 16 * SECTIONS_Y;
	public static final int SECTION_VOLUME = 16 * 16 * 16;

	private byte[][] sections;
	private int allocated;
	private volatile int modCount;
	/** Which columns have edits; rebuilt after changes. Replaced as a whole so generation threads can share it. */
	private volatile ColumnMask columnMask;

	private record ColumnMask(long[] bits, int modCount) {
	}

	/** Index of the section holding a block (local tile x/z 0..255, world y). */
	public static int sectionIndex(int sx, int sy, int sz) {
		return ((sz << 4) | sx) * SECTIONS_Y + sy;
	}

	/** Index of a block inside its section. */
	public static int voxelIndex(int lx, int y, int lz) {
		return ((y & 15) << 8) | ((lz & 15) << 4) | (lx & 15);
	}

	public static boolean inRange(int y) {
		return y >= MIN_Y && y <= MAX_Y;
	}

	public boolean isEmpty() {
		return allocated == 0;
	}

	public byte get(int lx, int y, int lz) {
		byte[][] s = sections;
		if (s == null || y < MIN_Y || y > MAX_Y) {
			return KEEP;
		}
		byte[] sec = s[sectionIndex(lx >> 4, (y - MIN_Y) >> 4, lz >> 4)];
		return sec == null ? KEEP : sec[voxelIndex(lx, y - MIN_Y, lz)];
	}

	/** Sets one block (local tile x/z 0..255, world y). Out-of-range heights are ignored. */
	public void set(int lx, int y, int lz, byte value) {
		if (y < MIN_Y || y > MAX_Y) {
			return;
		}
		int si = sectionIndex(lx >> 4, (y - MIN_Y) >> 4, lz >> 4);
		byte[] sec = sections == null ? null : sections[si];
		if (sec == null) {
			if (value == KEEP) {
				return;
			}
			if (sections == null) {
				sections = new byte[SECTION_COUNT][];
			}
			sec = new byte[SECTION_VOLUME];
			sections[si] = sec;
			allocated++;
		}
		int vi = voxelIndex(lx, y - MIN_Y, lz);
		if (sec[vi] != value) {
			sec[vi] = value;
			modCount++;
		}
	}

	/** The raw section (section coordinates sx/sz 0..15, sy 0..{@link #SECTIONS_Y}-1), or null if it has no edits. */
	public byte[] section(int sx, int sy, int sz) {
		byte[][] s = sections;
		return s == null ? null : s[sectionIndex(sx, sy, sz)];
	}

	/** True if any block in this column has a 3D edit. */
	public boolean columnHasEdits(int lx, int lz) {
		if (allocated == 0) {
			return false;
		}
		long[] mask = columnMask();
		int i = PaintTile.index(lx, lz);
		return (mask[i >> 6] & (1L << (i & 63))) != 0;
	}

	/** Bit per column (index {@link PaintTile#index}) that has any 3D edit; rebuilt only after changes. */
	private long[] columnMask() {
		int mod = modCount;
		ColumnMask cached = columnMask;
		if (cached != null && cached.modCount() == mod) {
			return cached.bits();
		}
		long[] mask = new long[PaintTile.AREA / 64];
		byte[][] s = sections;
		if (s != null) {
			for (int sz = 0; sz < 16; sz++) {
				for (int sx = 0; sx < 16; sx++) {
					for (int sy = 0; sy < SECTIONS_Y; sy++) {
						byte[] sec = s[sectionIndex(sx, sy, sz)];
						if (sec == null) {
							continue;
						}
						for (int v = 0; v < SECTION_VOLUME; v++) {
							if (sec[v] != KEEP) {
								int i = PaintTile.index((sx << 4) | (v & 15), (sz << 4) | ((v >> 4) & 15));
								mask[i >> 6] |= 1L << (i & 63);
							}
						}
					}
				}
			}
		}
		columnMask = new ColumnMask(mask, mod);
		return mask;
	}

	/** Copies the edits of one column into {@code out} (length {@link #HEIGHT}, index 0 = {@link #MIN_Y}). */
	public void column(int lx, int lz, byte[] out) {
		Arrays.fill(out, KEEP);
		byte[][] s = sections;
		if (s == null) {
			return;
		}
		int base = ((lz & 15) << 4) | (lx & 15);
		for (int sy = 0; sy < SECTIONS_Y; sy++) {
			byte[] sec = s[sectionIndex(lx >> 4, sy, lz >> 4)];
			if (sec == null) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				out[(sy << 4) | y] = sec[(y << 8) | base];
			}
		}
	}

	/** Highest Y in this column holding {@code value}, or Integer.MIN_VALUE if there is none. */
	public int highest(int lx, int lz, byte value) {
		byte[][] s = sections;
		if (s == null) {
			return Integer.MIN_VALUE;
		}
		int base = ((lz & 15) << 4) | (lx & 15);
		for (int sy = SECTIONS_Y - 1; sy >= 0; sy--) {
			byte[] sec = s[sectionIndex(lx >> 4, sy, lz >> 4)];
			if (sec == null) {
				continue;
			}
			for (int y = 15; y >= 0; y--) {
				if (sec[(y << 8) | base] == value) {
					return MIN_Y + (sy << 4) + y;
				}
			}
		}
		return Integer.MIN_VALUE;
	}

	/** Removes every 3D edit in a column. */
	public void clearColumn(int lx, int lz) {
		byte[][] s = sections;
		if (s == null) {
			return;
		}
		int base = ((lz & 15) << 4) | (lx & 15);
		for (int sy = 0; sy < SECTIONS_Y; sy++) {
			byte[] sec = s[sectionIndex(lx >> 4, sy, lz >> 4)];
			if (sec == null) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				if (sec[(y << 8) | base] != KEEP) {
					sec[(y << 8) | base] = KEEP;
					modCount++;
				}
			}
		}
	}

	public void clear() {
		if (sections != null) {
			sections = null;
			allocated = 0;
			modCount++;
		}
	}

	/** Drops sections that no longer hold any edit (after restoring / erasing). */
	public void compact() {
		byte[][] s = sections;
		if (s == null) {
			return;
		}
		for (int i = 0; i < s.length; i++) {
			byte[] sec = s[i];
			if (sec != null && isAllKeep(sec)) {
				s[i] = null;
				allocated--;
			}
		}
		if (allocated == 0) {
			sections = null;
		}
	}

	private static boolean isAllKeep(byte[] sec) {
		for (byte b : sec) {
			if (b != KEEP) {
				return false;
			}
		}
		return true;
	}

	/** Number of allocated sections (each {@link #SECTION_VOLUME} bytes). */
	public int sectionCount() {
		return allocated;
	}

	public interface SectionConsumer {
		void accept(int index, byte[] data);
	}

	/** Visits every allocated section in index order (used for saving). */
	public void forEachSection(SectionConsumer consumer) {
		byte[][] s = sections;
		if (s == null) {
			return;
		}
		for (int i = 0; i < s.length; i++) {
			if (s[i] != null) {
				consumer.accept(i, s[i]);
			}
		}
	}

	/** Puts a whole section in place (used when loading). */
	public void putSection(int index, byte[] data) {
		if (index < 0 || index >= SECTION_COUNT || data.length != SECTION_VOLUME) {
			throw new IllegalArgumentException("Bad 3D section " + index);
		}
		if (sections == null) {
			sections = new byte[SECTION_COUNT][];
		}
		if (sections[index] == null) {
			allocated++;
		}
		sections[index] = data;
		modCount++;
	}

	/** Replaces all content with a copy of another layer. */
	public void setFrom(VolumeLayer o) {
		byte[][] src = o.sections;
		if (src == null) {
			clear();
			return;
		}
		byte[][] copy = new byte[SECTION_COUNT][];
		for (int i = 0; i < src.length; i++) {
			if (src[i] != null) {
				copy[i] = src[i].clone();
			}
		}
		sections = copy;
		allocated = o.allocated;
		modCount++;
	}

	public long memoryBytes() {
		return sections == null ? 0L : SECTION_COUNT * 8L + (long) allocated * SECTION_VOLUME;
	}
}
