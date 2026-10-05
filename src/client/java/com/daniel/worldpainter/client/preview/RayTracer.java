package com.daniel.worldpainter.client.preview;

/**
 * Casts rays through the voxel scene and shades what they hit. One instance per render thread
 * (it keeps scratch state between calls).
 *
 * <p>Rays walk the columns of the map with a 2D grid walk; whole tiles and 16x16 cells are skipped
 * when the ray stays above everything in them, which keeps long rays across open land cheap. Inside
 * a column the ray is tested against the column's solid / fluid runs.
 */
final class RayTracer {
	// Hit faces (named after the side of the block that was hit)
	static final int UP = 0, DOWN = 1, WEST = 2, EAST = 3, NORTH = 4, SOUTH = 5;

	static final int WATER = 0xFF3F76E4;
	static final int LAVA = 0xFFE0661C;
	static final int SKY_TOP = 0xFF6D9EE0;
	static final int SKY_HORIZON = 0xFFB9D3EE;
	/** Seen when looking from inside the ground without coming out of it. */
	static final int ROCK_INSIDE = 0xFF2B2B31;

	// Sun direction (towards the sun): from the south-west, high.
	private static final double SUN_X, SUN_Y, SUN_Z;

	static {
		double x = -0.38, y = 0.82, z = 0.43;
		double l = Math.sqrt(x * x + y * y + z * z);
		SUN_X = x / l;
		SUN_Y = y / l;
		SUN_Z = z / l;
	}

	private static final int MAX_STEPS = 200_000;

	// Scene
	private PreviewScene.Snapshot scene;

	// Current ray
	private double ox, oy, oz, dx, dy, dz;
	private boolean ignoreWater, ignoreLava;
	/**
	 * The ray started inside ground (the camera dipped into a hill): that ground is looked through
	 * until the ray comes out into the open, like a cut-away.
	 */
	private boolean startInside;

	// Result of the last trace
	double hitT;
	int hitX, hitY, hitZ, face;
	byte hitType;
	SceneTile hitTile;
	int hitIndex;
	/** Top (exclusive) of the run that was hit. */
	int runTop;
	/** Where the ray entered water (NaN if it did not). */
	double waterT;
	int waterFace;

	private final short[] scratch = new short[6];

	void setScene(PreviewScene.Snapshot scene) {
		this.scene = scene;
	}

