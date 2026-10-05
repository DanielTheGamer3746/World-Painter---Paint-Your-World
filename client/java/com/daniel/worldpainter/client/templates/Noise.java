package com.daniel.worldpainter.client.templates;

/** Seeded 2D gradient (Perlin) noise with fractal helpers, used by the built-in templates. */
public final class Noise {
	private final int[] perm = new int[512];

	public Noise(long seed) {
		int[] p = new int[256];
		for (int i = 0; i < 256; i++) {
			p[i] = i;
		}
		java.util.Random r = new java.util.Random(seed);
		for (int i = 255; i > 0; i--) {
			int j = r.nextInt(i + 1);
			int t = p[i];
			p[i] = p[j];
			p[j] = t;
		}
		for (int i = 0; i < 512; i++) {
			perm[i] = p[i & 255];
		}
	}

	/** Noise in roughly -1..1. */
	public double noise(double x, double y) {
		int xi = (int) Math.floor(x) & 255;
		int yi = (int) Math.floor(y) & 255;
		double xf = x - Math.floor(x);
		double yf = y - Math.floor(y);
		double u = fade(xf), v = fade(yf);
		int aa = perm[perm[xi] + yi], ab = perm[perm[xi] + yi + 1];
		int ba = perm[perm[xi + 1] + yi], bb = perm[perm[xi + 1] + yi + 1];
		double x1 = lerp(grad(aa, xf, yf), grad(ba, xf - 1, yf), u);
		double x2 = lerp(grad(ab, xf, yf - 1), grad(bb, xf - 1, yf - 1), u);
		return lerp(x1, x2, v) * 1.414;
	}

	/** Fractal noise: several octaves, each twice as detailed and half as strong. */
	public double fbm(double x, double y, int octaves) {
		double sum = 0, amp = 1, norm = 0, freq = 1;
		for (int i = 0; i < octaves; i++) {
			sum += noise(x * freq + i * 17.3, y * freq - i * 9.1) * amp;
			norm += amp;
			amp *= 0.5;
			freq *= 2;
		}
		return sum / norm;
	}

	/** Ridged noise (sharp crests), 0..1. */
	public double ridged(double x, double y, int octaves) {
		double sum = 0, amp = 1, norm = 0, freq = 1;
		for (int i = 0; i < octaves; i++) {
			double n = 1 - Math.abs(noise(x * freq + i * 31.7, y * freq + i * 5.3));
			sum += n * n * amp;
			norm += amp;
			amp *= 0.5;
			freq *= 2;
		}
		return sum / norm;
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	private static double grad(int hash, double x, double y) {
		return switch (hash & 7) {
			case 0 -> x + y;
			case 1 -> -x + y;
			case 2 -> x - y;
			case 3 -> -x - y;
			case 4 -> x;
			case 5 -> -x;
			case 6 -> y;
			default -> -y;
		};
	}
}
