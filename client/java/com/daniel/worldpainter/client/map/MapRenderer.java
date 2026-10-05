package com.daniel.worldpainter.client.map;

import com.daniel.worldpainter.WorldPainter;
import com.daniel.worldpainter.client.edit.PainterState;
import com.daniel.worldpainter.client.existing.ExistingWorldLayer;
import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.PaintWorld;
import com.daniel.worldpainter.data.TileKey;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Draws the map into a texture, one texture pixel per screen pixel (capped for very large windows).
 * It only redraws when the view or the painting changed.
 */
public final class MapRenderer implements AutoCloseable {
	private static final Identifier TEXTURE_ID = WorldPainter.id("painter_map");
	private static final int MAX_BUFFER_WIDTH = 1600;
	private static final double COARSE_BLOCKS_PER_PIXEL = 16;
	private static final double LOAD_EXISTING_BLOCKS_PER_PIXEL = 4;
	private static final int VOLUME_TINT = 0xFFB27BE8;

	private DynamicTexture texture;
	private int texWidth;
	private int texHeight;
	private boolean dirty = true;

	private double lastCenterX = Double.NaN, lastCenterZ = Double.NaN;
	private int lastZoom = Integer.MIN_VALUE, lastChanges = -1;
	private PainterState.ViewMode lastMode;
	private boolean lastExisting;

	private int[] row;
	private int[] prevRow;

	public void invalidate() {
		dirty = true;
	}

	/** Redraws the texture if anything changed. */
	public void update(PainterState s, MapView v) {
		if (v.width <= 0 || v.height <= 0) {
			return;
		}
		double scale = Math.min(v.guiScale, MAX_BUFFER_WIDTH / (double) v.width);
		int w = Math.max(1, (int) Math.round(v.width * scale));
		int h = Math.max(1, (int) Math.round(v.height * scale));
		if (texture == null || w != texWidth || h != texHeight) {
			allocate(w, h);
		}
		boolean existingChanged = s.existing != null && s.existing.pollChanged();
		int changes = s.session.changeCount();
		if (!dirty && !existingChanged && changes == lastChanges && v.centerX == lastCenterX && v.centerZ == lastCenterZ
				&& v.zoom == lastZoom && s.viewMode == lastMode && s.showExisting == lastExisting) {
			return;
		}
		dirty = false;
		lastChanges = changes;
		lastCenterX = v.centerX;
		lastCenterZ = v.centerZ;
		lastZoom = v.zoom;
		lastMode = s.viewMode;
		lastExisting = s.showExisting;
		draw(s, v);
	}

	public void blit(GuiGraphicsExtractor g, MapView v) {
		if (texture == null) {
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, v.left, v.top, 0f, 0f, v.width, v.height, texWidth, texHeight, texWidth, texHeight);
	}

	private void allocate(int w, int h) {
		Minecraft mc = Minecraft.getInstance();
		if (texture != null) {
			mc.getTextureManager().release(TEXTURE_ID);
		}
		texture = new DynamicTexture(() -> "World Painter map", w, h, true);
		mc.getTextureManager().register(TEXTURE_ID, texture);
		texWidth = w;
		texHeight = h;
		row = new int[w];
		prevRow = new int[w];
		dirty = true;
	}

	private void draw(PainterState s, MapView v) {
		NativeImage img = texture.getPixels();
		if (img == null) {
			return;
		}
		double bpp = v.blocksPerGuiPixel() * v.width / texWidth;
		double originX = v.centerX - texWidth / 2.0 * bpp;
		double originZ = v.centerZ - texHeight / 2.0 * bpp;
		boolean coarse = bpp >= COARSE_BLOCKS_PER_PIXEL;
		int step = Math.max(1, (int) Math.round(bpp));
		int prevBz = Integer.MIN_VALUE;
		for (int py = 0; py < texHeight; py++) {
			int bz = (int) Math.floor(originZ + (py + 0.5) * bpp);
			if (bz == prevBz && !coarse) {
				// Zoomed in: this screen row shows the same blocks as the one above.
				for (int px = 0; px < texWidth; px++) {
					img.setPixel(px, py, prevRow[px]);
				}
				continue;
			}
			int prevBx = Integer.MIN_VALUE;
			int prevColor = 0;
			for (int px = 0; px < texWidth; px++) {
				int bx = (int) Math.floor(originX + (px + 0.5) * bpp);
				int color;
				if (bx == prevBx) {
					color = prevColor;
				} else {
					color = coarse ? coarseColor(s, bx, bz, bpp) : fineColor(s, bx, bz, bpp, step);
					prevBx = bx;
					prevColor = color;
				}
				row[px] = color;
				img.setPixel(px, py, color);
			}
			int[] t = prevRow;
			prevRow = row;
			row = t;
			prevBz = bz;
		}
		texture.upload();
	}