	/**
	 * Follows a ray. Unless water is ignored, the first water surface is remembered in {@link #waterT}
	 * and the ray continues to the ground below it.
	 *
	 * @return true if something solid (or lava) was hit; the hit is in the fields
	 */
	boolean trace(double ox, double oy, double oz, double dx, double dy, double dz, double maxT,
				  boolean ignoreWater, boolean ignoreLava) {
		this.ox = ox;
		this.oy = oy;
		this.oz = oz;
		this.dx = dx;
		this.dy = dy;
		this.dz = dz;
		this.ignoreWater = ignoreWater;
		this.ignoreLava = ignoreLava;
		this.waterT = Double.NaN;
		PreviewScene.Snapshot s = scene;
		if (s == null || s.top() == SceneTile.NONE) {
			return false;
		}

		// Clip to the round area that is shown.
		double tStart = 0, tEnd = maxT;
		double ex = ox - s.centerX(), ez = oz - s.centerZ();
		double r = s.radius();
		double a = dx * dx + dz * dz;
		if (a < 1e-12) {
			if (ex * ex + ez * ez > r * r) {
				return false;
			}
		} else {
			double b = ex * dx + ez * dz;
			double c = ex * ex + ez * ez - r * r;
			double disc = b * b - a * c;
			if (disc < 0) {
				return false;
			}
			double sq = Math.sqrt(disc);
			tStart = Math.max(tStart, (-b - sq) / a);
			tEnd = Math.min(tEnd, (-b + sq) / a);
		}
		// Nothing above the highest top of the scene.
		int top = s.top();
		if (oy >= top) {
			if (dy >= 0) {
				return false;
			}
			tStart = Math.max(tStart, (top - oy) / dy);
		} else if (dy > 0) {
			tEnd = Math.min(tEnd, (top - oy) / dy);
		}
		if (tStart >= tEnd) {
			return false;
		}
		startInside = tStart <= 0;

		int stepX = dx > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
		double invDx = dx != 0 ? 1 / dx : Double.POSITIVE_INFINITY;
		double invDz = dz != 0 ? 1 / dz : Double.POSITIVE_INFINITY;

		double t = tStart;
		int cx = (int) Math.floor(ox + dx * (t + 1e-7));
		int cz = (int) Math.floor(oz + dz * (t + 1e-7));
		int enterFace = Math.abs(dx) > Math.abs(dz) ? (stepX > 0 ? WEST : EAST) : (stepZ > 0 ? NORTH : SOUTH);
		int steps = 0;

		// Each step either skips a whole tile / 16x16 cell / 4x4 quad that the ray passes above, or
		// tests one column and moves to the next.
		int curTx = Integer.MIN_VALUE, curTz = Integer.MIN_VALUE;
		SceneTile tile = null;
		while (t < tEnd && steps++ < MAX_STEPS) {
			if ((cx >> SceneTile.SHIFT) != curTx || (cz >> SceneTile.SHIFT) != curTz) {
				if (!insideGrid(s, cx, cz)) {
					return false;
				}
				curTx = cx >> SceneTile.SHIFT;
				curTz = cz >> SceneTile.SHIFT;
				tile = s.tile(curTx, curTz);
			}
			int size;
			if (tile == null || tile.tileTop == SceneTile.NONE || above(t, tEnd, cx, cz, SceneTile.SIZE, tile.tileTop, invDx, invDz)) {
				size = SceneTile.SIZE;
			} else {
				int lx = cx & SceneTile.MASK, lz = cz & SceneTile.MASK;
				short cellTop = tile.cellTop[(lz >> SceneTile.CELL_SHIFT) * SceneTile.CELLS + (lx >> SceneTile.CELL_SHIFT)];
				if (cellTop == SceneTile.NONE || above(t, tEnd, cx, cz, 16, cellTop, invDx, invDz)) {
					size = 16;
				} else {
					short quadTop = tile.quadTop[(lz >> SceneTile.QUAD_SHIFT) * SceneTile.QUADS + (lx >> SceneTile.QUAD_SHIFT)];
					if (quadTop == SceneTile.NONE || above(t, tEnd, cx, cz, 4, quadTop, invDx, invDz)) {
						size = 4;
					} else {
						size = 1;
						double tOut = Math.min(exitT(cx, cz, 1, invDx, invDz), tEnd);
						if (column(tile, SceneTile.index(lx, lz), cx, cz, t, tOut, enterFace)) {
							return true;
						}
					}
				}
			}
			int x0 = cx & -size, z0 = cz & -size;
			double tx = dx > 0 ? (x0 + size - ox) * invDx : dx < 0 ? (x0 - ox) * invDx : Double.POSITIVE_INFINITY;
			double tz = dz > 0 ? (z0 + size - oz) * invDz : dz < 0 ? (z0 - oz) * invDz : Double.POSITIVE_INFINITY;
			double next = Math.max(Math.min(tx, tz), t);
			if (next >= tEnd) {
				return false;
			}
			t = next;
			if (size > 1) {
				// Skipped because the ray is above everything there: it is in the open.
				startInside = false;
			}
			if (tx <= tz) {
				cx = stepX > 0 ? x0 + size : x0 - 1;
				if (size > 1) {
					cz = Math.clamp((long) Math.floor(oz + dz * t), z0, z0 + size - 1);
				}
				enterFace = stepX > 0 ? WEST : EAST;
			} else {
				cz = stepZ > 0 ? z0 + size : z0 - 1;
				if (size > 1) {
					cx = Math.clamp((long) Math.floor(ox + dx * t), x0, x0 + size - 1);
				}
				enterFace = stepZ > 0 ? NORTH : SOUTH;
			}
		}
		return false;
	}

	/** True if the ray stays at or above {@code top} while it crosses the square of {@code size} around (cx, cz). */
	private boolean above(double t, double tEnd, int cx, int cz, int size, int top, double invDx, double invDz) {
		double exit = Math.min(exitT(cx & -size, cz & -size, size, invDx, invDz), tEnd);
		return minY(t, exit) >= top;
	}

	private static boolean insideGrid(PreviewScene.Snapshot s, int cx, int cz) {
		int tx = cx >> SceneTile.SHIFT, tz = cz >> SceneTile.SHIFT;
		return tx >= s.gridX0() && tz >= s.gridZ0() && tx < s.gridX0() + s.gridSize() && tz < s.gridZ0() + s.gridSize();
	}

