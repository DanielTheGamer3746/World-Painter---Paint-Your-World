package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.data.BetaBlocks;
import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.VolumeLayer;
import com.daniel.worldpainter.util.MathUtil;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Applies the painted terrain height, water/lava, 3D edits and surface blocks to Beta chunks while
 * they generate. Beta builds a chunk in a plain array of block ids ({@code x << 11 | z << 7 | y}):
 * first stone, water and ice ("terrain"), then grass, dirt, sand and bedrock ("surfaces"), then
 * caves. Height, fluids and 3D edits are applied after the terrain step, so surfaces and caves are
 * made on the painted shape; painted surface blocks are applied after the surface step.
 */
public final class TerrainShaper {
	public static final int HEIGHT = 128;
	public static final int MAX_Y = HEIGHT - 1;
	/** Beta's sea: water up to Y 63 (Y 64 is the first air block above the ocean). */
	public static final int SEA_LEVEL = 64;
	private static final int NO_FLUID = Integer.MIN_VALUE;

	private TerrainShaper() {
	}

	public static int index(int lx, int y, int lz) {
		return lx << 11 | lz << 7 | y;
	}

	/** After Beta's terrain step. {@code temperatures} is the chunk's (painted) temperature map. */
	public static void afterTerrain(World world, int cx, int cz, byte[] blocks, double[] temperatures) {
		GenPaint paint = PaintBindings.get(world);
		// Only Beta's own 128 block high layout (StationAPI can make worlds of other heights).
		if (paint == null || blocks == null || blocks.length != 16 * 16 * HEIGHT) {
			return;
		}
		int bx0 = cx << 4, bz0 = cz << 4;
		PaintTile tile = paint.world.getForBlock(bx0, bz0);
		if (tile == null) {
			return;
		}
		if (tile.hasTerrainLayers() && paint.dimension.allows(Layer.HEIGHT)) {
			applyColumns(tile, blocks, temperatures, bx0, bz0);
		}
		if (tile.hasVolume()) {
			applyVolume(tile, blocks, bx0, bz0);
		}
	}

