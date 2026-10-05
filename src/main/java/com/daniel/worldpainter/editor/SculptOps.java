package com.daniel.worldpainter.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes 3D sculpting visible in the world right away while the painter's 3D view is open: sculpted
 * blocks become ground (topped like the ground around them: grass, sand, nylium, ...), carved blocks
 * become air. The design records the same change, so regenerating the area later gives the same shape
 * with full decoration (trees, ores). Server thread only.
 */
public final class SculptOps {
	/** Send to clients, no neighbour updates (keeps sand and water from flowing while sculpting). */
	private static final int FLAGS = 2;

	private SculptOps() {
	}

	// ---- block positions packed in a long (26 bits x, 26 bits z, 12 bits y) ----

	public static long pack(int x, int y, int z) {
		return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
	}

	public static int x(long p) {
		return (int) (p >> 38);
	}

	public static int z(long p) {
		return (int) (p << 26 >> 38);
	}

	public static int y(long p) {
		return (int) (p << 52 >> 52);
	}

	/**
	 * Adds ground where there is air or water ({@code add}), or carves ground to air. Returns the
	 * changes, for undo.
	 */
	public static List<EditorOps.BlockChange> apply(ServerLevel level, long[] positions, int count, boolean add) {
		List<EditorOps.BlockChange> changes = new ArrayList<>();
		int minY = level.getMinY(), maxY = level.getMaxY();
		BlockState air = Blocks.AIR.defaultBlockState();
		if (!add) {
			for (int i = 0; i < count; i++) {
				long p = positions[i];
				int y = y(p);
				if (y < minY || y > maxY) {
					continue;
				}
				BlockPos pos = new BlockPos(x(p), y, z(p));
				BlockState cur = level.getBlockState(pos);
				if (!cur.isAir() && cur.getFluidState().isEmpty() && !cur.is(Blocks.BEDROCK)) {
					set(level, pos, cur, air, changes);
				}
			}
			return changes;
		}

		BlockState ground = groundBlock(level);
		// What each column is topped with now, so new ground can be topped the same way.
		Map<Long, BlockState> surface = new HashMap<>();
		Map<Long, List<Integer>> placed = new HashMap<>();
		Set<Long> done = new HashSet<>();
		for (int i = 0; i < count; i++) {
			long p = positions[i];
			int x = x(p), y = y(p), z = z(p);
			if (y < minY || y > maxY || !done.add(p)) {
				continue;
			}
			long col = columnKey(x, z);
			if (!surface.containsKey(col)) {
				int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				surface.put(col, top >= minY ? level.getBlockState(new BlockPos(x, top, z)) : air);
			}
			BlockPos pos = new BlockPos(x, y, z);
			BlockState cur = level.getBlockState(pos);
			if (cur.isAir() || !cur.getFluidState().isEmpty()) {
				set(level, pos, cur, ground, changes);
				placed.computeIfAbsent(col, k -> new ArrayList<>()).add(y);
			}
		}
		// Top the new ground: grass on top and dirt under it where the column had grass, and so on.
		for (Map.Entry<Long, List<Integer>> e : placed.entrySet()) {
			BlockState skin = surface.get(e.getKey());
			BlockState top = topBlock(skin);
			if (top == null) {
				continue;
			}
			BlockState under = underBlock(skin);
			List<Integer> ys = e.getValue();
			Set<Integer> mine = new HashSet<>(ys);
			int x = (int) (e.getKey() >> 32), z = (int) (long) e.getKey();
			for (int y : ys) {
				BlockPos above = new BlockPos(x, y + 1, z);
				if (!level.getBlockState(above).isAir()) {
					continue;
				}
				BlockPos pos = new BlockPos(x, y, z);
				set(level, pos, level.getBlockState(pos), top, changes);
				for (int d = 1; d <= 3 && under != null && mine.contains(y - d); d++) {
					BlockPos below = new BlockPos(x, y - d, z);
					set(level, below, level.getBlockState(below), under, changes);
				}
			}
		}
		return changes;
	}

	/** Puts changed blocks back ({@code toBefore}) or redoes them, where nothing else changed them since. */
	public static void revert(ServerLevel level, List<EditorOps.BlockChange> changes, boolean toBefore) {
		if (toBefore) {
			for (int i = changes.size() - 1; i >= 0; i--) {
				EditorOps.BlockChange c = changes.get(i);
				if (level.getBlockState(c.pos()) == c.after()) {
					level.setBlock(c.pos(), c.before(), FLAGS);
				}
			}
		} else {
			for (EditorOps.BlockChange c : changes) {
				if (level.getBlockState(c.pos()) == c.before()) {
					level.setBlock(c.pos(), c.after(), FLAGS);
				}
			}
		}
	}

	private static void set(ServerLevel level, BlockPos pos, BlockState before, BlockState after, List<EditorOps.BlockChange> changes) {
		if (before != after) {
			level.setBlock(pos, after, FLAGS);
			changes.add(new EditorOps.BlockChange(pos, before, after));
		}
	}

	/** The dimension's own ground block (stone, netherrack, end stone). */
	private static BlockState groundBlock(ServerLevel level) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		if (generator instanceof NoiseBasedChunkGenerator noise) {
			return noise.generatorSettings().value().defaultBlock();
		}
		return Blocks.STONE.defaultBlockState();
	}

	private static final Block[] SKINS = {
			Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.MOSS_BLOCK, Blocks.MUD, Blocks.DIRT, Blocks.COARSE_DIRT,
			Blocks.SAND, Blocks.RED_SAND, Blocks.GRAVEL, Blocks.SNOW_BLOCK,
			Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM, Blocks.SOUL_SAND, Blocks.SOUL_SOIL};

	/** The block new ground gets on top, or null to leave it plain. */
	private static BlockState topBlock(BlockState skin) {
		for (Block b : SKINS) {
			if (skin.is(b)) {
				return b.defaultBlockState();
			}
		}
		return null;
	}

	private static BlockState underBlock(BlockState skin) {
		if (skin.is(Blocks.GRASS_BLOCK) || skin.is(Blocks.PODZOL) || skin.is(Blocks.MYCELIUM) || skin.is(Blocks.MOSS_BLOCK)
				|| skin.is(Blocks.SNOW_BLOCK)) {
			return Blocks.DIRT.defaultBlockState();
		}
		if (skin.is(Blocks.SAND) || skin.is(Blocks.RED_SAND) || skin.is(Blocks.MUD) || skin.is(Blocks.SOUL_SOIL) || skin.is(Blocks.SOUL_SAND)) {
			return skin.getBlock().defaultBlockState();
		}
		return null;
	}

	private static long columnKey(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}
}