	// ---- zoomed out: one color per tile / region ----

	private int coarseColor(PainterState s, int x, int z, double bpp) {
		if (!PaintWorld.inWorld(x, z)) {
			return MapColors.OUTSIDE_WORLD;
		}
		PaintWorld world = s.world;
		long key = TileKey.forBlock(x, z);
		if (world.isLoaded(key)) {
			PaintTile t = world.get(TileKey.x(key), TileKey.z(key));
			if (t != null) {
				int c = summary(world, t);
				if (c != 0) {
					return c;
				}
			}
		} else if (world.exists(key)) {
			Integer c = world.storedSummary(key);
			if (c != null && c != 0) {
				return c;
			}
			return 0xFF9A7ACC;
		}
		ExistingWorldLayer ex = s.existing;
		if (ex != null && s.showExisting) {
			int rx = x >> 9, rz = z >> 9;
			ExistingWorldLayer.Region r = ex.region(rx, rz, false);
			if (r != null && r.averageColor != 0) {
				return MapColors.scale(r.averageColor, 0.8);
			}
			if (ex.hasRegion(rx, rz)) {
				return MapColors.EXPLORED;
			}
		}
		return MapColors.background(x, z, bpp);
	}

	private static int summary(PaintWorld world, PaintTile t) {
		if (t.summaryRevision != t.revision) {
			t.summaryColor = MapColors.summarize(t);
			t.summaryRevision = t.revision;
			world.putSummary(TileKey.of(t.tx, t.tz), t.summaryColor);
		}
		return t.summaryColor;
	}

	// ---- zoomed in: real per-block colors ----

	private int fineColor(PainterState s, int x, int z, double bpp, int step) {
		if (!PaintWorld.inWorld(x, z)) {
			return MapColors.OUTSIDE_WORLD;
		}
		PaintTile t = s.tileAt(x, z);
		int i = PaintTile.indexForBlock(x, z);
		String biome = null, surface = null;
		short h = PaintTile.NO_HEIGHT, f = FluidType.NONE;
		if (t != null) {
			biome = t.biomeAt(i);
			surface = t.surfaceAt(i);
			h = t.height.get(i);
			f = t.fluid.get(i);
		}

		// What the existing world has here (if shown and loaded).
		ExistingWorldLayer.Region region = null;
		ExistingWorldLayer ex = s.existing;
		if (ex != null && s.showExisting) {
			region = ex.region(x >> 9, z >> 9, bpp <= LOAD_EXISTING_BLOCKS_PER_PIXEL);
		}
		short exSurface = ExistingWorldLayer.UNKNOWN, exFloor = ExistingWorldLayer.UNKNOWN;
		String exBiome = null;
		if (region != null) {
			exBiome = ex.biomeName(region.biomeIndex(x & 511, z & 511));
			// Saved heights mean nothing under the Nether's roof, and nothing in the End's void.
			if (s.dimension.existingHeights) {
				exSurface = region.surface(x & 511, z & 511);
				exFloor = region.floor(x & 511, z & 511);
				if (s.dimension.voidByDefault && exFloor < 1) {
					exSurface = exFloor = ExistingWorldLayer.UNKNOWN;
				}
			}
		}
		boolean painted = biome != null || h != PaintTile.NO_HEIGHT || surface != null || f != FluidType.NONE;

		return switch (s.viewMode) {
			case BIOME -> biome != null ? BiomeColors.color(biome)
					: exBiome != null ? MapColors.scale(BiomeColors.color(exBiome), 0.45)
					: MapColors.background(x, z, bpp);
			case SURFACE -> surface != null ? SurfaceColors.color(surface) : MapColors.scale(MapColors.background(x, z, bpp), 0.9);
			case HEIGHT -> {
				if (h != PaintTile.NO_HEIGHT) {
					int g = MapColors.heightGray(h);
					yield h < MapColors.SEA_LEVEL - 1 ? MapColors.mix(g, MapColors.WATER, 0.35) : g;
				}
				if (exFloor != ExistingWorldLayer.UNKNOWN) {
					yield MapColors.scale(MapColors.heightGray(exFloor), 0.5);
				}
				yield MapColors.background(x, z, bpp);
			}
			case FLUID -> {
				if (f != FluidType.NONE) {
					FluidType ft = FluidType.type(f);
					yield ft == FluidType.WATER ? MapColors.WATER : ft == FluidType.LAVA ? MapColors.LAVA : MapColors.DRY;
				}
				yield MapColors.scale(MapColors.background(x, z, bpp), 0.9);
			}
			case COMPOSITE -> {
				int c = composite(s, x, z, step, painted, biome, surface, h, f, exBiome, exSurface, exFloor, region, ex);
				// Columns with 3D edits (caves, overhangs, floating islands) get a violet tint.
				if (t != null && t.hasVolume() && t.volume.columnHasEdits(x & PaintTile.MASK, z & PaintTile.MASK)) {
					c = MapColors.mix(c, VOLUME_TINT, 0.38);
				}
				yield c;
			}
		};
	}