	private double exitT(int x0, int z0, int size, double invDx, double invDz) {
		double tx = dx > 0 ? (x0 + size - ox) * invDx : dx < 0 ? (x0 - ox) * invDx : Double.POSITIVE_INFINITY;
		double tz = dz > 0 ? (z0 + size - oz) * invDz : dz < 0 ? (z0 - oz) * invDz : Double.POSITIVE_INFINITY;
		return Math.min(tx, tz);
	}

	/** Lowest height of the ray between two distances. */
	private double minY(double ta, double tb) {
		return dy >= 0 ? oy + dy * ta : oy + dy * tb;
	}

	/** Tests the ray against one column between {@code tIn} and {@code tOut}. */
	private boolean column(SceneTile tile, int i, int x, int z, double tIn, double tOut, int enterFace) {
		short[] runs = tile.runs == null ? null : tile.runs[i];
		int n;
		if (runs != null) {
			n = runs.length / 3;
		} else {
			short g = tile.ground[i];
			if (g == SceneTile.NONE) {
				startInside = false;
				return false;
			}
			short f = tile.fluidTop[i];
			int top = (f != SceneTile.NONE && f > g ? f : g) + 1;
			if (minY(tIn, tOut) >= top) {
				// The ray passes above this column.
				startInside = false;
				return false;
			}
			runs = scratch;
			runs[0] = SceneTile.BOTTOM;
			runs[1] = (short) (g + 1);
			runs[2] = SceneTile.SOLID;
			n = 1;
			if (f != SceneTile.NONE && f > g) {
				runs[3] = (short) (g + 1);
				runs[4] = (short) (f + 1);
				runs[5] = tile.fluidKind[i];
				n = 2;
			}
		}
		while (true) {
			double yIn = oy + dy * tIn;
			int found = -1;
			int foundFace = -1;
			double foundT = 0;
			int skip = -1;
			for (int k = 0; k < n; k++) {
				byte type = (byte) runs[k * 3 + 2];
				if (ignored(type)) {
					continue;
				}
				if (yIn >= runs[k * 3] && yIn < runs[k * 3 + 1]) {
					if (startInside && type != SceneTile.WATER) {
						// Still inside the ground the camera is in: look through it to where the
						// ray leaves this run (if that happens in this column).
						double leave = dy > 0 ? (runs[k * 3 + 1] - oy) / dy : dy < 0 ? (runs[k * 3] - oy) / dy : Double.POSITIVE_INFINITY;
						if (leave >= tOut) {
							return false;
						}
						skip = k;
						tIn = Math.max(tIn, leave);
						yIn = oy + dy * tIn;
						startInside = false;
						continue;
					}
					found = k;
					foundFace = enterFace;
					foundT = tIn;
					break;
				}
			}
			if (startInside) {
				startInside = false;
			}
			if (found < 0 && dy < 0) {
				int best = -1;
				for (int k = 0; k < n; k++) {
					if (k == skip || ignored((byte) runs[k * 3 + 2])) {
						continue;
					}
					if (runs[k * 3 + 1] <= yIn && (best < 0 || runs[k * 3 + 1] > runs[best * 3 + 1])) {
						best = k;
					}
				}
				if (best >= 0) {
					double th = (runs[best * 3 + 1] - oy) / dy;
					if (th <= tOut) {
						found = best;
						foundFace = UP;
						foundT = Math.max(th, tIn);
					}
				}
			} else if (found < 0 && dy > 0) {
				int best = -1;
				for (int k = 0; k < n; k++) {
					if (k == skip || ignored((byte) runs[k * 3 + 2])) {
						continue;
					}
					if (runs[k * 3] >= yIn && (best < 0 || runs[k * 3] < runs[best * 3])) {
						best = k;
					}
				}
				if (best >= 0) {
					double th = (runs[best * 3] - oy) / dy;
					if (th <= tOut) {
						found = best;
						foundFace = DOWN;
						foundT = Math.max(th, tIn);
					}
				}
			}
			if (found < 0) {
				return false;
			}
			byte type = (byte) runs[found * 3 + 2];
			if (type == SceneTile.WATER) {
				// Remember the water surface and look for the ground below it.
				if (Double.isNaN(waterT)) {
					waterT = foundT;
					waterFace = foundFace;
				}
				ignoreWater = true;
				continue;
			}
			hitT = foundT;
			face = foundFace;
			hitType = type;
			hitTile = tile;
			hitIndex = i;
			runTop = runs[found * 3 + 1];
			hitX = x;
			hitZ = z;
			hitY = switch (foundFace) {
				case UP -> runs[found * 3 + 1] - 1;
				case DOWN -> runs[found * 3];
				default -> (int) Math.floor(oy + dy * foundT);
			};
			if (hitY < runs[found * 3]) {
				hitY = runs[found * 3];
			} else if (hitY >= runTop) {
				hitY = runTop - 1;
			}
			return true;
		}
	}