	/** Painted height and water/lava, column by column. */
	private static void applyColumns(PaintTile tile, byte[] blocks, double[] temperatures, int bx0, int bz0) {
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int i = PaintTile.indexForBlock(bx0 + lx, bz0 + lz);
				short h = tile.height.get(i);
				short f = tile.fluid.get(i);
				if (h == PaintTile.NO_HEIGHT && f == FluidType.NONE) {
					continue;
				}
				int oldTop = topSolid(blocks, lx, lz);
				int target = h != PaintTile.NO_HEIGHT ? MathUtil.clamp(h, 0, MAX_Y) : oldTop;
				int fluidTop = fluidTop(f, h != PaintTile.NO_HEIGHT, target);
				boolean sea = f == FluidType.NONE;
				int fluid = fluidBlock(f);
				// Beta freezes the top of the sea where it is cold (painted cold biomes included).
				int t = lx * 16 + lz;
				boolean frozen = sea && temperatures != null && t < temperatures.length && temperatures[t] < 0.5;

				int from = Math.max(0, Math.min(oldTop, target) + 1);
				int to = Math.min(MAX_Y, Math.max(Math.max(oldTop, target), Math.max(fluidTop, SEA_LEVEL - 1)));
				for (int y = from; y <= to; y++) {
					int desired;
					if (y <= target) {
						desired = BetaBlocks.STONE;
					} else if (y <= fluidTop) {
						desired = frozen && y == SEA_LEVEL - 1 ? BetaBlocks.ICE : fluid;
					} else {
						desired = BetaBlocks.AIR;
					}
					blocks[index(lx, y, lz)] = (byte) desired;
				}
			}
		}
	}

	/** The 3D edits: sculpted blocks become stone (Beta's surface step covers them), carved ones air. */
	private static void applyVolume(PaintTile tile, byte[] blocks, int bx0, int bz0) {
		VolumeLayer volume = tile.volume;
		int sx = (bx0 & PaintTile.MASK) >> 4;
		int sz = (bz0 & PaintTile.MASK) >> 4;
		for (int sy = 0; sy < VolumeLayer.SECTIONS_Y; sy++) {
			byte[] edits = volume.section(sx, sy, sz);
			if (edits == null) {
				continue;
			}
			int baseY = VolumeLayer.MIN_Y + (sy << 4);
			for (int v = 0; v < VolumeLayer.SECTION_VOLUME; v++) {
				byte e = edits[v];
				if (e == VolumeLayer.KEEP) {
					continue;
				}
				int y = baseY + (v >> 8);
				if (y < 0 || y > MAX_Y) {
					continue;
				}
				int idx = index(v & 15, y, (v >> 4) & 15);
				int current = blocks[idx] & 255;
				if (e == VolumeLayer.SOLID) {
					blocks[idx] = (byte) BetaBlocks.STONE;
				} else if (current != BetaBlocks.AIR && !BetaBlocks.isFluid(current) && current != BetaBlocks.ICE) {
					// Carving leaves water, lava and sea ice alone.
					blocks[idx] = (byte) BetaBlocks.AIR;
				}
			}
		}
	}

	/** After Beta's surface step: painted top blocks. */
	public static void afterSurface(World world, int cx, int cz, byte[] blocks) {
		GenPaint paint = PaintBindings.get(world);
		if (paint == null || blocks == null || blocks.length != 16 * 16 * HEIGHT || !paint.dimension.allows(Layer.SURFACE)) {
			return;
		}
		int bx0 = cx << 4, bz0 = cz << 4;
		PaintTile tile = paint.world.getForBlock(bx0, bz0);
		if (tile == null || tile.surface.isAll((short) 0)) {
			return;
		}
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				BetaBlocks.Surface s = paint.block(tile.surfaceAt(PaintTile.indexForBlock(bx0 + lx, bz0 + lz)));
				if (s == null) {
					continue;
				}
				int top = topSolid(blocks, lx, lz);
				if (top > 0) {
					blocks[index(lx, top, lz)] = (byte) s.block();
				}
			}
		}
	}

	/**
	 * After the chunk was made: block variants (wool colors, wood types) live in a separate array of
	 * the finished chunk, so they are set here, where the painted block survived the caves.
	 */
	public static void finishChunk(World world, Chunk chunk) {
		GenPaint paint = PaintBindings.get(world);
		if (paint == null || chunk == null || !paint.dimension.allows(Layer.SURFACE)) {
			return;
		}
		int bx0 = chunk.x << 4, bz0 = chunk.z << 4;
		PaintTile tile = paint.world.getForBlock(bx0, bz0);
		if (tile == null || tile.surface.isAll((short) 0)) {
			return;
		}
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				BetaBlocks.Surface s = paint.block(tile.surfaceAt(PaintTile.indexForBlock(bx0 + lx, bz0 + lz)));
				if (s == null || s.meta() == 0) {
					continue;
				}
				// Through the chunk's methods: StationAPI's chunks keep their blocks elsewhere.
				for (int y = MAX_Y; y > 0; y--) {
					int id = chunk.getBlockId(lx, y, lz);
					if (id == s.block()) {
						chunk.setBlockMeta(lx, y, lz, s.meta());
						break;
					}
					if (id != BetaBlocks.AIR && !BetaBlocks.isFluid(id) && id != BetaBlocks.ICE) {
						break;
					}
				}
			}
		}
	}

	/** Highest block that is neither air nor a fluid nor sea ice, or -1 if the column is empty. */
	public static int topSolid(byte[] blocks, int lx, int lz) {
		int base = index(lx, 0, lz);
		for (int y = MAX_Y; y >= 0; y--) {
			int b = blocks[base + y] & 255;
			if (b != BetaBlocks.AIR && !BetaBlocks.isFluid(b) && b != BetaBlocks.ICE) {
				return y;
			}
		}
		return -1;
	}

	/**
	 * Highest Y that should hold fluid. A painted fluid wins; otherwise a height-painted column
	 * below sea level fills with water like Beta's oceans.
	 */
	private static int fluidTop(short packed, boolean heightPainted, int target) {
		if (packed != FluidType.NONE) {
			if (FluidType.type(packed) == FluidType.DRY) {
				return NO_FLUID;
			}
			return MathUtil.clamp(FluidType.level(packed), 0, MAX_Y);
		}
		if (heightPainted && target < SEA_LEVEL - 1) {
			return SEA_LEVEL - 1;
		}
		return NO_FLUID;
	}

	private static int fluidBlock(short packed) {
		if (packed == FluidType.NONE) {
			return BetaBlocks.WATER;
		}
		switch (FluidType.type(packed)) {
			case LAVA:
				return BetaBlocks.LAVA;
			case DRY:
				return BetaBlocks.AIR;
			default:
				return BetaBlocks.WATER;
		}
	}
}