	private int composite(PainterState s, int x, int z, int step, boolean painted, String biome, String surface, short h, short f,
						  String exBiome, short exSurface, short exFloor, ExistingWorldLayer.Region region, ExistingWorldLayer ex) {
		int c;
		if (biome != null) {
			c = BiomeColors.color(biome);
		} else if (exBiome != null) {
			c = BiomeColors.color(exBiome);
		} else if (h != PaintTile.NO_HEIGHT || surface != null) {
			c = MapColors.LAND_NO_BIOME;
		} else if (!painted && exFloor == ExistingWorldLayer.UNKNOWN) {
			return MapColors.background(x, z, step);
		} else {
			c = MapColors.LAND_NO_BIOME;
		}
		if (surface != null) {
			c = MapColors.mix(c, SurfaceColors.color(surface), 0.6);
		}

		// Ground height: painted, else existing.
		int ground = h != PaintTile.NO_HEIGHT ? h : exFloor != ExistingWorldLayer.UNKNOWN ? exFloor : Integer.MIN_VALUE;
		if (ground != Integer.MIN_VALUE) {
			int west = groundAt(s, x - step, z, region, ex);
			int north = groundAt(s, x, z - step, region, ex);
			double shade = 1.0;
			if (west != Integer.MIN_VALUE && north != Integer.MIN_VALUE) {
				double slope = ((ground - west) + (ground - north)) / (double) step;
				shade += Math.clamp(slope * 0.12, -0.4, 0.4);
			}
			shade *= 0.88 + Math.clamp((ground - 63) / 300.0, -0.15, 0.2);
			c = MapColors.scale(c, shade);
		}

		// Water / lava on top.
		int top = MapColors.fluidTop(f, h);
		if (top != Integer.MIN_VALUE) {
			if (ground == Integer.MIN_VALUE || top > ground) {
				boolean lava = f != FluidType.NONE && FluidType.type(f) == FluidType.LAVA;
				c = MapColors.fluid(c, lava, ground == Integer.MIN_VALUE ? 4 : top - ground);
			}
		} else if (h == PaintTile.NO_HEIGHT && f == FluidType.NONE && exSurface != ExistingWorldLayer.UNKNOWN
				&& exFloor != ExistingWorldLayer.UNKNOWN && exSurface > exFloor) {
			c = MapColors.fluid(c, false, exSurface - exFloor);
		}

		if (!painted) {
			// Unpainted existing terrain is shown a little darker so painted areas stand out.
			c = MapColors.scale(c, 0.72);
		}
		return c;
	}

	private static int groundAt(PainterState s, int x, int z, ExistingWorldLayer.Region region, ExistingWorldLayer ex) {
		short h = s.paintedHeight(x, z);
		if (h != PaintTile.NO_HEIGHT) {
			return h;
		}
		if (ex != null && s.showExisting) {
			short fl = s.existingHeight(x, z);
			if (fl != ExistingWorldLayer.UNKNOWN) {
				return fl;
			}
		}
		return Integer.MIN_VALUE;
	}

	@Override
	public void close() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(TEXTURE_ID);
			texture = null;
		}
	}
}
