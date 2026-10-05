package com.daniel.worldpainter.client.preview;

import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.existing.ExistingWorldLayer;
import com.daniel.worldpainter.client.map.BiomeColors;
import com.daniel.worldpainter.client.map.MapColors;
import com.daniel.worldpainter.client.map.SurfaceColors;
import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintDimension;
import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.PaintWorld;
import com.daniel.worldpainter.data.VolumeLayer;

import java.util.Arrays;
import java.util.List;

/**
 * Feeds the 3D preview from the painter: painted layers first, then the existing world (if shown),
 * and where neither is known a flat guess at the base height (empty space in the End). This mirrors
 * what world generation does with the design, including the 3D edits.
 */
public final class PaintedSceneSource implements PreviewScene.Source {
	private static final int DIRT = 0xFF866043;
	private static final int NETHERRACK = 0xFF6F3634;
	private static final int SOUL_SOIL = 0xFF4B3A2C;
	private static final int BASALT = 0xFF4E4E54;
	private static final int END_STONE = 0xFFD6D89C;
	private static final int SAND = 0xFFD8CC94;
	private static final int RED_SAND = 0xFFBA6A32;
	private static final int TERRACOTTA = 0xFFA45A3A;
	private static final int SEABED = 0xFF8E8467;
	private static final int GUESS_A = 0xFF6E7A66;
	private static final int GUESS_B = 0xFF66715F;
	private static final int NETHER_GUESS_A = 0xFF6A3532;
	private static final int NETHER_GUESS_B = 0xFF60302E;

	private final PainterState state;
	private final PaintDimension dim;
	private final int seaTop;
	private final byte seaKind;
	private final byte[] edits = new byte[VolumeLayer.HEIGHT];
	private final byte[] types = new byte[VolumeLayer.HEIGHT];

	public PaintedSceneSource(PainterState state) {
		this.state = state;
		this.dim = state.dimension;
		this.seaTop = dim.seaLevel - 1;
		this.seaKind = dim == PaintDimension.NETHER ? SceneTile.LAVA : SceneTile.WATER;
	}

	@Override
	public PreviewScene.Environment environment() {
		return switch (dim) {
			case OVERWORLD -> PreviewScene.Environment.OVERWORLD;
			case NETHER -> PreviewScene.Environment.NETHER;
			case END -> PreviewScene.Environment.END;
		};
	}

	@Override
	public long version(int tx, int tz) {
		long v = 17;
		if (PaintWorld.tileInWorld(tx, tz)) {
			PaintTile t = state.world.get(tx, tz);
			if (t != null) {
				v = v * 31 + System.identityHashCode(t);
				v = v * 31 + t.revision;
			}
			ExistingWorldLayer ex = state.showExisting ? state.existing : null;
			if (ex != null) {
				ExistingWorldLayer.Region r = ex.region(tx >> 1, tz >> 1, false);
				v = v * 31 + (r == null ? 0 : System.identityHashCode(r));
			}
		}
		v = v * 31 + (state.showExisting ? 1 : 0);
		v = v * 31 + state.baseHeight;
		return v;
	}

