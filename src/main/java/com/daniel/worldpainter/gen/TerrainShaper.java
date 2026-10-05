package com.daniel.worldpainter.gen;

import com.daniel.worldpainter.data.FluidType;
import com.daniel.worldpainter.data.Layer;
import com.daniel.worldpainter.data.PaintTile;
import com.daniel.worldpainter.data.VolumeLayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.util.EnumSet;

/**
 * Applies the painted terrain height, water/lava, 3D edits and surface blocks to chunks while they
 * generate. Height, fluids and 3D edits are applied right after the noise fill (so surface rules, caves,
 * ores, trees and structures all run on the painted shape); surface blocks are applied after the
 * surface rules.
 */
public final class TerrainShaper {
	private static final EnumSet<Heightmap.Types> WG_HEIGHTMAPS = EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG);
	private static final int NO_FLUID = Integer.MIN_VALUE;

	private TerrainShaper() {
	}

	// ---- chunk generation ----

	public static void afterNoiseFill(NoiseBasedChunkGenerator generator, ChunkAccess chunk) {
		GenPaint paint = PaintBindings.get(generator);
		if (paint == null) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		int bx0 = pos.getMinBlockX();
		int bz0 = pos.getMinBlockZ();
		PaintTile tile = paint.world.getForBlock(bx0, bz0);
		if (tile != null) {
			// Painted biomes are written into the chunk here as well, so they stick even where the biome
			// source hooks are bypassed (the End's own biome source, mods like TerraBlender).
			ChunkBiomes.paint(paint, chunk, tile, bx0, bz0);
		}
		if (tile == null || (!tile.hasTerrainLayers() && !tile.hasVolume())) {
			return;
		}
		NoiseGeneratorSettings settings = generator.generatorSettings().value();
		BlockState ground = settings.defaultBlock();
		// Painted height and water only exist in dimensions that have them (not under the Nether's roof).
		boolean columns = paint.dimension.allows(Layer.HEIGHT) || paint.dimension.allows(Layer.FLUID);
		boolean changed = columns && tile.hasTerrainLayers() && applyColumns(tile, chunk, settings, bx0, bz0);
		if (tile.hasVolume() && applyVolume(tile, chunk, ground, bx0, bz0)) {
			changed = true;
		}
		if (changed) {
			Heightmap.primeHeightmaps(chunk, WG_HEIGHTMAPS);
		}
	}

	/** Painted height and water/lava, column by column. */
	private static boolean applyColumns(PaintTile tile, ChunkAccess chunk, NoiseGeneratorSettings settings, int bx0, int bz0) {
		BlockState ground = settings.defaultBlock();
		BlockState seaFluid = settings.defaultFluid();
		int seaLevel = settings.seaLevel();
		BlockState air = Blocks.AIR.defaultBlockState();
		int minY = chunk.getMinY();
		int maxY = minY + chunk.getHeight() - 1;
		boolean changed = false;

		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int i = PaintTile.indexForBlock(bx0 + lx, bz0 + lz);
				short h = tile.height.get(i);
				short f = tile.fluid.get(i);
				if (h == PaintTile.NO_HEIGHT && f == FluidType.NONE) {
					continue;
				}
				int oldTop = topSolid(chunk, lx, lz, minY);
				int target = h != PaintTile.NO_HEIGHT ? Math.clamp(h, minY, maxY) : oldTop;
				int fluidTop = fluidTop(f, h != PaintTile.NO_HEIGHT, target, seaLevel, minY, maxY);
				BlockState fluidState = fluidState(f, seaFluid);

				int from = Math.min(oldTop, target) + 1;
				int to = Math.min(maxY, Math.max(Math.max(oldTop, target), Math.max(fluidTop, seaLevel - 1)));
				for (int y = Math.max(from, minY); y <= to; y++) {
					BlockState desired;
					if (y <= target) {
						desired = ground;
					} else if (y <= fluidTop) {
						desired = fluidState;
					} else {
						desired = air;
					}
					LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
					BlockState current = section.getBlockState(lx, y & 15, lz);
					if (current != desired) {
						// Fluids already present below the old ground (aquifers) are kept; only the
						// part we are reshaping is rewritten.
						section.setBlockState(lx, y & 15, lz, desired, false);
						changed = true;
					}
				}
			}
		}
		return changed;
	}

	/**
	 * The 3D edits: sculpted blocks become ground (the surface rules that run next give them grass,
	 * sand, ... like any terrain) and carved blocks become air. Carving leaves water and lava alone.
	 */
	private static boolean applyVolume(PaintTile tile, ChunkAccess chunk, BlockState ground, int bx0, int bz0) {
		VolumeLayer volume = tile.volume;
		int sx = (bx0 & PaintTile.MASK) >> 4;
		int sz = (bz0 & PaintTile.MASK) >> 4;
		BlockState air = Blocks.AIR.defaultBlockState();
		int minY = chunk.getMinY();
		int maxY = minY + chunk.getHeight() - 1;
		boolean changed = false;
		for (int sy = 0; sy < VolumeLayer.SECTIONS_Y; sy++) {
			byte[] edits = volume.section(sx, sy, sz);
			if (edits == null) {
				continue;
			}
			int baseY = VolumeLayer.MIN_Y + (sy << 4);
			if (baseY > maxY || baseY + 15 < minY) {
				continue;
			}
			for (int v = 0; v < VolumeLayer.SECTION_VOLUME; v++) {
				byte e = edits[v];
				if (e == VolumeLayer.KEEP) {
					continue;
				}
				int y = baseY + (v >> 8);
				if (y < minY || y > maxY) {
					continue;
				}
				int lx = v & 15, lz = (v >> 4) & 15;
				LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
				BlockState current = section.getBlockState(lx, y & 15, lz);
				if (e == VolumeLayer.SOLID) {
					if (current != ground) {
						section.setBlockState(lx, y & 15, lz, ground, false);
						changed = true;
					}
				} else if (!current.isAir() && current.getFluidState().isEmpty()) {
					section.setBlockState(lx, y & 15, lz, air, false);
					changed = true;
				}
			}
		}
		return changed;
	}

	public static void afterSurface(NoiseBasedChunkGenerator generator, ChunkAccess chunk) {
		GenPaint paint = PaintBindings.get(generator);
		if (paint == null || !paint.dimension.allows(Layer.SURFACE)) {
			return;
		}
		ChunkPos pos = chunk.getPos();
		int bx0 = pos.getMinBlockX();
		int bz0 = pos.getMinBlockZ();
		PaintTile tile = paint.world.getForBlock(bx0, bz0);
		if (tile == null || tile.surface.isAll((short) 0)) {
			return;
		}
		int minY = chunk.getMinY();
		boolean changed = false;
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				String id = tile.surfaceAt(PaintTile.indexForBlock(bx0 + lx, bz0 + lz));
				if (id == null) {
					continue;
				}
				BlockState state = paint.block(id);
				if (state == null) {
					continue;
				}
				int top = topSolid(chunk, lx, lz, minY);
				if (top < minY) {
					continue;
				}
				LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(top));
				section.setBlockState(lx, top & 15, lz, state, false);
				changed = true;
			}
		}
		if (changed) {
			Heightmap.primeHeightmaps(chunk, WG_HEIGHTMAPS);
		}
	}

	// ---- queries used by structures, spawn search, etc. ----

	/** Painted base height (first free Y above ground/fluid), or null if this column is not height-painted. */
	public static Integer baseHeight(NoiseBasedChunkGenerator generator, int x, int z, Heightmap.Types type, LevelHeightAccessor level) {
		GenPaint paint = PaintBindings.get(generator);
		if (paint == null || !paint.dimension.allows(Layer.HEIGHT)) {
			return null;
		}
		PaintTile tile = paint.world.getForBlock(x, z);
		if (tile == null) {
			return null;
		}
		int i = PaintTile.indexForBlock(x, z);
		short h = tile.height.get(i);
		if (h == PaintTile.NO_HEIGHT) {
			return null;
		}
		int minY = level.getMinY();
		int maxY = minY + level.getHeight() - 1;
		int target = Math.clamp(h, minY, maxY);
		int seaLevel = generator.generatorSettings().value().seaLevel();
		int fluidTop = fluidTop(tile.fluid.get(i), true, target, seaLevel, minY, maxY);
		boolean countsFluid = type != Heightmap.Types.OCEAN_FLOOR && type != Heightmap.Types.OCEAN_FLOOR_WG;
		int top = countsFluid ? Math.max(target, fluidTop) : target;
		return withVolume(tile, x, z, top + 1, minY);
	}

	/**
	 * Height reported by vanilla generation for a column that is not height-painted, corrected for
	 * 3D edits there (a floating island on top, a pit carved from above).
	 */
	public static int adjustBaseHeight(NoiseBasedChunkGenerator generator, int x, int z, int firstFree, LevelHeightAccessor level) {
		GenPaint paint = PaintBindings.get(generator);
		if (paint == null) {
			return firstFree;
		}
		PaintTile tile = paint.world.getForBlock(x, z);
		if (tile == null || !tile.hasVolume()) {
			return firstFree;
		}
		return withVolume(tile, x, z, firstFree, level.getMinY());
	}

	/** Moves a height (first free Y above the top block) to account for sculpted and carved blocks. */
	private static int withVolume(PaintTile tile, int x, int z, int firstFree, int minY) {
		VolumeLayer volume = tile.volume;
		int lx = x & PaintTile.MASK, lz = z & PaintTile.MASK;
		if (!volume.columnHasEdits(lx, lz)) {
			return firstFree;
		}
		int top = firstFree - 1;
		int solid = volume.highest(lx, lz, VolumeLayer.SOLID);
		if (solid > top) {
			top = solid;
		}
		while (top >= minY && volume.get(lx, top, lz) == VolumeLayer.AIR) {
			top--;
		}
		return top + 1;
	}

	/** Painted block column, or null if this column is not height-painted. */
	public static NoiseColumn baseColumn(NoiseBasedChunkGenerator generator, int x, int z, LevelHeightAccessor level) {
		GenPaint paint = PaintBindings.get(generator);
		if (paint == null || !paint.dimension.allows(Layer.HEIGHT)) {
			return null;
		}
		PaintTile tile = paint.world.getForBlock(x, z);
		if (tile == null) {
			return null;
		}
		int i = PaintTile.indexForBlock(x, z);
		short h = tile.height.get(i);
		if (h == PaintTile.NO_HEIGHT) {
			return null;
		}
		NoiseGeneratorSettings settings = generator.generatorSettings().value();
		int minY = level.getMinY();
		int height = level.getHeight();
		int maxY = minY + height - 1;
		int target = Math.clamp(h, minY, maxY);
		short f = tile.fluid.get(i);
		int fluidTop = fluidTop(f, true, target, settings.seaLevel(), minY, maxY);
		BlockState fluidState = fluidState(f, settings.defaultFluid());
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState[] column = new BlockState[height];
		for (int y = minY; y <= maxY; y++) {
			column[y - minY] = y <= target ? settings.defaultBlock() : y <= fluidTop ? fluidState : air;
		}
		VolumeLayer volume = tile.volume;
		int lx = x & PaintTile.MASK, lz = z & PaintTile.MASK;
		if (volume.columnHasEdits(lx, lz)) {
			for (int y = Math.max(minY, VolumeLayer.MIN_Y); y <= Math.min(maxY, VolumeLayer.MAX_Y); y++) {
				byte e = volume.get(lx, y, lz);
				if (e == VolumeLayer.SOLID) {
					column[y - minY] = settings.defaultBlock();
				} else if (e == VolumeLayer.AIR && column[y - minY] == settings.defaultBlock()) {
					column[y - minY] = air;
				}
			}
		}
		return new NoiseColumn(minY, column);
	}

	// ---- helpers ----

	/** Highest block that is neither air nor a fluid, or minY - 1 if the column is empty. */
	private static int topSolid(ChunkAccess chunk, int lx, int lz, int minY) {
		int highestSection = chunk.getHighestFilledSectionIndex();
		if (highestSection < 0) {
			return minY - 1;
		}
		int startY = (chunk.getSectionYFromSectionIndex(highestSection) << 4) + 15;
		for (int y = startY; y >= minY; y--) {
			BlockState s = chunk.getSection(chunk.getSectionIndex(y)).getBlockState(lx, y & 15, lz);
			if (!s.isAir() && s.getFluidState().isEmpty()) {
				return y;
			}
		}
		return minY - 1;
	}

	/**
	 * Highest Y that should hold fluid. A painted fluid wins; otherwise a height-painted column
	 * below sea level fills with the normal sea fluid, like vanilla oceans.
	 */
	private static int fluidTop(short packed, boolean heightPainted, int target, int seaLevel, int minY, int maxY) {
		if (packed != FluidType.NONE) {
			if (FluidType.type(packed) == FluidType.DRY) {
				return NO_FLUID;
			}
			return Math.clamp(FluidType.level(packed), minY, maxY);
		}
		if (heightPainted && target < seaLevel - 1) {
			return seaLevel - 1;
		}
		return NO_FLUID;
	}

	private static BlockState fluidState(short packed, BlockState seaFluid) {
		if (packed == FluidType.NONE) {
			return seaFluid;
		}
		return switch (FluidType.type(packed)) {
			case WATER -> Blocks.WATER.defaultBlockState();
			case LAVA -> Blocks.LAVA.defaultBlockState();
			case DRY -> Blocks.AIR.defaultBlockState();
		};
	}
}
