package com.daniel.worldpainter.util;

import com.daniel.worldpainter.util.MathUtil;

/** {@code Math.clamp} arrived in Java 21; Beta 1.7.3 mods are built for Java 17. Same overloads. */
public final class MathUtil {
	private MathUtil() {
	}

	public static int clamp(long value, int min, int max) {
		if (min > max) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return (int) Math.min(max, Math.max(value, min));
	}

	public static double clamp(double value, double min, double max) {
		if (min > max) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return Math.min(max, Math.max(value, min));
	}

	public static float clamp(float value, float min, float max) {
		if (min > max) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return Math.min(max, Math.max(value, min));
	}
}