	private boolean ignored(byte type) {
		return (type == SceneTile.WATER && ignoreWater) || (type == SceneTile.LAVA && ignoreLava);
	}

	/** True if the block at (x, y, z) is solid ground. */
	boolean solidAt(int x, int y, int z) {
		SceneTile t = scene.tile(x >> SceneTile.SHIFT, z >> SceneTile.SHIFT);
		if (t == null) {
			return false;
		}
		int i = SceneTile.index(x & SceneTile.MASK, z & SceneTile.MASK);
		short[] runs = t.runs == null ? null : t.runs[i];
		if (runs == null) {
			short g = t.ground[i];
			return g != SceneTile.NONE && y <= g;
		}
		for (int k = 0; k < runs.length; k += 3) {
			if (runs[k + 2] == SceneTile.SOLID && y >= runs[k] && y < runs[k + 1]) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ shading

	/** Color for a pixel whose ray starts at the eye and goes in direction (dx, dy, dz). */
	int shade(double ox, double oy, double oz, double dx, double dy, double dz, double maxT,
			  boolean shadows, double pixelAngle) {
		boolean hit = trace(ox, oy, oz, dx, dy, dz, maxT, false, false);
		double wt = waterT;
		int color;
		double fogX, fogZ;
		if (hit) {
			color = surfaceColor(pixelAngle, shadows);
			fogX = ox + dx * hitT;
			fogZ = oz + dz * hitT;
			if (!Double.isNaN(wt)) {
				double depth = hitT - wt;
				double alpha = Math.clamp(0.42 + depth * 0.045, 0.42, 0.9);
				alpha = Math.max(alpha, 1 - Math.abs(dy) * 1.6);
				color = mix(color, faceLight(WATER, waterFace), alpha);
				fogX = ox + dx * wt;
				fogZ = oz + dz * wt;
			}
		} else if (!Double.isNaN(wt)) {
			color = faceLight(WATER, waterFace);
			fogX = ox + dx * wt;
			fogZ = oz + dz * wt;
		} else if (startInside) {
			return ROCK_INSIDE;
		} else {
			return sky(scene.env(), dy);
		}
		PreviewScene.Snapshot s = scene;
		double hx = fogX - s.centerX(), hz = fogZ - s.centerZ();
		double d = Math.sqrt(hx * hx + hz * hz) / s.radius();
		if (d > 0.8) {
			double f = Math.clamp((d - 0.8) / 0.2, 0, 1);
			color = mix(color, s.env().skyHorizon(), f * f * (3 - 2 * f));
		}
		return color;
	}

	static int sky(PreviewScene.Environment env, double dy) {
		if (dy <= 0) {
			return env.skyHorizon();
		}
		return mix(env.skyHorizon(), env.skyTop(), Math.pow(Math.min(1, dy), 0.6));
	}

	private int surfaceColor(double pixelAngle, boolean shadows) {
		SceneTile tile = hitTile;
		int i = hitIndex;
		if (hitType == SceneTile.LAVA) {
			return face == UP ? LAVA : scale(LAVA, 0.85);
		}
		int ground = tile.ground[i];
		// Runs that reach the surface of the 2D terrain (or float above it) get grass, sand, ...;
		// runs deep below (cave floors, the far side of a cut) are stone.
		boolean surface = runTop - 1 >= ground - 2;
		int base;
		if (face == UP) {
			base = surface ? tile.topColor[i] : rock(hitY);
		} else if (face == DOWN) {
			base = rock(hitY);
		} else {
			int depth = runTop - 1 - hitY;
			if (!surface || depth >= 4) {
				base = rock(hitY);
			} else if (depth == 0) {
				base = mix(tile.topColor[i], tile.sideColor[i], 0.45);
			} else {
				base = tile.sideColor[i];
			}
		}
		if ((tile.flags[i] & SceneTile.PAINTED) == 0 && surface) {
			// Unpainted land is shown a little darker, like on the map.
			base = scale(base, 0.8);
		}
		// A little noise per block so flat areas still read as blocks.
		base = scale(base, 0.93 + 0.1 * hash01(hitX, hitY, hitZ));

		double light = faceLight(face);
		double hx = ox + dx * hitT, hy = oy + dy * hitT, hz = oz + dz * hitT;

		if (face == UP) {
			light *= ambientOcclusion(hx - hitX, hz - hitZ);
		}
		if (shadows && facesSun(face) && inShadow(hx, hy, hz)) {
			light *= 0.62;
		}

		// Block outlines when close enough to see them.
		double footprint = hitT * pixelAngle;
		if (footprint < 0.2) {
			double fx = hx - Math.floor(hx), fy = hy - Math.floor(hy), fz = hz - Math.floor(hz);
			double edge = switch (face) {
				case UP, DOWN -> Math.min(Math.min(fx, 1 - fx), Math.min(fz, 1 - fz));
				case WEST, EAST -> Math.min(Math.min(fy, 1 - fy), Math.min(fz, 1 - fz));
				default -> Math.min(Math.min(fx, 1 - fx), Math.min(fy, 1 - fy));
			};
			if (edge < Math.max(0.025, footprint * 0.6)) {
				light *= 0.87;
			}
		}
		return scale(base, light);
	}

	/** Stone (deepslate below Y 0) in the Overworld, netherrack in the Nether, end stone in the End. */
	private int rock(int y) {
		PreviewScene.Environment env = scene.env();
		return y < 0 ? env.deepRock() : env.rock();
	}

	/** Minecraft-like face brightness. */
	static double faceLight(int face) {
		return switch (face) {
			case UP -> 1.0;
			case DOWN -> 0.5;
			case NORTH, SOUTH -> 0.8;
			default -> 0.62;
		};
	}

	private static int faceLight(int color, int face) {
		return scale(color, face == UP ? 1.0 : 0.8);
	}

	private static boolean facesSun(int face) {
		return face == UP || face == WEST || face == SOUTH;
	}

	private boolean inShadow(double hx, double hy, double hz) {
		double nx = 0, ny = 0, nz = 0;
		switch (face) {
			case UP -> ny = 0.002;
			case WEST -> nx = -0.002;
			case SOUTH -> nz = 0.002;
			default -> {
			}
		}
		return traceShadow(hx + nx, hy + ny, hz + nz);
	}

	private boolean traceShadow(double px, double py, double pz) {
		// Save the primary hit: the shadow ray overwrites the hit fields.
		double sT = hitT;
		int sX = hitX, sY = hitY, sZ = hitZ, sFace = face, sIndex = hitIndex, sTop = runTop;
		byte sType = hitType;
		SceneTile sTile = hitTile;
		double sWater = waterT;
		int sWaterFace = waterFace;
		double sox = ox, soy = oy, soz = oz, sdx = dx, sdy = dy, sdz = dz;
		boolean shadow = trace(px, py, pz, SUN_X, SUN_Y, SUN_Z, 256, true, true);
		hitT = sT;
		hitX = sX;
		hitY = sY;
		hitZ = sZ;
		face = sFace;
		hitIndex = sIndex;
		runTop = sTop;
		hitType = sType;
		hitTile = sTile;
		waterT = sWater;
		waterFace = sWaterFace;
		ox = sox;
		oy = soy;
		oz = soz;
		dx = sdx;
		dy = sdy;
		dz = sdz;
		return shadow;
	}

	/** Darkens a top face next to higher blocks. {@code fx, fz} is the position inside the block (0..1). */
	private double ambientOcclusion(double fx, double fz) {
		int y = hitY + 1;
		double occ = 0;
		if (solidAt(hitX - 1, y, hitZ)) {
			occ += (1 - fx) * (1 - fx);
		}
		if (solidAt(hitX + 1, y, hitZ)) {
			occ += fx * fx;
		}
		if (solidAt(hitX, y, hitZ - 1)) {
			occ += (1 - fz) * (1 - fz);
		}
		if (solidAt(hitX, y, hitZ + 1)) {
			occ += fz * fz;
		}
		return 1 - Math.min(0.45, occ * 0.3);
	}

	// ------------------------------------------------------------------ color helpers

	static int mix(int a, int b, double t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	static int scale(int c, double f) {
		int r = Math.min(255, (int) (((c >> 16) & 255) * f));
		int g = Math.min(255, (int) (((c >> 8) & 255) * f));
		int b = Math.min(255, (int) ((c & 255) * f));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	static double hash01(int x, int y, int z) {
		long h = x * 0x9E3779B97F4A7C15L ^ y * 0x632BE59BD9B4E019L ^ z * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return (h >>> 11) * 0x1.0p-53;
	}
}