	@Override
	public SceneTile build(int tx, int tz, long version) {
		SceneTile st = new SceneTile(tx, tz, version);
		if (!PaintWorld.tileInWorld(tx, tz)) {
			Arrays.fill(st.ground, SceneTile.NONE);
			Arrays.fill(st.fluidTop, SceneTile.NONE);
			st.finish();
			return st;
		}
		PaintTile t = state.world.get(tx, tz);
		ExistingWorldLayer ex = state.showExisting ? state.existing : null;
		ExistingWorldLayer.Region region = ex == null ? null : ex.region(tx >> 1, tz >> 1, true);

		int[] biomeColors = t == null ? null : paletteColors(t.biomePalette.snapshot(), true);
		int[] surfaceColors = t == null ? null : paletteColors(t.surfacePalette.snapshot(), false);
		int[] sideColors = t == null ? null : paletteSides(t.biomePalette.snapshot());
		int[] exColors = new int[64];
		int[] exSides = new int[64];
		boolean volume = t != null && t.hasVolume();

		int bx0 = tx << PaintTile.SHIFT, bz0 = tz << PaintTile.SHIFT;
		for (int lz = 0; lz < PaintTile.SIZE; lz++) {
			for (int lx = 0; lx < PaintTile.SIZE; lx++) {
				int x = bx0 + lx, z = bz0 + lz;
				int i = PaintTile.index(lx, lz);
				if (!PaintWorld.inWorld(x, z)) {
					st.ground[i] = SceneTile.NONE;
					st.fluidTop[i] = SceneTile.NONE;
					continue;
				}
				short h = PaintTile.NO_HEIGHT, f = FluidType.NONE, b = 0, s = 0;
				if (t != null) {
					// Layers this dimension does not have are not generated either.
					h = dim.allows(Layer.HEIGHT) ? t.height.get(i) : PaintTile.NO_HEIGHT;
					f = dim.allows(Layer.FLUID) ? t.fluid.get(i) : FluidType.NONE;
					b = t.biome.get(i);
					s = dim.allows(Layer.SURFACE) ? t.surface.get(i) : 0;
				}
				short exFloor = ExistingWorldLayer.UNKNOWN, exSurface = ExistingWorldLayer.UNKNOWN, exBiome = 0;
				if (region != null) {
					exBiome = region.biomeIndex(x & 511, z & 511);
					if (dim.existingHeights) {
						exFloor = region.floor(x & 511, z & 511);
						exSurface = region.surface(x & 511, z & 511);
						if (dim.voidByDefault && exFloor < 1) {
							exFloor = ExistingWorldLayer.UNKNOWN;
						}
					}
				}

				boolean guess = false;
				boolean empty = false;
				int ground;
				if (h != PaintTile.NO_HEIGHT) {
					ground = Math.clamp(h, PainterState.MIN_Y, PainterState.MAX_Y);
				} else if (exFloor != ExistingWorldLayer.UNKNOWN) {
					ground = exFloor;
				} else if (dim.voidByDefault) {
					ground = Integer.MIN_VALUE;
					empty = true;
				} else {
					ground = state.baseHeight;
					guess = true;
				}

				// Water and lava, the same way world generation decides them.
				int fluidTop = SceneTile.NONE;
				byte kind = SceneTile.WATER;
				if (f != FluidType.NONE) {
					FluidType ft = FluidType.type(f);
					if (ft != FluidType.DRY) {
						fluidTop = Math.clamp(FluidType.level(f), PainterState.MIN_Y, PainterState.MAX_Y);
						kind = ft == FluidType.LAVA ? SceneTile.LAVA : SceneTile.WATER;
					}
				} else if (h != PaintTile.NO_HEIGHT || guess) {
					if (ground < seaTop) {
						fluidTop = seaTop;
						kind = seaKind;
					}
				} else if (dim == PaintDimension.OVERWORLD && exSurface != ExistingWorldLayer.UNKNOWN && exSurface > exFloor
						&& exSurface <= seaTop) {
					// Existing oceans and rivers (higher differences are mostly tree tops).
					fluidTop = exSurface;
				}
				if (empty || (fluidTop != SceneTile.NONE && fluidTop <= ground)) {
					fluidTop = SceneTile.NONE;
				}

				// Colors
				int top, side;
				if (b > 0 && biomeColors != null && b < biomeColors.length) {
					top = biomeColors[b];
					side = sideColors[b];
				} else if (exBiome > 0) {
					top = exColor(ex, exBiome, exColors, true);
					side = exColor(ex, exBiome, exSides, false);
				} else if (guess) {
					boolean a = (((x >> 4) ^ (z >> 4)) & 1) == 0;
					top = dim == PaintDimension.NETHER ? (a ? NETHER_GUESS_A : NETHER_GUESS_B) : a ? GUESS_A : GUESS_B;
					side = MapColors.scale(top, 0.85);
				} else {
					top = switch (dim) {
						case OVERWORLD -> MapColors.LAND_NO_BIOME;
						case NETHER -> NETHERRACK;
						case END -> END_STONE;
					};
					side = dim == PaintDimension.OVERWORLD ? DIRT : top;
				}
				if (s > 0 && surfaceColors != null && s < surfaceColors.length) {
					top = surfaceColors[s];
					side = MapColors.scale(top, 0.9);
				}
				if (fluidTop != SceneTile.NONE && kind == SceneTile.WATER) {
					top = MapColors.mix(top, SEABED, 0.6);
				}

				st.ground[i] = empty ? SceneTile.NONE : (short) ground;
				st.fluidTop[i] = (short) fluidTop;
				st.fluidKind[i] = kind;
				st.topColor[i] = top;
				st.sideColor[i] = side;
				byte flags = 0;
				boolean edited = volume && t.volume.columnHasEdits(lx, lz);
				if (h != PaintTile.NO_HEIGHT || f != FluidType.NONE || b != 0 || s != 0 || edited) {
					flags |= SceneTile.PAINTED;
				}
				if (guess) {
					flags |= SceneTile.GUESS;
				}
				st.flags[i] = flags;
				if (edited) {
					t.volume.column(lx, lz, edits);
					st.setRuns(i, ColumnRuns.build(ground, fluidTop, kind, edits, types));
				}
			}
		}
		st.finish();
		return st;
	}

	private static int[] paletteColors(List<String> ids, boolean biome) {
		int[] c = new int[ids.size()];
		for (int k = 1; k < ids.size(); k++) {
			c[k] = biome ? BiomeColors.color(ids.get(k)) : SurfaceColors.color(ids.get(k));
		}
		return c;
	}

	private static int[] paletteSides(List<String> ids) {
		int[] c = new int[ids.size()];
		for (int k = 1; k < ids.size(); k++) {
			c[k] = sideColor(ids.get(k));
		}
		return c;
	}

	private static int exColor(ExistingWorldLayer ex, short index, int[] cache, boolean top) {
		if (index < cache.length && cache[index] != 0) {
			return cache[index];
		}
		String name = ex.biomeName(index);
		int c = name == null ? (top ? MapColors.LAND_NO_BIOME : DIRT) : top ? BiomeColors.color(name) : sideColor(name);
		if (index < cache.length) {
			cache[index] = c;
		}
		return c;
	}

	/** What the few blocks under the surface look like in a biome. */
	static int sideColor(String biome) {
		if (biome == null) {
			return DIRT;
		}
		if (biome.contains("soul_sand")) {
			return SOUL_SOIL;
		}
		if (biome.contains("basalt")) {
			return BASALT;
		}
		if (biome.contains("nether") || biome.contains("crimson") || biome.contains("warped")) {
			return NETHERRACK;
		}
		if (biome.startsWith("minecraft:end") || biome.contains("end_") || biome.equals("minecraft:the_end")) {
			return END_STONE;
		}
		if (biome.contains("badlands")) {
			return biome.contains("wooded") ? DIRT : TERRACOTTA;
		}
		if (biome.contains("desert") || biome.contains("beach") || biome.contains("ocean") || biome.contains("river")) {
			return biome.contains("snowy") ? DIRT : biome.contains("red") ? RED_SAND : SAND;
		}
		return DIRT;
	}
}
