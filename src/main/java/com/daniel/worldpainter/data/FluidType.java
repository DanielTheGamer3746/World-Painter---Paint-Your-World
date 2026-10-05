package com.daniel.worldpainter.data;

import com.daniel.worldpainter.util.MathUtil;

/**
 * What a painted fluid pixel does. The fluid layer stores type and level together in one short,
 * see {@link #pack(FluidType, int)}.
 */
public enum FluidType {
	WATER,
	LAVA,
	/** Removes water/lava above the ground, e.g. a dry valley below sea level. */
	DRY;

	public static final short NONE = Short.MIN_VALUE;
	private static final FluidType[] VALUES = values();

	public static short pack(FluidType type, int level) {
		int l = MathUtil.clamp(level, -2048, 2047) + 2048;
		return (short) ((l << 2) | type.ordinal());
	}

	public static FluidType type(short packed) {
		return VALUES[packed & 3];
	}

	public static int level(short packed) {
		return ((packed >> 2) & 0xFFF) - 2048;
	}
}
